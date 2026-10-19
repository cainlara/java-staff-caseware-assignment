package com.example.fanout.fanout;

import com.example.fanout.domain.DeadLetterRecord;
import com.example.fanout.domain.DeadLetterUnitKind;
import com.example.fanout.domain.FanOutConfig;
import com.example.fanout.observability.IdentifierField;
import com.example.fanout.observability.LogLevel;
import com.example.fanout.observability.NoOpStructuredLogSink;
import com.example.fanout.ports.DeadLetterStore;
import com.example.fanout.ports.FanOutMetrics;
import com.example.fanout.ports.StructuredLogSink;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
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

    private final RetryPolicy policy;
    private final BackoffWaiter backoffWaiter;
    private final Clock clock;
    private final JitterSource jitterSource;
    private final FanOutMetrics metrics;
    private final DeadLetterStore deadLetterStore;
    private final StructuredLogSink logSink;

    /** Delivered entry point: retry knobs carried by {@link FanOutConfig}. */
    public RetryExecutor(FanOutConfig config, BackoffWaiter backoffWaiter, Clock clock,
                         JitterSource jitterSource, FanOutMetrics metrics,
                         DeadLetterStore deadLetterStore) {
        this(RetryPolicy.from(config), backoffWaiter, clock, jitterSource, metrics,
                deadLetterStore);
    }

    /** Delivered entry point with log sink (spec 005, FR-004). */
    public RetryExecutor(FanOutConfig config, BackoffWaiter backoffWaiter, Clock clock,
                         JitterSource jitterSource, FanOutMetrics metrics,
                         DeadLetterStore deadLetterStore, StructuredLogSink logSink) {
        this(RetryPolicy.from(config), backoffWaiter, clock, jitterSource, metrics,
                deadLetterStore, logSink);
    }

    /** Shared-policy entry point (research.md D10): backfill passes its own policy. */
    public RetryExecutor(RetryPolicy policy, BackoffWaiter backoffWaiter, Clock clock,
                         JitterSource jitterSource, FanOutMetrics metrics,
                         DeadLetterStore deadLetterStore) {
        this(policy, backoffWaiter, clock, jitterSource, metrics, deadLetterStore,
                new NoOpStructuredLogSink());
    }

    /** Log-injecting entry point (spec 005, FR-004). */
    public RetryExecutor(RetryPolicy policy, BackoffWaiter backoffWaiter, Clock clock,
                         JitterSource jitterSource, FanOutMetrics metrics,
                         DeadLetterStore deadLetterStore, StructuredLogSink logSink) {
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.backoffWaiter = Objects.requireNonNull(backoffWaiter, "backoffWaiter must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.jitterSource = Objects.requireNonNull(jitterSource, "jitterSource must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        this.deadLetterStore = Objects.requireNonNull(deadLetterStore, "deadLetterStore must not be null");
        this.logSink = Objects.requireNonNull(logSink, "logSink must not be null");
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
        int maxAttempts = policy.maxAttempts();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return unit.get();
            } catch (TransientFanOutException failure) {
                if (attempt == maxAttempts) {
                    throw exhaust(eventId, productId, chunkIndex, unitKind, attempt, failure);
                }
                metrics.increment("fanout.retry", 1, "product_id", productId);
                logSink.log(LogLevel.WARN, "fanout.retry", clock.instant(),
                        retryIdentifiers(productId, chunkIndex));
                backoffWaiter.await(delayFor(attempt));
            }
        }
        throw new IllegalStateException("retry loop exhausted without resolution");
    }

    /** Bounded exponential delay with jitter: min(max, base * 2^(n-1)) * factor. */
    private Duration delayFor(int attempt) {
        int exponent = Math.min(attempt - 1, 30);
        Duration uncapped = policy.baseDelay().multipliedBy(1L << exponent);
        Duration capped = uncapped.compareTo(policy.maxDelay()) > 0
                ? policy.maxDelay()
                : uncapped;
        double factor = policy.jitterFactorMin()
                + jitterSource.next() * (policy.jitterFactorMax() - policy.jitterFactorMin());
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
        logSink.log(LogLevel.ERROR, "fanout.dlq", clock.instant(),
                dlqIdentifiers(productId, chunkIndex, unitKind));
        return new FanOutExhaustedException(
                "retry budget exhausted for " + unitKind + " of " + eventId, record);
    }

    /**
     * Retry/DLQ identifiers (spec 004): product_id plus chunk position when the
     * unit is positional. No engagement content; event identity lives in the
     * stored {@link DeadLetterRecord}, not the log line.
     */
    private static Map<IdentifierField, String> retryIdentifiers(String productId, int chunkIndex) {
        Map<IdentifierField, String> identifiers = new EnumMap<>(IdentifierField.class);
        identifiers.put(IdentifierField.product_id, productId);
        if (chunkIndex >= 0) {
            identifiers.put(IdentifierField.chunk_id, String.valueOf(chunkIndex));
        }
        return identifiers;
    }

    private static Map<IdentifierField, String> dlqIdentifiers(String productId, int chunkIndex,
                                                               DeadLetterUnitKind unitKind) {
        Map<IdentifierField, String> identifiers = new EnumMap<>(IdentifierField.class);
        identifiers.put(IdentifierField.product_id, productId);
        if (chunkIndex >= 0) {
            identifiers.put(IdentifierField.chunk_id, String.valueOf(chunkIndex));
        }
        identifiers.put(IdentifierField.state, unitKind.name().toLowerCase(Locale.ROOT));
        return identifiers;
    }
}
