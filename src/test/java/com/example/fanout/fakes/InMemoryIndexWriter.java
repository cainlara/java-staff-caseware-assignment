package com.example.fanout.fakes;

import com.example.fanout.domain.PendingIndexRow;
import com.example.fanout.domain.PendingIndexState;
import com.example.fanout.ports.IndexWriter;
import com.example.fanout.ports.WriteResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory {@link IndexWriter} fake for contract tests.
 *
 * <p>Keyed by the idempotency triple {@code (engagementId, offeredVersion, state)}.
 * Compare-and-set via {@code Map.compute}:
 * <ul>
 *   <li>insert — stored as presented, {@link WriteResult#WRITTEN};</li>
 *   <li>identical effective state (all fields except rowVersion/updatedAt) —
 *       {@link WriteResult#SKIPPED_DUPLICATE}, stored state unchanged;</li>
 *   <li>incoming {@code rowVersion} != stored — {@link WriteResult#REJECTED_STALE},
 *       stored state unchanged;</li>
 *   <li>otherwise — stored with {@code rowVersion = stored + 1},
 *       {@link WriteResult#WRITTEN}.</li>
 * </ul>
 */
public final class InMemoryIndexWriter implements IndexWriter {

    /** One audited mutation attempt (spec SC-001/SC-003: create-count == 1). */
    public record AuditEvent(
            String engagementId,
            String offeredVersion,
            PendingIndexState state,
            WriteResult result,
            boolean created,
            boolean updated) {
    }

    private record Key(String engagementId, String offeredVersion, PendingIndexState state) {
        static Key of(PendingIndexRow row) {
            return new Key(row.engagementId(), row.offeredVersion(), row.state());
        }
    }

    private final Map<Key, PendingIndexRow> rows = new ConcurrentHashMap<>();
    private final List<AuditEvent> audit = new ArrayList<>();

    @Override
    public WriteResult upsert(PendingIndexRow row) {
        Objects.requireNonNull(row, "row must not be null");
        AtomicReference<WriteResult> result = new AtomicReference<>();
        AtomicReference<Boolean> created = new AtomicReference<>(false);
        AtomicReference<Boolean> updated = new AtomicReference<>(false);
        rows.compute(Key.of(row), (key, existing) -> {
            created.set(existing == null);
            if (existing == null) {
                result.set(WriteResult.WRITTEN);
                return row;
            }
            if (sameEffectiveState(existing, row)) {
                result.set(WriteResult.SKIPPED_DUPLICATE);
                created.set(false);
                updated.set(false);
                return existing;
            }
            if (existing.rowVersion() != row.rowVersion()) {
                result.set(WriteResult.REJECTED_STALE);
                created.set(false);
                updated.set(false);
                return existing;
            }
            result.set(WriteResult.WRITTEN);
            created.set(false);
            updated.set(true);
            return new PendingIndexRow(
                    row.engagementId(),
                    row.firmId(),
                    row.productId(),
                    row.currentVersion(),
                    row.offeredVersion(),
                    row.state(),
                    row.decision(),
                    row.pinnedRanges(),
                    row.summaryKey(),
                    row.publishIds(),
                    existing.rowVersion() + 1,
                    row.updatedAt());
        });
        synchronized (audit) {
            audit.add(new AuditEvent(row.engagementId(), row.offeredVersion(), row.state(),
                    result.get(), Boolean.TRUE.equals(created.get()), Boolean.TRUE.equals(updated.get())));
        }
        return result.get();
    }

    /** Every stored row, in unspecified order. */
    public List<PendingIndexRow> allRows() {
        return List.copyOf(rows.values());
    }

    /** Number of stored rows. */
    public int rowCount() {
        return rows.size();
    }

    /** Every upsert attempt in call order (inserts, duplicate skips, stale rejections). */
    public List<AuditEvent> audit() {
        synchronized (audit) {
            return List.copyOf(audit);
        }
    }

    /** Number of inserts that actually created a row for one engagement. */
    public long createCount(String engagementId) {
        synchronized (audit) {
            return audit.stream()
                    .filter(event -> event.engagementId().equals(engagementId) && event.created())
                    .count();
        }
    }

    /** Number of mutating (row-version bumping) writes for one engagement. */
    public long updateCount(String engagementId) {
        synchronized (audit) {
            return audit.stream()
                    .filter(event -> event.engagementId().equals(engagementId) && event.updated())
                    .count();
        }
    }

    /** Number of inserts across the whole audit log. */
    public long createdTotal() {
        synchronized (audit) {
            return audit.stream().filter(AuditEvent::created).count();
        }
    }

    @Override
    public Optional<PendingIndexRow> find(
            String engagementId, String offeredVersion, PendingIndexState state) {
        Objects.requireNonNull(engagementId, "engagementId must not be null");
        if (engagementId.isBlank()) {
            throw new IllegalArgumentException("engagementId must be non-blank");
        }
        Objects.requireNonNull(offeredVersion, "offeredVersion must not be null");
        if (offeredVersion.isBlank()) {
            throw new IllegalArgumentException("offeredVersion must be non-blank");
        }
        Objects.requireNonNull(state, "state must not be null");
        return Optional.ofNullable(rows.get(new Key(engagementId, offeredVersion, state)));
    }

    /** Test hook: current stored row for the idempotency triple, or null. */
    public PendingIndexRow stored(String engagementId, String offeredVersion,
                                  PendingIndexState state) {
        return rows.get(new Key(engagementId, offeredVersion, state));
    }

    private static boolean sameEffectiveState(PendingIndexRow a, PendingIndexRow b) {
        return a.engagementId().equals(b.engagementId())
                && a.firmId().equals(b.firmId())
                && a.productId().equals(b.productId())
                && a.currentVersion().equals(b.currentVersion())
                && a.offeredVersion().equals(b.offeredVersion())
                && a.state() == b.state()
                && Objects.equals(a.decision(), b.decision())
                && a.pinnedRanges().equals(b.pinnedRanges())
                && Objects.equals(a.summaryKey(), b.summaryKey())
                && a.publishIds().equals(b.publishIds());
    }
}
