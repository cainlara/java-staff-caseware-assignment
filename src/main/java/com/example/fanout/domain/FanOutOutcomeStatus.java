package com.example.fanout.domain;

/**
 * Outcome status of one engine entry call. Returned, never thrown, for skip
 * and dead-letter paths (data-model.md FanOutRunOutcome).
 */
public enum FanOutOutcomeStatus {
    /** Cursor claimed by this run and moved to COMPLETE with at least one chunk. */
    COMPLETED,
    /** Zero eligible engagements; cursor COMPLETE with totalChunks = 0 (FR-004). */
    COMPLETED_EMPTY,
    /** Cursor already COMPLETE — replay converged, no writes (FR-053/SC-004). */
    SKIPPED_DUPLICATE,
    /** Cursor RUNNING and claimed by another live run (duplicate trigger racing an active run). */
    SKIPPED_IN_PROGRESS,
    /** A unit exhausted retries, was dead-lettered, alert incremented (FR-041). */
    FAILED_DEAD_LETTERED
}
