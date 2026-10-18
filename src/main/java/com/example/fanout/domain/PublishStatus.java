package com.example.fanout.domain;

/** Terminal/progress status of one publish's fan-out, as reported by a PublishOutcome. */
public enum PublishStatus {
    IN_PROGRESS,
    COMPLETED,
    PARTIAL,
    FAILED
}
