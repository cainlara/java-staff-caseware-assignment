package com.example.fanout.fakes;

import com.example.fanout.domain.PendingIndexRow;
import com.example.fanout.ports.IndexWriter;
import com.example.fanout.ports.WriteResult;
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

    private record Key(String engagementId, String offeredVersion, com.example.fanout.domain.PendingIndexState state) {
        static Key of(PendingIndexRow row) {
            return new Key(row.engagementId(), row.offeredVersion(), row.state());
        }
    }

    private final Map<Key, PendingIndexRow> rows = new ConcurrentHashMap<>();

    @Override
    public WriteResult upsert(PendingIndexRow row) {
        Objects.requireNonNull(row, "row must not be null");
        AtomicReference<WriteResult> result = new AtomicReference<>();
        rows.compute(Key.of(row), (key, existing) -> {
            if (existing == null) {
                result.set(WriteResult.WRITTEN);
                return row;
            }
            if (sameEffectiveState(existing, row)) {
                result.set(WriteResult.SKIPPED_DUPLICATE);
                return existing;
            }
            if (existing.rowVersion() != row.rowVersion()) {
                result.set(WriteResult.REJECTED_STALE);
                return existing;
            }
            result.set(WriteResult.WRITTEN);
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
        return result.get();
    }

    @Override
    public Optional<PendingIndexRow> find(
            String engagementId, String offeredVersion, com.example.fanout.domain.PendingIndexState state) {
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
                                  com.example.fanout.domain.PendingIndexState state) {
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
