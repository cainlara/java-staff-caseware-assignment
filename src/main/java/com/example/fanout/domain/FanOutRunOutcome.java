package com.example.fanout.domain;

/**
 * Result of one engine entry call — returned, never thrown, for skip and
 * dead-letter paths (data-model.md FanOutRunOutcome).
 *
 * <ul>
 *   <li>{@code rowsResolved} — writes + duplicate skips + converged re-applies.</li>
 *   <li>{@code deadLetterCount} — units stored in the dead-letter store during
 *       this run (0 on non-dead-letter outcomes).</li>
 * </ul>
 */
public record FanOutRunOutcome(
        FanOutOutcomeStatus status,
        String eventId,
        int chunkCount,
        long rowsResolved,
        int deadLetterCount) {

    public FanOutRunOutcome {
        Checks.requireNonNull(status, "status");
        Checks.requireNonBlank(eventId, "eventId");
        Checks.requireAtLeast(chunkCount, 0, "chunkCount");
        Checks.requireAtLeast(rowsResolved, 0L, "rowsResolved");
        Checks.requireAtLeast(deadLetterCount, 0, "deadLetterCount");
    }
}
