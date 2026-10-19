package com.example.fanout.backfill;

import java.util.Objects;

/**
 * Terminal result of one {@link BackfillWorker#run()} (spec B4,
 * contracts/backfill-worker.md).
 *
 * <p>{@code firmsRemaining} counts firms whose cursor is not {@code COMPLETE};
 * the counters are per-run tallies (a restart starts fresh — the durable truth
 * is always the cursor set plus the index rows).
 */
public record BackfillOutcome(
        BackfillStatus status,
        int firmsRemaining,
        long engagementsResolved,
        long rowsWritten,
        long noOps,
        int deadLettered) {

    public BackfillOutcome {
        Objects.requireNonNull(status, "status must not be null");
        requireAtLeast(firmsRemaining, 0, "firmsRemaining");
        requireAtLeast(engagementsResolved, 0L, "engagementsResolved");
        requireAtLeast(rowsWritten, 0L, "rowsWritten");
        requireAtLeast(noOps, 0L, "noOps");
        requireAtLeast(deadLettered, 0, "deadLettered");
    }

    /** How one backfill run ended. */
    public enum BackfillStatus {
        /** Every firm's cursor {@code COMPLETE}; dead-lettered gaps live in the DLQ. */
        COMPLETED,
        /**
         * Halt observed; some firms incomplete; cursors durable; safe to re-run
         * (FR-033).
         */
        HALTED,
        /**
         * All remaining work blocked by the product/firm gates; nothing
         * failed; cursors durable; re-run resumes when the gate opens
         * (FR-043, additive — mirrors the fan-out {@code GATED} outcome).
         */
        GATED,
        /** Run-level failure (corrupt cursor, exhausted batch/cursor unit). */
        FAILED_DEAD_LETTERED
    }

    private static void requireAtLeast(long value, long minimum, String fieldName) {
        if (value < minimum) {
            throw new IllegalArgumentException(fieldName + " must be >= " + minimum);
        }
    }
}
