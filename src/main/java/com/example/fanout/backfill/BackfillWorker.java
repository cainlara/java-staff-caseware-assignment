package com.example.fanout.backfill;

import com.example.fanout.domain.DeadLetterRecord;
import com.example.fanout.domain.DeadLetterUnitKind;
import com.example.fanout.domain.EnumeratedEngagement;
import com.example.fanout.domain.EnumerationBatch;
import com.example.fanout.domain.PendingIndexRow;
import com.example.fanout.domain.PendingIndexState;
import com.example.fanout.fanout.AdmissionGate;
import com.example.fanout.fanout.AdmissionGate.Verdict;
import com.example.fanout.fanout.AdmissionHaltedException;
import com.example.fanout.fanout.FanOutCursorRepository;
import com.example.fanout.fanout.FanOutEngine;
import com.example.fanout.fanout.FanOutExhaustedException;
import com.example.fanout.fanout.PermitGuardedDownstream;
import com.example.fanout.fanout.RetryExecutor;
import com.example.fanout.fanout.TaskRunner;
import com.example.fanout.fanout.TransientFanOutException;
import com.example.fanout.ingest.EffectiveVersion;
import com.example.fanout.ingest.IngestedPublish;
import com.example.fanout.observability.IdentifierField;
import com.example.fanout.observability.LogLevel;
import com.example.fanout.observability.NoOpStructuredLogSink;
import com.example.fanout.ports.DeadLetterStore;
import com.example.fanout.ports.EngagementEnumerator;
import com.example.fanout.ports.FanOutMetrics;
import com.example.fanout.ports.IndexWriter;
import com.example.fanout.ports.StructuredLogSink;
import com.example.fanout.ports.WriteResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Backfill pipeline (spec B2–B4, B7–B9, contracts/backfill-worker.md):
 * enumerate per firm in round-robin batch cycles, and per engagement — in
 * order — admit through {@link AdmissionGate}, capture the current version
 * under one backfill-share permit via {@link PermitGuardedDownstream} (the
 * ONLY downstream call path), compare against the effective version, write or
 * skip one idempotent PENDING row, then advance the firm cursor only after
 * every engagement of the batch reached a terminal outcome.
 *
 * <p>Outcomes: {@code COMPLETED} (all cursors complete), {@code HALTED} (halt
 * observed; cursors durable; safe to re-run), {@code GATED} (remaining work
 * held by the flags; re-derived from the cursor when the gate opens),
 * {@code FAILED_DEAD_LETTERED} (run-level failure — corrupt cursor or an
 * exhausted batch/cursor unit; loud, never silent). Per-engagement retry
 * exhaustion dead-letters and still checkpoints (B8: a poisoned engagement
 * never blocks its firm); halt-abandoned engagements are counted, never
 * dead-lettered (D9).
 *
 * <p>Deterministic by construction: injected clock/waiter/jitter, signal-based
 * cycle joins (latches, no sleeps), live flag reads at every admission
 * decision (FR-030/FR-040, FR-055).
 */
public final class BackfillWorker {

    private final EngagementEnumerator enumerator;
    private final List<String> firmIds;
    private final BackfillCursorRepository cursors;
    private final FanOutCursorRepository fanOutCursors;
    private final IndexWriter indexWriter;
    private final PermitGuardedDownstream downstream;
    private final RetryExecutor retry;
    private final AdmissionGate gate;
    private final FanOutMetrics metrics;
    private final DeadLetterStore deadLetterStore;
    private final TaskRunner taskRunner;
    private final BackfillConfig config;
    private final Clock clock;
    private final StructuredLogSink logSink;

    public BackfillWorker(
            EngagementEnumerator enumerator,
            Collection<String> firmIds,
            BackfillCursorRepository cursors,
            FanOutCursorRepository fanOutCursors,
            IndexWriter indexWriter,
            PermitGuardedDownstream downstream,
            RetryExecutor retry,
            AdmissionGate gate,
            FanOutMetrics metrics,
            DeadLetterStore deadLetterStore,
            TaskRunner taskRunner,
            BackfillConfig config,
            Clock clock) {
        this(enumerator, firmIds, cursors, fanOutCursors, indexWriter, downstream, retry, gate,
                metrics, deadLetterStore, taskRunner, config, clock, new NoOpStructuredLogSink());
    }

    /** Log-injecting constructor (spec 005, FR-004). */
    public BackfillWorker(
            EngagementEnumerator enumerator,
            Collection<String> firmIds,
            BackfillCursorRepository cursors,
            FanOutCursorRepository fanOutCursors,
            IndexWriter indexWriter,
            PermitGuardedDownstream downstream,
            RetryExecutor retry,
            AdmissionGate gate,
            FanOutMetrics metrics,
            DeadLetterStore deadLetterStore,
            TaskRunner taskRunner,
            BackfillConfig config,
            Clock clock,
            StructuredLogSink logSink) {
        this.enumerator = Objects.requireNonNull(enumerator, "enumerator must not be null");
        this.firmIds = List.copyOf(new TreeSet<>(Objects.requireNonNull(
                firmIds, "firmIds must not be null")));
        this.cursors = Objects.requireNonNull(cursors, "cursors must not be null");
        this.fanOutCursors = Objects.requireNonNull(fanOutCursors, "fanOutCursors must not be null");
        this.indexWriter = Objects.requireNonNull(indexWriter, "indexWriter must not be null");
        this.downstream = Objects.requireNonNull(downstream, "downstream must not be null");
        this.retry = Objects.requireNonNull(retry, "retry must not be null");
        this.gate = Objects.requireNonNull(gate, "gate must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        this.deadLetterStore = Objects.requireNonNull(deadLetterStore, "deadLetterStore must not be null");
        this.taskRunner = Objects.requireNonNull(taskRunner, "taskRunner must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.logSink = Objects.requireNonNull(logSink, "logSink must not be null");
    }

    /**
     * Run backfill over every configured firm until completion, halt, gates,
     * or a run-level failure. Never throws for halt or per-engagement
     * exhaustion; non-transient failures propagate (crash-test seam).
     */
    public BackfillOutcome run() {
        RunState state = new RunState(clock);

        Map<String, String> startCursors = new LinkedHashMap<>();
        for (String firm : firmIds) {
            String start;
            try {
                start = retry.callWithRetry(
                        BackfillDeadLetterIdentity.cursor(firm),
                        BackfillDeadLetterIdentity.UNKNOWN_PRODUCT,
                        -1,
                        DeadLetterUnitKind.BACKFILL_CURSOR,
                        () -> readCursor(firm));
            } catch (FanOutExhaustedException exhausted) {
                state.deadLettered.incrementAndGet();
                state.failed = true;
                return outcome(BackfillOutcome.BackfillStatus.FAILED_DEAD_LETTERED,
                        firmIds.size(), state);
            } catch (IllegalStateException corrupt) {
                storeDeadLetter(BackfillDeadLetterIdentity.cursor(firm),
                        BackfillDeadLetterIdentity.UNKNOWN_PRODUCT, -1,
                        DeadLetterUnitKind.BACKFILL_CURSOR, 1, corrupt);
                state.deadLettered.incrementAndGet();
                return outcome(BackfillOutcome.BackfillStatus.FAILED_DEAD_LETTERED,
                        firmIds.size(), state);
            }
            startCursors.put(firm, start);
        }

        BackfillScheduler scheduler =
                new BackfillScheduler(firmIds, startCursors, gate, config);
        BackfillOutcome.BackfillStatus status = BackfillOutcome.BackfillStatus.COMPLETED;

        while (true) {
            if (state.failed) {
                status = BackfillOutcome.BackfillStatus.FAILED_DEAD_LETTERED;
                break;
            }
            if (state.halted()) {
                status = BackfillOutcome.BackfillStatus.HALTED;
                break;
            }
            if (scheduler.allComplete()) {
                status = BackfillOutcome.BackfillStatus.COMPLETED;
                break;
            }

            long resolvedBefore = state.resolved.get();
            int completeBefore = scheduler.firmsRemaining();
            BackfillScheduler.Cycle cycle = scheduler.admitCycle();
            if (cycle.halted()) {
                if (state.markHalted()) {
                    logHalt(null, null);
                }
            }

            launchCycle(cycle.admitted(), scheduler, state);

            if (state.failed) {
                status = BackfillOutcome.BackfillStatus.FAILED_DEAD_LETTERED;
                break;
            }
            if (state.halted()) {
                status = BackfillOutcome.BackfillStatus.HALTED;
                break;
            }
            if (scheduler.allComplete()) {
                status = BackfillOutcome.BackfillStatus.COMPLETED;
                break;
            }
            boolean progress = state.resolved.get() > resolvedBefore
                    || scheduler.firmsRemaining() < completeBefore;
            if (!progress) {
                // everything left is gate-held: skipped cycles, gated batches
                status = BackfillOutcome.BackfillStatus.GATED;
                break;
            }
        }

        if (state.halted() && state.haltObservedAt.get() != null) {
            metrics.recordTime("backfill.halt.drain",
                    Duration.between(state.haltObservedAt.get(), clock.instant()));
        }
        return outcome(status, scheduler.firmsRemaining(), state);
    }

    // ---- cycle execution ----

    private void launchCycle(List<BackfillScheduler.Admission> admitted,
                             BackfillScheduler scheduler, RunState state) {
        CountDownLatch latch = new CountDownLatch(admitted.size());
        for (BackfillScheduler.Admission admission : admitted) {
            if (state.failed || state.halted()) {
                // no new admission is launched after the flag was observed
                scheduler.release(admission);
                latch.countDown();
                continue;
            }
            try {
                taskRunner.execute(() -> {
                    try {
                        runBatch(admission, scheduler, state);
                    } finally {
                        scheduler.release(admission);
                        latch.countDown();
                    }
                });
            } catch (RuntimeException runnerFailure) {
                scheduler.release(admission);
                latch.countDown();
                throw runnerFailure;
            }
        }
        await(latch);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("backfill cycle interrupted", interrupted);
        }
    }

    // ---- one batch (one enumeration fetch) ----

    private void runBatch(BackfillScheduler.Admission admission,
                          BackfillScheduler scheduler, RunState state) {
        EnumerationBatch batch;
        try {
            batch = retry.callWithRetry(
                    BackfillDeadLetterIdentity.batch(admission.firmId(), admission.batchIndex()),
                    BackfillDeadLetterIdentity.UNKNOWN_PRODUCT,
                    admission.batchIndex(),
                    DeadLetterUnitKind.BACKFILL_BATCH,
                    () -> fetch(admission));
        } catch (FanOutExhaustedException exhausted) {
            // a failed fetch never advances the cursor (contracts/backfill-worker.md)
            state.deadLettered.incrementAndGet();
            state.failed = true;
            return;
        }

        if (batch.done()) {
            if (!completeFirm(admission, scheduler, state)) {
                return;
            }
            scheduler.markComplete(admission);
            return;
        }

        metrics.increment("backfill.batches.admitted", 1, "firm_id", admission.firmId());
        logSink.log(LogLevel.INFO, "backfill.batch.admitted", clock.instant(),
                batchIdentifiers(admission.firmId(), null));
        boolean aborted = false;
        try {
            for (EnumeratedEngagement item : batch.items()) {
                if (state.failed) {
                    aborted = true;
                    break;
                }
                Verdict verdict = gate.admit(item.productId(), item.firmId());
                if (verdict == Verdict.HALTED) {
                    if (state.markHalted()) {
                        logHalt(admission.firmId(), item.productId());
                    }
                    aborted = true;
                    break;
                }
                if (verdict != Verdict.ADMIT) {
                    // gated: not admitted, not dead-lettered, not buffered —
                    // stays behind this batch cursor (FR-043 / T042)
                    aborted = true;
                    break;
                }
                resolveEngagement(item, admission.batchIndex(), state);
            }
        } catch (AdmissionHaltedException abandoned) {
            // permit waiter abandoned on halt (D9): batch aborts, cursor unchanged
            if (state.markHalted()) {
                logHalt(admission.firmId(), null);
            }
            aborted = true;
        }
        if (aborted) {
            return;
        }

        String nextCursor = batch.nextCursor().orElseThrow();
        if (!advanceFirm(admission, nextCursor, state)) {
            return;
        }
        scheduler.markAdvanced(admission, nextCursor);
        metrics.increment("backfill.batches.completed", 1, "firm_id", admission.firmId());
        logSink.log(LogLevel.INFO, "backfill.batch.completed", clock.instant(),
                batchIdentifiers(admission.firmId(), null));
    }

    private EnumerationBatch fetch(BackfillScheduler.Admission admission) {
        Optional<String> after = admission.afterCursor().isEmpty()
                ? Optional.empty()
                : Optional.of(admission.afterCursor());
        try {
            return enumerator.nextBatch(admission.firmId(), after, config.batchSize());
        } catch (TransientFanOutException failure) {
            throw failure;
        } catch (IllegalArgumentException callerBug) {
            throw callerBug;
        } catch (RuntimeException enumerationFailure) {
            throw new TransientFanOutException(
                    "enumeration failed for " + admission.firmId(), enumerationFailure);
        }
    }

    private boolean completeFirm(BackfillScheduler.Admission admission,
                                 BackfillScheduler scheduler, RunState state) {
        try {
            retry.callWithRetry(
                    BackfillDeadLetterIdentity.cursor(admission.firmId()),
                    BackfillDeadLetterIdentity.UNKNOWN_PRODUCT,
                    -1,
                    DeadLetterUnitKind.BACKFILL_CURSOR,
                    () -> {
                        cursors.complete(admission.firmId(), admission.afterCursor());
                        return null;
                    });
            return true;
        } catch (FanOutExhaustedException exhausted) {
            state.deadLettered.incrementAndGet();
            state.failed = true;
            return false;
        } catch (IllegalStateException corrupt) {
            storeDeadLetter(BackfillDeadLetterIdentity.cursor(admission.firmId()),
                    BackfillDeadLetterIdentity.UNKNOWN_PRODUCT, -1,
                    DeadLetterUnitKind.BACKFILL_CURSOR, 1, corrupt);
            state.deadLettered.incrementAndGet();
            state.failed = true;
            return false;
        }
    }

    private boolean advanceFirm(BackfillScheduler.Admission admission, String nextCursor,
                                RunState state) {
        try {
            retry.callWithRetry(
                    BackfillDeadLetterIdentity.cursor(admission.firmId()),
                    BackfillDeadLetterIdentity.UNKNOWN_PRODUCT,
                    -1,
                    DeadLetterUnitKind.BACKFILL_CURSOR,
                    () -> {
                        cursors.advance(admission.firmId(), nextCursor);
                        return null;
                    });
            return true;
        } catch (FanOutExhaustedException exhausted) {
            state.deadLettered.incrementAndGet();
            state.failed = true;
            return false;
        } catch (IllegalStateException corrupt) {
            storeDeadLetter(BackfillDeadLetterIdentity.cursor(admission.firmId()),
                    BackfillDeadLetterIdentity.UNKNOWN_PRODUCT, -1,
                    DeadLetterUnitKind.BACKFILL_CURSOR, 1, corrupt);
            state.deadLettered.incrementAndGet();
            state.failed = true;
            return false;
        }
    }

    // ---- one engagement ----

    private void resolveEngagement(EnumeratedEngagement item, int batchIndex, RunState state) {
        String eventId = BackfillDeadLetterIdentity.engagement(
                item.firmId(), batchIndex, item.engagementId());
        try {
            retry.callWithRetry(
                    eventId,
                    item.productId(),
                    batchIndex,
                    DeadLetterUnitKind.BACKFILL_ENGAGEMENT,
                    () -> captureAndWrite(item, state));
        } catch (FanOutExhaustedException exhausted) {
            // B8: the poisoned engagement resolves as dead-lettered, its
            // batch still checkpoints
            state.deadLettered.incrementAndGet();
            state.resolved.incrementAndGet();
            metrics.increment("backfill.engagements.dlq", 1,
                    "product_id", item.productId());
            logSink.log(LogLevel.ERROR, "fanout.dlq", clock.instant(),
                    engagementDlqIdentifiers(item));
        } catch (AdmissionHaltedException abandoned) {
            metrics.increment("backfill.halt.abandoned", 1,
                    "product_id", item.productId(), "firm_id", item.firmId());
            throw abandoned;
        }
    }

    private Void captureAndWrite(EnumeratedEngagement item, RunState state) {
        String captured = downstream.load(
                item.engagementId(), item.firmId(), item.productId());
        Optional<IngestedPublish> effective = effectiveVersion(item.productId());
        if (effective.isEmpty() || captured.equals(effective.get().toVersion())) {
            state.noOps.incrementAndGet();
            state.resolved.incrementAndGet();
            metrics.increment("backfill.engagements.noop", 1,
                    "product_id", item.productId());
            return null;
        }

        PendingIndexRow intent = new PendingIndexRow(
                item.engagementId(),
                item.firmId(),
                item.productId(),
                captured,
                effective.get().toVersion(),
                PendingIndexState.PENDING,
                null,
                List.of(),
                null,
                Set.of(effective.get().publishId()),
                1L,
                clock.instant());
        WriteResult result = indexWriter.upsert(intent);
        if (result == WriteResult.REJECTED_STALE) {
            metrics.increment("fanout.cas.reapply", 1, "product_id", item.productId());
            PendingIndexRow current = indexWriter
                    .find(intent.engagementId(), intent.offeredVersion(), intent.state())
                    .orElseThrow(() -> new TransientFanOutException(
                            "stale rejection but row absent: " + intent.engagementId()));
            PendingIndexRow merged = FanOutEngine.merge(current, intent, true);
            WriteResult second = indexWriter.upsert(merged);
            if (second == WriteResult.REJECTED_STALE) {
                throw new TransientFanOutException(
                        "second stale rejection: " + intent.engagementId());
            }
        }
        state.rows.incrementAndGet();
        state.resolved.incrementAndGet();
        metrics.increment("backfill.engagements.written", 1,
                "product_id", item.productId());
        return null;
    }

    /** B7: effective = greatest (publishedAt, publishId) among non-withdrawn. */
    private Optional<IngestedPublish> effectiveVersion(String productId) {
        Set<String> withdrawn = Set.copyOf(fanOutCursors.withdrawnVersions(productId));
        List<IngestedPublish> eligible = fanOutCursors.ingestedPublishes(productId).stream()
                .filter(publish -> !withdrawn.contains(publish.toVersion()))
                .toList();
        return EffectiveVersion.selectEffective(eligible);
    }

    // ---- helpers ----

    private String readCursor(String firmId) {
        try {
            return cursors.startCursor(firmId);
        } catch (TransientFanOutException failure) {
            throw failure;
        } catch (IllegalStateException corrupt) {
            throw corrupt;
        } catch (RuntimeException storeFailure) {
            throw new TransientFanOutException(
                    "cursor read failed for " + firmId, storeFailure);
        }
    }

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

    private BackfillOutcome outcome(BackfillOutcome.BackfillStatus status, int firmsRemaining,
                                    RunState state) {
        return new BackfillOutcome(
                status,
                firmsRemaining,
                state.resolved.get(),
                state.rows.get(),
                state.noOps.get(),
                state.deadLettered.get());
    }

    /** Batch-level identifiers (spec 004): firm_id (+ optional product_id). */
    private static Map<IdentifierField, String> batchIdentifiers(String firmId, String productId) {
        Map<IdentifierField, String> identifiers = new EnumMap<>(IdentifierField.class);
        identifiers.put(IdentifierField.firm_id, firmId);
        if (productId != null) {
            identifiers.put(IdentifierField.product_id, productId);
        }
        return identifiers;
    }

    /**
     * One {@code backfill.halt.observed} per first transition (spec 004): the
     * run may observe halt at cycle level, per engagement, or via an abandoned
     * permit — only the first observation logs.
     */
    private void logHalt(String firmId, String productId) {
        Map<IdentifierField, String> identifiers = new EnumMap<>(IdentifierField.class);
        if (firmId != null) {
            identifiers.put(IdentifierField.firm_id, firmId);
        }
        if (productId != null) {
            identifiers.put(IdentifierField.product_id, productId);
        }
        identifiers.put(IdentifierField.state, "halted");
        logSink.log(LogLevel.WARN, "backfill.halt.observed", clock.instant(), identifiers);
    }

    /**
     * Per-engagement dead-letter (error path, FR-004): the only log event that
     * carries {@code engagement_id} besides retry.
     */
    private static Map<IdentifierField, String> engagementDlqIdentifiers(
            EnumeratedEngagement item) {
        Map<IdentifierField, String> identifiers = new EnumMap<>(IdentifierField.class);
        identifiers.put(IdentifierField.product_id, item.productId());
        identifiers.put(IdentifierField.firm_id, item.firmId());
        identifiers.put(IdentifierField.engagement_id, item.engagementId());
        identifiers.put(IdentifierField.state, DeadLetterUnitKind.BACKFILL_ENGAGEMENT
                .name().toLowerCase(Locale.ROOT));
        return identifiers;
    }

    /** Per-run mutable counters shared by the cycle's jobs. */
    private static final class RunState {
        final AtomicLong resolved = new AtomicLong();
        final AtomicLong rows = new AtomicLong();
        final AtomicLong noOps = new AtomicLong();
        final AtomicInteger deadLettered = new AtomicInteger();
        final AtomicReference<Instant> haltObservedAt = new AtomicReference<>();
        volatile boolean failed;
        private final Clock clock;

        RunState(Clock clock) {
            this.clock = clock;
        }

        boolean halted() {
            return haltObservedAt.get() != null;
        }

        /** First-observer wins: true only on the transition into halt. */
        boolean markHalted() {
            return haltObservedAt.compareAndSet(null, clock.instant());
        }
    }
}
