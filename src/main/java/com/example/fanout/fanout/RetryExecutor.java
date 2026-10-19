package com.example.fanout.fanout;

import com.example.fanout.domain.DeadLetterRecord;
import com.example.fanout.domain.DeadLetterUnitKind;
import com.example.fanout.domain.FanOutConfig;
import com.example.fanout.ports.DeadLetterStore;
import com.example.fanout.ports.FanOutMetrics;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Bounded retry for one work unit (research.md D8, spec R6):
 * {@link TransientFanOutException} retries with exponential backoff from
 * {@code retryBaseDelay} to {@code retryMaxDelay}, each delay scaled by a
 * jitter factor in {@code [jitterFactorMin, jitterFactorMax)} - time comes
 * from the injected {@link Clock} and waiting from the injected
 * {@link BackoffWaiter}, so tests stay deterministic (spec FR-042, FR-072).
 *
 * <p>On exhaustion a {@link DeadLetterRecord} with the unit's full work
 * identity is stored and {@code fanout.dlq} is incremented, then a
 * {@link FanOutExhaustedException} carrying the record is thrown - never a
 * silent drop (spec FR-041). Every scheduled retry increments
 * {@code fanout.retry}. Non-transient failures (e.g. simulated process death)
 * are not caught: they propagate so crash tests stay meaningful.
 */
public final class RetryExecutor {

    private final FanOutConfig config;
    private final BackoffWaiter backoffWaiter;
    private final Clock clock;
    private final JitterSource jitterSource;
    private final FanOutMetrics metrics;
    private final DeadLetterStore deadLetterStore;

    public RetryExecutor(FanOutConfig config, BackoffWaiter backoffWaiter, Clock clock,
                         JitterSource jitterSource, FanOutMetrics metrics,
                         DeadLetterStore deadLetterStore) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.backoffWaiter = Objects.requireNonNull(backoffWaiter, "backoffWaiter must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.jitterSource = Objects.requireNonNull(jitterSource, "jitterSource must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        this.deadLetterStore = Objects.requireNonNull(deadLetterStore, "deadLetterStore must not be null");
    }

    /**
     * Run one unit with bounded retries.
     *
     * @throws FanOutExhaustedException after {@code retryMaxAttempts}
     *         transient failures - the record is already stored and alerted
     * @throws RuntimeException           any non-transient failure, unchanged
     */
    public <T> T callWithRetry(String eventId, String productId, int chunkIndex,
                               DeadLetterUnitKind unitKind, Supplier<T> unit) {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(productId, "productId must not be null");
        Objects.requireNonNull(unitKind, "unitKind must not be null");
        Objects.requireNonNull(unit, "unit must not be null");
        if (chunkIndex < -1) {
            throw new IllegalArgumentException("chunkIndex must be >= -1");
        }
        int maxAttempts = config.retryMaxAttempts();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return unit.get();
            } catch (TransientFanOutException failure) {
                if (attempt == maxAttempts) {
                    throw exhaust(eventId, productId, chunkIndex, unitKind, attempt, failure);
                }
                metrics.increment("fanout.retry", 1, "product_id", productId);
                backoffWaiter.await(delayFor(attempt));
            }
        }
        throw new IllegalStateException("retry loop exhausted without resolution");
    }

    /** Bounded exponential delay with jitter: min(max, base * 2^(n-1)) * factor. */
    private Duration delayFor(int attempt) {
        int exponent = Math.min(attempt - 1, 30);
        Duration uncapped = config.retryBaseDelay().multipliedBy(1L << exponent);
        Duration capped = uncapped.compareTo(config.retryMaxDelay()) > 0
                ? config.retryMaxDelay()
                : uncapped;
        double factor = config.jitterFactorMin()
                + jitterSource.next() * (config.jitterFactorMax() - config.jitterFactorMin());
        return Duration.ofNanos(Math.round(capped.toNanos() * factor));
    }

    private FanOutExhaustedException exhaust(String eventId, String productId, int chunkIndex,
                                             DeadLetterUnitKind unitKind, int attemptCount,
                                             TransientFanOutException failure) {
        DeadLetterRecord record = new DeadLetterRecord(
                eventId,
                productId,
                chunkIndex,
                unitKind,
                attemptCount,
                failure.getClass().getName(),
                String.valueOf(failure.getMessage()),
                clock.instant());
        deadLetterStore.store(record);
        metrics.increment("fanout.dlq", 1, "product_id", productId,
                "unit_kind", unitKind.name());
        return new FanOutExhaustedException(
                "retry budget exhausted for " + unitKind + " of " + eventId, record);
    }
}
