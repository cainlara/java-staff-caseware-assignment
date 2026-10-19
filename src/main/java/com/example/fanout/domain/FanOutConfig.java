package com.example.fanout.domain;

import java.time.Duration;
import java.util.Objects;

/**
 * Every configurable bound of the fan-out engine in one validation-friendly
 * record (spec Assumptions; data-model.md FanOutConfig). Tests override freely.
 *
 * <p>Defaults: chunkSize 500 · poolBound 16 · firmInFlightCap 2 ·
 * retryMaxAttempts 5 · retryBaseDelay 100 ms · retryMaxDelay 30 s ·
 * jitterFactorMin 0.5 · jitterFactorMax 1.5.
 */
public record FanOutConfig(
        int chunkSize,
        int poolBound,
        int firmInFlightCap,
        int retryMaxAttempts,
        Duration retryBaseDelay,
        Duration retryMaxDelay,
        double jitterFactorMin,
        double jitterFactorMax) {

    public FanOutConfig {
        Checks.requireAtLeast(chunkSize, 1, "chunkSize");
        Checks.requireAtLeast(poolBound, 1, "poolBound");
        Checks.requireAtLeast(firmInFlightCap, 1, "firmInFlightCap");
        Checks.requireAtLeast(retryMaxAttempts, 1, "retryMaxAttempts");
        Objects.requireNonNull(retryBaseDelay, "retryBaseDelay must not be null");
        Objects.requireNonNull(retryMaxDelay, "retryMaxDelay must not be null");
        if (retryBaseDelay.isNegative() || retryBaseDelay.isZero()) {
            throw new IllegalArgumentException("retryBaseDelay must be positive");
        }
        if (retryMaxDelay.compareTo(retryBaseDelay) < 0) {
            throw new IllegalArgumentException("retryMaxDelay must be >= retryBaseDelay");
        }
        if (jitterFactorMin < 0.0) {
            throw new IllegalArgumentException("jitterFactorMin must be >= 0.0");
        }
        if (jitterFactorMax <= jitterFactorMin) {
            throw new IllegalArgumentException("jitterFactorMax must be > jitterFactorMin");
        }
    }

    /** Spec-default configuration. */
    public static FanOutConfig defaults() {
        return new FanOutConfig(
                500,
                16,
                2,
                5,
                Duration.ofMillis(100),
                Duration.ofSeconds(30),
                0.5,
                1.5);
    }
}
