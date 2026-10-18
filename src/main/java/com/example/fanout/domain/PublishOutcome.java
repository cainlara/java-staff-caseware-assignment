package com.example.fanout.domain;

import java.util.Objects;

/**
 * Report of what one publish achieved (coverage measurement / alerting input).
 * Counts are non-negative; {@code publishId} is a mandatory, non-blank natural key.
 */
public record PublishOutcome(
        String publishId,
        long rowsWritten,
        long rowsSkipped,
        long failures,
        PublishStatus status) {

    public PublishOutcome {
        Checks.requireNonBlank(publishId, "publishId");
        Checks.requireAtLeast(rowsWritten, 0L, "rowsWritten");
        Checks.requireAtLeast(rowsSkipped, 0L, "rowsSkipped");
        Checks.requireAtLeast(failures, 0L, "failures");
        Objects.requireNonNull(status, "status must not be null");
    }
}
