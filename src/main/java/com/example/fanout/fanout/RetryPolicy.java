package com.example.fanout.fanout;

import com.example.fanout.domain.FanOutConfig;
import java.time.Duration;
import java.util.Objects;

/**
 * Retry knobs shared by the fan-out and backfill paths (research.md D10,
 * data-model.md RetryPolicy): bounded attempts, exponential base delay capped
 * at {@code maxDelay}, and a jitter factor in
 * {@code [jitterFactorMin, jitterFactorMax)}.
 *
 * <p>Validation: {@code maxAttempts >= 1}, {@code baseDelay > 0},
 * {@code maxDelay >= baseDelay}, {@code jitterFactorMin >= 0},
 * {@code jitterFactorMax > jitterFactorMin} — the same rules the delivered
 * {@link FanOutConfig} applies to its own copy of these knobs.
 */
public record RetryPolicy(
        int maxAttempts,
        Duration baseDelay,
        Duration maxDelay,
        double jitterFactorMin,
        double jitterFactorMax) {

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        Objects.requireNonNull(baseDelay, "baseDelay must not be null");
        Objects.requireNonNull(maxDelay, "maxDelay must not be null");
        if (baseDelay.isNegative() || baseDelay.isZero()) {
            throw new IllegalArgumentException("baseDelay must be positive");
        }
        if (maxDelay.compareTo(baseDelay) < 0) {
            throw new IllegalArgumentException("maxDelay must be >= baseDelay");
        }
        if (jitterFactorMin < 0.0) {
            throw new IllegalArgumentException("jitterFactorMin must be >= 0.0");
        }
        if (jitterFactorMax <= jitterFactorMin) {
            throw new IllegalArgumentException("jitterFactorMax must be > jitterFactorMin");
        }
    }

    /** Policy view of the delivered {@link FanOutConfig} retry knobs. */
    public static RetryPolicy from(FanOutConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        return new RetryPolicy(
                config.retryMaxAttempts(),
                config.retryBaseDelay(),
                config.retryMaxDelay(),
                config.jitterFactorMin(),
                config.jitterFactorMax());
    }
}
