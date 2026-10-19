package com.example.fanout.domain;

import java.util.Objects;

/**
 * Decoded view of one per-run chunk cursor in the checkpoint store — the sole
 * resume truth (spec FR-050, R2). Encoded/decoded only by
 * {@code FanOutCursorRepository}.
 *
 * <p>Invariant: {@code lastCompletedChunkIndex < totalChunks} whenever
 * {@code totalChunks > 0}; {@link FanOutRunStatus#COMPLETE} implies
 * {@code lastCompletedChunkIndex == totalChunks - 1} (or {@code totalChunks == 0}).
 * {@code lastCompletedChunkIndex == -1} means no chunk completed yet.
 */
public record FanOutCursor(
        FanOutRunKind kind,
        String eventId,
        String productId,
        FanOutRunStatus status,
        int totalChunks,
        int lastCompletedChunkIndex) {

    public FanOutCursor {
        Objects.requireNonNull(kind, "kind must not be null");
        Checks.requireNonBlank(eventId, "eventId");
        Checks.requireNonBlank(productId, "productId");
        Objects.requireNonNull(status, "status must not be null");
        Checks.requireAtLeast(totalChunks, 0, "totalChunks");
        Checks.requireAtLeast(lastCompletedChunkIndex, -1, "lastCompletedChunkIndex");
        if (totalChunks > 0 && lastCompletedChunkIndex >= totalChunks) {
            throw new IllegalArgumentException(
                    "lastCompletedChunkIndex must be < totalChunks but was "
                            + lastCompletedChunkIndex + " with totalChunks " + totalChunks);
        }
        if (status == FanOutRunStatus.COMPLETE && totalChunks > 0
                && lastCompletedChunkIndex != totalChunks - 1) {
            throw new IllegalArgumentException(
                    "COMPLETE requires lastCompletedChunkIndex == totalChunks - 1");
        }
    }
}
