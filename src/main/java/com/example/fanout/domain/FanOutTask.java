package com.example.fanout.domain;

/**
 * Schedulable chunk of one publish's fan-out work.
 *
 * <p>Batch identity: {@code (publishId, batchId, batchIndex)} identifies one chunk.
 * Chunking and scheduling behavior are later phases; this record is the identity only.
 */
public record FanOutTask(
        String publishId,
        String batchId,
        int batchIndex,
        int batchSize) {

    public FanOutTask {
        Checks.requireNonBlank(publishId, "publishId");
        Checks.requireNonBlank(batchId, "batchId");
        Checks.requireAtLeast(batchIndex, 0, "batchIndex");
        Checks.requireAtLeast(batchSize, 1, "batchSize");
    }
}
