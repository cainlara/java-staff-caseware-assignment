package com.example.fanout.observability;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Freshness signal consumed by the {@code FRESHNESS_BURN} alert
 * (contracts/alert-hooks.md): windowed p99 of the
 * {@code fanout.publish_to_pending} samples plus the identity of the slowest
 * recent publish for the fire identifiers.
 *
 * <p>Empty ring means "no data" — the evaluator never fires on missing data
 * (spec US3 edge case).
 */
public interface FreshnessSource {

    /** Windowed p99 in nanoseconds; empty when no sample was recorded. */
    OptionalDouble freshnessP99();

    /** Slowest sample in the window (argmax); empty when no sample exists. */
    Optional<Sample> worstSample();

    /** One recorded freshness sample with its publish identity. */
    record Sample(Duration duration, String productId, String publishId) {
    }
}
