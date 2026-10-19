package com.example.fanout.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One stable, resumable enumeration slice (spec B1, data-model.md
 * EnumerationBatch).
 *
 * <p>Invariants enforced by the compact constructor:
 * <ul>
 *   <li>{@code items} is never empty — a done batch carries no items;</li>
 *   <li>items are strictly ascending by {@code engagementId}, no duplicates;</li>
 *   <li>{@code nextCursor} is empty iff {@code done}; otherwise it equals the
 *       last item's engagement id (exclusive lower bound for the next call);</li>
 *   <li>{@code done == false} implies {@code items.size() <= limit} and a
 *       present {@code nextCursor}; {@code done == true} implies empty items.</li>
 * </ul>
 */
public record EnumerationBatch(
        List<EnumeratedEngagement> items,
        Optional<String> nextCursor,
        boolean done) {

    public EnumerationBatch {
        Objects.requireNonNull(items, "items must not be null");
        Objects.requireNonNull(nextCursor, "nextCursor must not be null");
        items = List.copyOf(items);
        String previous = null;
        for (EnumeratedEngagement item : items) {
            Objects.requireNonNull(item, "items must not contain null");
            if (previous != null && item.engagementId().compareTo(previous) <= 0) {
                throw new IllegalArgumentException(
                        "items must be strictly ascending by engagementId without duplicates");
            }
            previous = item.engagementId();
        }
        if (done) {
            if (!items.isEmpty()) {
                throw new IllegalArgumentException("a done batch must carry no items");
            }
            if (nextCursor.isPresent()) {
                throw new IllegalArgumentException("a done batch must carry an empty cursor");
            }
        } else {
            if (items.isEmpty()) {
                throw new IllegalArgumentException(
                        "a non-done batch must carry at least one item");
            }
            if (nextCursor.isEmpty()) {
                throw new IllegalArgumentException(
                        "a non-done batch must carry its next cursor");
            }
            if (!nextCursor.get().equals(items.get(items.size() - 1).engagementId())) {
                throw new IllegalArgumentException(
                        "nextCursor must equal the last item's engagementId");
            }
        }
    }

    /** Batch signalling end of enumeration: no items, no cursor. */
    public static EnumerationBatch endOfEnumeration() {
        return new EnumerationBatch(List.of(), Optional.empty(), true);
    }
}
