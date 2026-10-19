package com.example.fanout.domain;

import java.util.List;
import java.util.Objects;

/**
 * One schedulable chunk of a fan-out run — a positional slice of the ordered
 * eligibility stream (spec R2 / data-model.md FanOutTask). Never carries
 * engagement content.
 *
 * <p><b>Task identity</b>: {@code (kind, eventId, productId, chunkIndex)} —
 * matches the cursor's positional chunking (spec R2/FR-003).
 * {@code firmId} is the fairness key: the firm of the first engagement in the
 * slice (the deterministic firm-grouped order makes it the dominant firm of
 * the chunk). {@code engagements} is defensively copied and unmodifiable;
 * each element is validated by {@link EngagementRef}.
 *
 * <p>Rejected: null kind / blank strings / chunkIndex &lt; 0 / empty list.
 */
public record FanOutTask(
        String eventId,
        String productId,
        FanOutRunKind kind,
        int chunkIndex,
        String firmId,
        List<EngagementRef> engagements) {

    public FanOutTask {
        Checks.requireNonBlank(eventId, "eventId");
        Checks.requireNonBlank(productId, "productId");
        Objects.requireNonNull(kind, "kind must not be null");
        Checks.requireAtLeast(chunkIndex, 0, "chunkIndex");
        Checks.requireNonBlank(firmId, "firmId");
        Objects.requireNonNull(engagements, "engagements must not be null");
        if (engagements.isEmpty()) {
            throw new IllegalArgumentException("engagements must be non-empty");
        }
        engagements = List.copyOf(engagements);
    }
}
