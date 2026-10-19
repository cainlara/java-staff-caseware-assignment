package com.example.fanout.fanout;

import com.example.fanout.domain.EngagementRef;
import com.example.fanout.domain.FanOutConfig;
import com.example.fanout.ports.CapacityBudget;
import com.example.fanout.ports.DownstreamLoadClient;
import com.example.fanout.ports.FanOutMetrics;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The ONLY sanctioned way to call {@link DownstreamLoadClient} (spec R5,
 * research.md D9, contracts/permit-guard.md): every call is gated on
 * {@link CapacityBudget} with paused admission - when exhausted,
 * {@code fanout.backpressure} increments and the caller waits via
 * {@link BackoffWaiter}; there is never a queue of admitted-but-waiting units
 * (spec FR-031). The permit is released in {@code finally} BEFORE any retry
 * backoff ({@link RetryExecutor} owns backoff outside the permit, spec FR-033),
 * so C is reserved only for actual in-flight calls. New in-flight peaks emit
 * the event-style {@code fanout.downstream.inflight.hwm} counter.
 *
 * <p>Off the hot path only: {@code FanOutEngine} does not reference this class
 * (spec FR-032); publish tests assert the client call log stays empty.
 */
public final class PermitGuardedDownstream {

    private final DownstreamLoadClient client;
    private final CapacityBudget budget;
    private final BackoffWaiter backoffWaiter;
    private final FanOutMetrics metrics;
    private final FanOutConfig config;
    private final JitterSource jitterSource;

    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicLong hwm = new AtomicLong();

    public PermitGuardedDownstream(DownstreamLoadClient client, CapacityBudget budget,
                                   BackoffWaiter backoffWaiter, Clock clock,
                                   FanOutMetrics metrics, FanOutConfig config,
                                   JitterSource jitterSource) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.budget = Objects.requireNonNull(budget, "budget must not be null");
        this.backoffWaiter = Objects.requireNonNull(backoffWaiter, "backoffWaiter must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.jitterSource = Objects.requireNonNull(jitterSource, "jitterSource must not be null");
    }

    /**
     * Permit-gated downstream load (contract sequence): admit (pausing on
     * exhaustion), invoke inside try/finally with exactly-once release,
     * rethrow client failures as {@link TransientFanOutException}.
     */
    public String load(EngagementRef ref) {
        Objects.requireNonNull(ref, "ref must not be null");
        while (!budget.tryAcquire()) {
            // backpressure, not failure: paused admission, zero buffering (FR-031)
            metrics.increment("fanout.backpressure", 1, "product_id", ref.productId());
            backoffWaiter.await(admissionPause());
        }
        int current = inFlight.incrementAndGet();
        if (current > hwm.getAndUpdate(previous -> Math.max(previous, current))) {
            metrics.increment("fanout.downstream.inflight.hwm", 1, "product_id", ref.productId());
        }
        try {
            try {
                return client.loadEngagement(ref);
            } catch (TransientFanOutException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                throw new TransientFanOutException(
                        "downstream load failed for " + ref.engagementId(), failure);
            }
        } finally {
            inFlight.decrementAndGet();
            budget.release();
        }
    }

    /** Current permit-held count (tests/metrics). */
    public int inflight() {
        return inFlight.get();
    }

    /** Jittered admission pause (deterministic under the injected seams). */
    private java.time.Duration admissionPause() {
        double factor = config.jitterFactorMin()
                + jitterSource.next() * (config.jitterFactorMax() - config.jitterFactorMin());
        return java.time.Duration.ofNanos(
                Math.round(config.retryBaseDelay().toNanos() * factor));
    }
}
