package com.example.fanout.fanout;

import com.example.fanout.domain.DeadLetterRecord;
import com.example.fanout.domain.DeadLetterUnitKind;
import com.example.fanout.domain.EngagementRef;
import com.example.fanout.domain.FanOutConfig;
import com.example.fanout.domain.FanOutCursor;
import com.example.fanout.domain.FanOutOutcomeStatus;
import com.example.fanout.domain.FanOutRunKind;
import com.example.fanout.domain.FanOutRunOutcome;
import com.example.fanout.domain.FanOutRunStatus;
import com.example.fanout.domain.FanOutTask;
import com.example.fanout.domain.FanOutTrigger;
import com.example.fanout.domain.FanOutTriggerKind;
import com.example.fanout.domain.PendingIndexRow;
import com.example.fanout.domain.PendingIndexState;
import com.example.fanout.ingest.IngestedPublish;
import com.example.fanout.ports.DeadLetterStore;
import com.example.fanout.ports.EngagementEligibilitySource;
import com.example.fanout.ports.FanOutMetrics;
import com.example.fanout.ports.IndexWriter;
import com.example.fanout.ports.WriteResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Consumes phase-2 {@link FanOutTrigger}s and materializes each resolved
 * publish (and each withdraw supersede) into Pending Update Index rows
 * (contracts/fan-out-engine.md).
 *
 * <p>Chunked (R2), fair-sharded (R3), checkpointed (FR-050...FR-053),
 * idempotent (R4), retry/dead-letter bounded (R6). Hot-path invariants: zero
 * downstream load calls and zero capacity-permit acquisitions - this class
 * has no dependency on either port (FR-032), asserted per test via the fake
 * call log.
 *
 * <p>Deterministic by construction: materialized ordering, positional chunks,
 * injected clock/runner/waiter/jitter, signal-based scheduling waits - no
 * sleeps anywhere (FR-072). Logs/metrics carry identifiers and measurements
 * only (FR-070); correctness never depends on metric values.
 */
public final class FanOutEngine {

    private static final Comparator<EngagementRef> CONTRACT_ORDER =
            Comparator.comparing(EngagementRef::firmId).thenComparing(EngagementRef::engagementId);

    private final EngagementEligibilitySource eligibility;
    private final IndexWriter indexWriter;
    private final FanOutCursorRepository cursors;
    private final DeadLetterStore deadLetterStore;
    private final FanOutMetrics metrics;
    private final FanOutConfig config;
    private final TaskRunner taskRunner;
    private final Clock clock;
    private final RetryExecutor retryExecutor;
    private final AdmissionGate gate;

    /** In-process live-run registry: one live runner per run identity. */
    private final Set<RunKey> activeRuns = ConcurrentHashMap.newKeySet();

    public FanOutEngine(
            EngagementEligibilitySource eligibility,
            IndexWriter indexWriter,
            FanOutCursorRepository cursors,
            DeadLetterStore deadLetterStore,
            FanOutMetrics metrics,
            FanOutConfig config,
            TaskRunner taskRunner,
            BackoffWaiter backoffWaiter,
            Clock clock,
            JitterSource jitterSource) {
        this(eligibility, indexWriter, cursors, deadLetterStore, metrics, config, taskRunner,
                backoffWaiter, clock, jitterSource, AdmissionGate.admitAll(metrics));
    }

    /** Gate-aware constructor (feature 004): halt and staged enablement admission. */
    public FanOutEngine(
            EngagementEligibilitySource eligibility,
            IndexWriter indexWriter,
            FanOutCursorRepository cursors,
            DeadLetterStore deadLetterStore,
            FanOutMetrics metrics,
            FanOutConfig config,
            TaskRunner taskRunner,
            BackoffWaiter backoffWaiter,
            Clock clock,
            JitterSource jitterSource,
            AdmissionGate gate) {
        this.eligibility = Objects.requireNonNull(eligibility, "eligibility must not be null");
        this.indexWriter = Objects.requireNonNull(indexWriter, "indexWriter must not be null");
        this.cursors = Objects.requireNonNull(cursors, "cursors must not be null");
        this.deadLetterStore = Objects.requireNonNull(deadLetterStore, "deadLetterStore must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.taskRunner = Objects.requireNonNull(taskRunner, "taskRunner must not be null");
        Objects.requireNonNull(backoffWaiter, "backoffWaiter must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        Objects.requireNonNull(jitterSource, "jitterSource must not be null");
        this.gate = Objects.requireNonNull(gate, "gate must not be null");
        this.retryExecutor = new RetryExecutor(config, backoffWaiter, clock, jitterSource,
                metrics, deadLetterStore);
    }

    // ---- entry points ----

    /** Publish fan-out (kind = PUBLISH). */
    public FanOutRunOutcome runPublish(FanOutTrigger trigger) {
        Objects.requireNonNull(trigger, "trigger must not be null");
        if (trigger.kind() != FanOutTriggerKind.PUBLISH) {
            throw new IllegalArgumentException(
                    "runPublish requires a PUBLISH trigger but kind was " + trigger.kind());
        }
        return run(trigger, FanOutRunKind.PUBLISH);
    }

    /** Withdraw supersede fan-out (kind = WITHDRAW). */
    public FanOutRunOutcome runWithdrawSupersede(FanOutTrigger trigger) {
        Objects.requireNonNull(trigger, "trigger must not be null");
        if (trigger.kind() != FanOutTriggerKind.WITHDRAW) {
            throw new IllegalArgumentException(
                    "runWithdrawSupersede requires a WITHDRAW trigger but kind was "
                            + trigger.kind());
        }
        return run(trigger, FanOutRunKind.WITHDRAW_SUPERSEDE);
    }

    // ---- run lifecycle ----

    private FanOutRunOutcome run(FanOutTrigger trigger, FanOutRunKind kind) {
        Instant start = clock.instant();
        RunKey runKey = new RunKey(kind, trigger.eventId(), trigger.productId());
        if (!activeRuns.add(runKey)) {
            metrics.increment("fanout.duplicate.run", 1, "product_id", trigger.productId());
            return new FanOutRunOutcome(FanOutOutcomeStatus.SKIPPED_IN_PROGRESS,
                    trigger.eventId(), 0, 0, 0);
        }
        try {
            return executeRun(trigger, kind, start);
        } finally {
            activeRuns.remove(runKey);
        }
    }

    private FanOutRunOutcome executeRun(FanOutTrigger trigger, FanOutRunKind kind, Instant start) {
        String eventId = trigger.eventId();
        String productId = trigger.productId();

        // 1. publish: resolve to-version (phase-2 record is the source of truth);
        //    supersede: resolve withdrawn version + record durable marker + pick next
        final String toVersion;
        final String nextVersion;
        if (kind == FanOutRunKind.PUBLISH) {
            toVersion = cursors.ingestedPublish(eventId)
                    .orElseThrow(() -> new IllegalStateException(
                            "ingested publish record missing for " + eventId)).toVersion();
            nextVersion = null;
        } else {
            String withdrawnVersion = cursors
                    .ingestedPublish(trigger.matchedPublishId())
                    .orElseThrow(() -> new IllegalStateException(
                            "ingested publish record missing for matched publish "
                                    + trigger.matchedPublishId()))
                    .toVersion();
            try {
                // durable marker before any supersede write (D7, FR-060)
                cursors.markWithdrawn(productId, withdrawnVersion);
                toVersion = withdrawnVersion;
                nextVersion = selectNextVersion(trigger, withdrawnVersion);
            } catch (RuntimeException e) {
                storeDeadLetter(eventId, productId, -1, DeadLetterUnitKind.SUPERSEDE_PLAN, 1, e);
                return new FanOutRunOutcome(FanOutOutcomeStatus.FAILED_DEAD_LETTERED,
                        eventId, 0, 0, 1);
            }
        }

        // 2. materialize input (R1 contract order; sorted defensively) through
        //    the bounded retry policy; exhaustion dead-letters the unit (R6)
        List<EngagementRef> refs;
        try {
            refs = retryExecutor.callWithRetry(eventId, productId, -1,
                    DeadLetterUnitKind.ELIGIBILITY_STREAM,
                    () -> streamFor(kind, trigger, toVersion, eventId, productId)
                            .sorted(CONTRACT_ORDER)
                            .toList());
        } catch (FanOutExhaustedException exhausted) {
            return new FanOutRunOutcome(FanOutOutcomeStatus.FAILED_DEAD_LETTERED,
                    eventId, 0, 0, 1);
        }

        // 3. partition into positional chunks (R2)
        List<FanOutTask> chunks = partition(refs, trigger, kind, productId, config.chunkSize());

        // 4. claim the run (FR-050)
        int totalChunks = chunks.size();
        boolean claimed;
        try {
            claimed = cursors.claimRun(kind, eventId, productId, totalChunks);
        } catch (RuntimeException e) {
            storeDeadLetter(eventId, productId, -1, DeadLetterUnitKind.CURSOR_WRITE, 1, e);
            return new FanOutRunOutcome(FanOutOutcomeStatus.FAILED_DEAD_LETTERED, eventId, 0, 0, 1);
        }

        int startChunk = 0;
        if (!claimed) {
            FanOutCursor cursor;
            try {
                Optional<FanOutCursor> existing = cursors.readRun(kind, eventId, productId);
                if (existing.isEmpty()) {
                    throw new IllegalStateException("cursor claim lost but no cursor readable");
                }
                cursor = existing.get();
            } catch (RuntimeException e) {
                storeDeadLetter(eventId, productId, -1, DeadLetterUnitKind.CURSOR_WRITE, 1, e);
                return new FanOutRunOutcome(FanOutOutcomeStatus.FAILED_DEAD_LETTERED,
                        eventId, 0, 0, 1);
            }
            if (cursor.status() == FanOutRunStatus.COMPLETE) {
                metrics.increment("fanout.duplicate.run", 1, "product_id", productId);
                return new FanOutRunOutcome(FanOutOutcomeStatus.SKIPPED_DUPLICATE,
                        eventId, cursor.totalChunks(), 0, 0);
            }
            if (cursor.totalChunks() != totalChunks) {
                storeDeadLetter(eventId, productId, -1, DeadLetterUnitKind.CURSOR_WRITE, 1,
                        new IllegalStateException("corrupt cursor: chunk count mismatch "
                                + cursor.totalChunks() + " vs " + totalChunks));
                return new FanOutRunOutcome(FanOutOutcomeStatus.FAILED_DEAD_LETTERED,
                        eventId, totalChunks, 0, 1);
            }
            startChunk = cursor.lastCompletedChunkIndex() + 1;
        }

        // 5. empty run: completed cursor, zero writes (FR-004 / FR-063)
        if (totalChunks == 0) {
            cursors.complete(kind, eventId, productId, 0);
            recordPublishTimer(start, productId);
            return new FanOutRunOutcome(FanOutOutcomeStatus.COMPLETED_EMPTY, eventId, 0, 0, 0);
        }

        // 6. resume continuation with nothing left: just complete (FR-051)
        if (startChunk >= totalChunks) {
            cursors.complete(kind, eventId, productId, totalChunks);
            recordPublishTimer(start, productId);
            return new FanOutRunOutcome(FanOutOutcomeStatus.COMPLETED, eventId, totalChunks, 0, 0);
        }

        // 7. live run-level halt admission (FR-030): pending chunks stay behind
        //    the durable prefix; the trigger is not dropped, only deferred
        if (gate.isHalted()) {
            return new FanOutRunOutcome(FanOutOutcomeStatus.HALTED, eventId, totalChunks, 0, 0);
        }

        // 8. schedule remaining chunks through the fair scheduler on the runner,
        //    each pick re-observing live admission (halt / staged enablement)
        var context = new RunContext(kind, eventId, productId, totalChunks,
                kind == FanOutRunKind.PUBLISH, toVersion, nextVersion);
        List<FanOutTask> remaining = List.copyOf(chunks.subList(startChunk, totalChunks));
        var scheduler = new FairShardScheduler(remaining, config.poolBound(),
                config.firmInFlightCap(),
                task -> gate.admit(task.productId(), task.firmId())
                        == AdmissionGate.Verdict.ADMIT);
        FairShardScheduler.SchedulerResult scheduled =
                scheduler.runAllWithResult(taskRunner, task -> executeChunk(task, context));
        if (scheduled == FairShardScheduler.SchedulerResult.AWAITING_ADMISSION) {
            // halt or gates hold the remaining chunks: the cursor keeps the
            // contiguous prefix (advance already persisted it) and never
            // completes; the trigger resumes from it (FR-042 / FR-043)
            FanOutOutcomeStatus held = gate.isHalted()
                    ? FanOutOutcomeStatus.HALTED
                    : FanOutOutcomeStatus.GATED;
            return new FanOutRunOutcome(held, eventId, totalChunks,
                    context.rowsResolved.get(), context.deadLetterCount.get());
        }

        // 9. complete the cursor; record the freshness timer (FR-071 / SC-010)
        cursors.complete(kind, eventId, productId, totalChunks);
        recordPublishTimer(start, productId);

        FanOutOutcomeStatus status = context.deadLetterCount.get() > 0
                ? FanOutOutcomeStatus.FAILED_DEAD_LETTERED
                : FanOutOutcomeStatus.COMPLETED;
        return new FanOutRunOutcome(status, eventId, totalChunks,
                context.rowsResolved.get(), context.deadLetterCount.get());
    }

    private Stream<EngagementRef> streamFor(FanOutRunKind kind,
                                                             FanOutTrigger trigger,
                                                             String toVersion, String eventId,
                                                             String productId) {
        if (kind == FanOutRunKind.PUBLISH) {
            return eligibility.streamEligible(productId, eventId);
        }
        return eligibility.streamOffering(productId, toVersion);
    }

    /**
     * Next eligible non-withdrawn version (spec R7): greatest
     * {@code (publishedAt, publishId)} among ingested publishes whose
     * to-version is not withdrawn and is strictly newer than the withdrawn
     * version's publish under phase-2 ordering; none => null (CREATED target).
     */
    private String selectNextVersion(FanOutTrigger trigger, String withdrawnVersion) {
        IngestedPublish withdrawnPublish = cursors
                .ingestedPublish(trigger.matchedPublishId())
                .orElseThrow(() -> new IllegalStateException(
                        "ingested publish record missing for matched publish "
                                + trigger.matchedPublishId()));
        Set<String> withdrawn = Set.copyOf(cursors.withdrawnVersions(trigger.productId()));
        return cursors.ingestedPublishes(trigger.productId()).stream()
                .filter(p -> !withdrawn.contains(p.toVersion()))
                .filter(p -> strictlyNewer(p, withdrawnPublish))
                .max(Comparator
                        .comparingLong((IngestedPublish p) -> p.publishedAt().toEpochMilli())
                        .thenComparing(IngestedPublish::publishId))
                .map(IngestedPublish::toVersion)
                .orElse(null);
    }

    private static boolean strictlyNewer(IngestedPublish candidate, IngestedPublish reference) {
        int byTime = candidate.publishedAt().compareTo(reference.publishedAt());
        if (byTime != 0) {
            return byTime > 0;
        }
        return candidate.publishId().compareTo(reference.publishId()) > 0;
    }

    private static List<FanOutTask> partition(List<EngagementRef> refs, FanOutTrigger trigger,
                                              FanOutRunKind kind, String productId, int chunkSize) {
        List<FanOutTask> chunks = new ArrayList<>();
        int total = refs.size();
        for (int offset = 0; offset < total; offset += chunkSize) {
            int end = Math.min(offset + chunkSize, total);
            List<EngagementRef> slice = List.copyOf(refs.subList(offset, end));
            chunks.add(new FanOutTask(
                    trigger.eventId(), productId, kind, chunks.size(),
                    slice.getFirst().firmId(), slice));
        }
        return chunks;
    }

    // ---- chunk execution ----

    private void executeChunk(FanOutTask task, RunContext context) {
        metrics.increment("fanout.chunks.started", 1, "product_id", context.productId);
        try {
            retryExecutor.callWithRetry(context.eventId, context.productId, task.chunkIndex(),
                    DeadLetterUnitKind.CHUNK_WRITE, () -> {
                        for (EngagementRef ref : task.engagements()) {
                            writeRow(ref, context);
                        }
                        return null;
                    });
            context.rowsResolved.addAndGet(task.engagements().size());
            metrics.increment("fanout.rows.resolved", task.engagements().size(),
                    "product_id", context.productId);
        } catch (FanOutExhaustedException exhausted) {
            // record stored and fanout.dlq emitted by the RetryExecutor; the
            // run keeps other chunks going and ends FAILED_DEAD_LETTERED (FR-041)
            context.deadLetterCount.incrementAndGet();
        }
        // dead-lettered chunks still advance the cursor so the run terminates;
        // the dead-letter store holds the gap for replay (contract step 5)
        metrics.increment("fanout.chunks.completed", 1, "product_id", context.productId);
        context.markChunkResolved(task.chunkIndex());
    }

    private void writeRow(EngagementRef ref, RunContext context) {
        PendingIndexRow intent = context.intentFor(ref, indexWriter, clock.instant());
        WriteResult result = indexWriter.upsert(intent);
        if (result == WriteResult.REJECTED_STALE) {
            metrics.increment("fanout.cas.reapply", 1, "product_id", context.productId);
            PendingIndexRow current = indexWriter
                    .find(intent.engagementId(), intent.offeredVersion(), intent.state())
                    .orElseThrow(() -> new TransientFanOutException(
                            "stale rejection but row absent: " + intent.engagementId()));
            PendingIndexRow merged = merge(current, intent, context.publishKind);
            WriteResult second = indexWriter.upsert(merged);
            if (second == WriteResult.REJECTED_STALE) {
                throw new TransientFanOutException(
                        "second stale rejection: " + intent.engagementId());
            }
        }
    }

    /**
     * Deterministic re-apply merge (spec R4 / FR-021 / data-model.md):
     * identity fields from the intent; decision/pinnedRanges/summaryKey from
     * the stored row for publish-kind writes (history preserved), from the
     * supersede intent's preserved copies for supersede writes (same bytes
     * copied from the stored row); publishIds union; presented rowVersion =
     * stored current version (the writer stores +1); updatedAt from the
     * intent (the later writer).
     */
    public static PendingIndexRow merge(PendingIndexRow current, PendingIndexRow intent,
                                        boolean publishKind) {
        String decision = publishKind ? current.decision() : intent.decision();
        List<String> pinnedRanges = publishKind ? current.pinnedRanges() : intent.pinnedRanges();
        String summaryKey = publishKind ? current.summaryKey() : intent.summaryKey();
        Set<String> publishIds = new HashSet<>(current.publishIds());
        publishIds.addAll(intent.publishIds());
        return new PendingIndexRow(
                intent.engagementId(),
                intent.firmId(),
                intent.productId(),
                intent.currentVersion(),
                intent.offeredVersion(),
                intent.state(),
                decision,
                pinnedRanges,
                summaryKey,
                Set.copyOf(publishIds),
                current.rowVersion(),
                intent.updatedAt());
    }

    // ---- helpers ----

    private void storeDeadLetter(String eventId, String productId, int chunkIndex,
                                 DeadLetterUnitKind unitKind, int attemptCount,
                                 RuntimeException failure) {
        DeadLetterRecord record = new DeadLetterRecord(
                eventId,
                productId,
                chunkIndex,
                unitKind,
                attemptCount,
                failure.getClass().getName(),
                String.valueOf(failure.getMessage()),
                clock.instant());
        deadLetterStore.store(record);
        metrics.increment("fanout.dlq", 1, "product_id", productId,
                "unit_kind", unitKind.name());
    }

    private void recordPublishTimer(Instant start, String productId) {
        metrics.recordTime("fanout.publish_to_pending",
                Duration.between(start, clock.instant()), "product_id", productId);
    }

    /** Durable run identity: (kind, eventId, productId). */
    private record RunKey(FanOutRunKind kind, String eventId, String productId) {
    }

    /**
     * Per-run mutable state shared by chunk handlers on the task runner.
     * Cursor advancement tracks the largest contiguous completed prefix, so
     * out-of-order chunk completion (round-robin scheduling) never lets a
     * lower chunk index regress the resume point.
     */
    private final class RunContext {
        final FanOutRunKind kind;
        final String eventId;
        final String productId;
        final int totalChunks;
        final boolean publishKind;
        final String toVersion;
        final String nextVersion;
        final AtomicLong rowsResolved = new AtomicLong();
        final AtomicInteger deadLetterCount = new AtomicInteger();
        private final boolean[] resolvedChunks;
        private int persistedLast = -1;

        RunContext(FanOutRunKind kind, String eventId, String productId, int totalChunks,
                   boolean publishKind, String toVersion, String nextVersion) {
            this.kind = kind;
            this.eventId = eventId;
            this.productId = productId;
            this.totalChunks = totalChunks;
            this.publishKind = publishKind;
            this.toVersion = toVersion;
            this.nextVersion = nextVersion;
            this.resolvedChunks = new boolean[totalChunks];
        }

        synchronized void markChunkResolved(int chunkIndex) {
            resolvedChunks[chunkIndex] = true;
            int last = -1;
            while (last + 1 < totalChunks && resolvedChunks[last + 1]) {
                last++;
            }
            if (last > persistedLast && last >= 0) {
                persistedLast = last;
                cursors.advance(kind, eventId, productId, totalChunks, last);
            }
        }

        /**
         * Intended row for one engagement: publish writes a fresh PENDING
         * row; supersede writes carry the preserved history of the
         * withdrawn-version row and target {@code PENDING(next)} or
         * {@code CREATED} (contract step 4).
         */
        PendingIndexRow intentFor(EngagementRef ref, IndexWriter writer, Instant now) {
            if (publishKind) {
                return new PendingIndexRow(
                        ref.engagementId(), ref.firmId(), ref.productId(),
                        ref.currentVersion(), toVersion, PendingIndexState.PENDING,
                        null, List.of(), null, Set.of(eventId), 1L, now);
            }
            PendingIndexRow stored = writer
                    .find(ref.engagementId(), toVersion, PendingIndexState.PENDING)
                    .orElse(null);
            String decision = stored == null ? null : stored.decision();
            List<String> pinnedRanges = stored == null ? List.of() : stored.pinnedRanges();
            String summaryKey = stored == null ? null : stored.summaryKey();
            Set<String> publishIds = new HashSet<>();
            if (stored != null) {
                publishIds.addAll(stored.publishIds());
            }
            publishIds.add(eventId);
            boolean targetPending = nextVersion != null;
            return new PendingIndexRow(
                    ref.engagementId(), ref.firmId(), ref.productId(),
                    ref.currentVersion(),
                    targetPending ? nextVersion : toVersion,
                    targetPending ? PendingIndexState.PENDING : PendingIndexState.CREATED,
                    decision, pinnedRanges, summaryKey,
                    Set.copyOf(publishIds), 1L, now);
        }
    }
}
