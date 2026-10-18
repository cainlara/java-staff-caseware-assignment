package com.example.fanout.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One metadata row in the Pending Update Index.
 *
 * <p>Holds metadata identifiers only — never engagement content (FR-016).
 * Idempotency key: {@code (engagementId, offeredVersion, state)}.
 * {@code rowVersion} is the compare-and-set token (>= 1); writers present the
 * expected current version and stale writes are rejected by {@code IndexWriter}.
 */
public record PendingIndexRow(
        String engagementId,
        String firmId,
        String productId,
        String currentVersion,
        String offeredVersion,
        PendingIndexState state,
        String decision,
        List<String> pinnedRanges,
        String summaryKey,
        Set<String> publishIds,
        long rowVersion,
        Instant updatedAt) {

    public PendingIndexRow {
        Checks.requireNonBlank(engagementId, "engagementId");
        Checks.requireNonBlank(firmId, "firmId");
        Checks.requireNonBlank(productId, "productId");
        Checks.requireNonBlank(currentVersion, "currentVersion");
        Checks.requireNonBlank(offeredVersion, "offeredVersion");
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(pinnedRanges, "pinnedRanges must not be null");
        pinnedRanges = pinnedRanges.stream()
                .map(range -> Checks.requireNonBlank(range, "pinnedRanges element"))
                .toList();
        Objects.requireNonNull(publishIds, "publishIds must not be null");
        if (publishIds.isEmpty()) {
            throw new IllegalArgumentException("publishIds must be non-empty");
        }
        publishIds = Set.copyOf(publishIds.stream()
                .map(id -> Checks.requireNonBlank(id, "publishIds element"))
                .toList());
        Checks.requireAtLeast(rowVersion, 1L, "rowVersion");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }
}
