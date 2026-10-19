package com.example.fanout.observability;

import java.time.Duration;
import java.util.Objects;

/**
 * Alert limits and windows (spec data-model.md, FR-014). Immutable;
 * runtime change happens by swapping the provider's value, never by mutating
 * a record. Invalid records fail loud at construction, so a bad hot-swap is
 * rejected at the provider boundary.
 */
public record AlertThresholds(
        Duration freshnessP99Limit,
        Duration freshnessWindow,
        Duration coverageAgeLimit,
        Duration coverageWindow,
        Duration dlqWindow,
        Duration backfillStallLimit,
        Duration backfillWindow,
        Duration saturationWindow) {

    public AlertThresholds {
        Objects.requireNonNull(freshnessP99Limit, "freshnessP99Limit must not be null");
        Objects.requireNonNull(freshnessWindow, "freshnessWindow must not be null");
        Objects.requireNonNull(coverageAgeLimit, "coverageAgeLimit must not be null");
        Objects.requireNonNull(coverageWindow, "coverageWindow must not be null");
        Objects.requireNonNull(dlqWindow, "dlqWindow must not be null");
        Objects.requireNonNull(backfillStallLimit, "backfillStallLimit must not be null");
        Objects.requireNonNull(backfillWindow, "backfillWindow must not be null");
        Objects.requireNonNull(saturationWindow, "saturationWindow must not be null");
        requirePositive(freshnessP99Limit, "freshnessP99Limit");
        requirePositive(freshnessWindow, "freshnessWindow");
        requirePositive(coverageAgeLimit, "coverageAgeLimit");
        requirePositive(coverageWindow, "coverageWindow");
        requirePositive(dlqWindow, "dlqWindow");
        requirePositive(backfillStallLimit, "backfillStallLimit");
        requirePositive(backfillWindow, "backfillWindow");
        requirePositive(saturationWindow, "saturationWindow");
        if (coverageAgeLimit.compareTo(Duration.ofHours(1)) < 0) {
            throw new IllegalArgumentException(
                    "coverageAgeLimit must be at least 1 hour, was " + coverageAgeLimit);
        }
    }

    /** Spec defaults (data-model.md). */
    public static AlertThresholds defaults() {
        return new AlertThresholds(
                Duration.ofSeconds(60),
                Duration.ofMinutes(5),
                Duration.ofHours(24),
                Duration.ofMinutes(10),
                Duration.ofMinutes(1),
                Duration.ofMinutes(15),
                Duration.ofMinutes(5),
                Duration.ofMinutes(2));
    }

    private static void requirePositive(Duration value, String name) {
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive, was " + value);
        }
    }
}
