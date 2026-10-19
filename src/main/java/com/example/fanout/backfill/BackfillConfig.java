package com.example.fanout.backfill;

import com.example.fanout.domain.FanOutConfig;
import com.example.fanout.fanout.RetryPolicy;
import java.time.Duration;
import java.util.Objects;

/**
 * Every backfill knob in one validation-friendly record (data-model.md
 * BackfillConfig, research.md D11) — mirrors the delivered
 * {@code FanOutConfig} pattern; tests override freely.
 *
 * <p>Defaults: batchSize 100 · firmInFlightCap 1 · parentC 20 · bBackfill 18 ·
 * bRepair 2 · haltPollInterval 1 s · haltDrainBound 2 min ·
 * retryPolicy RetryPolicy(5, 100 ms, 30 s, 0.5, 1.5).
 *
 * <p>The compact constructor rejects {@code bBackfill + bRepair > parentC} at
 * construction (FR-021 startup rejection) and every bound that must stay
 * positive.
 */
public record BackfillConfig(
        int batchSize,
        int firmInFlightCap,
        int parentC,
        int bBackfill,
        int bRepair,
        Duration haltPollInterval,
        Duration haltDrainBound,
        RetryPolicy retryPolicy) {

    public BackfillConfig {
        requireAtLeast(batchSize, 1, "batchSize");
        requireAtLeast(firmInFlightCap, 1, "firmInFlightCap");
        requireAtLeast(parentC, 1, "parentC");
        requireAtLeast(bBackfill, 1, "bBackfill");
        requireAtLeast(bRepair, 0, "bRepair");
        Objects.requireNonNull(haltPollInterval, "haltPollInterval must not be null");
        Objects.requireNonNull(haltDrainBound, "haltDrainBound must not be null");
        if (haltPollInterval.isNegative() || haltPollInterval.isZero()) {
            throw new IllegalArgumentException("haltPollInterval must be positive");
        }
        if (haltDrainBound.isNegative() || haltDrainBound.isZero()) {
            throw new IllegalArgumentException("haltDrainBound must be positive");
        }
        Objects.requireNonNull(retryPolicy, "retryPolicy must not be null");
        if (bBackfill + bRepair > parentC) {
            throw new IllegalArgumentException(
                    "bBackfill + bRepair must be <= parentC but was "
                            + (bBackfill + bRepair) + " > " + parentC);
        }
    }

    /** Spec-default configuration. */
    public static BackfillConfig defaults() {
        return new BackfillConfig(
                100,
                1,
                20,
                18,
                2,
                Duration.ofSeconds(1),
                Duration.ofMinutes(2),
                new RetryPolicy(5, Duration.ofMillis(100), Duration.ofSeconds(30), 0.5, 1.5));
    }

    /**
     * {@link FanOutConfig} view of the retry knobs, for the admission pause of
     * the {@code PermitGuardedDownstream} built on this configuration's share.
     */
    public FanOutConfig permitGuardConfig() {
        return new FanOutConfig(
                batchSize,
                bBackfill,
                firmInFlightCap,
                retryPolicy.maxAttempts(),
                retryPolicy.baseDelay(),
                retryPolicy.maxDelay(),
                retryPolicy.jitterFactorMin(),
                retryPolicy.jitterFactorMax());
    }

    /** Capacity partition implied by this configuration. */
    public CapacityShares shares() {
        return new CapacityShares(parentC, bBackfill, bRepair);
    }

    private static int requireAtLeast(int value, int minimum, String fieldName) {
        if (value < minimum) {
            throw new IllegalArgumentException(fieldName + " must be >= " + minimum
                    + " but was " + value);
        }
        return value;
    }
}
