package com.example.fanout.fanout;

import com.example.fanout.domain.EngagementRef;
import com.example.fanout.domain.FanOutConfig;
import com.example.fanout.ports.CapacityBudget;
import com.example.fanout.ports.DownstreamLoadClient;
import com.example.fanout.ports.FanOutMetrics;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * The ONLY sanctioned way to call {@link DownstreamLoadClient} (spec R5,
 * research.md D9, contracts/permit-guard.md): every call is gated on
 * {@link CapacityBudget} with paused admission - when exhausted,
 * the park counter increments and the caller waits via
 * {@link BackoffWaiter}; there is never a queue of admitted-but-waiting units
 * (spec FR-031). The permit is released in {@code finally} BEFORE any retry
 * backoff ({@link RetryExecutor} owns backoff outside the permit, spec FR-033),
 * so C is reserved only for actual in-flight calls. New in-flight peaks emit
 * the {@code *.downstream.inflight.hwm} counter.
 *
 * <p>Off the hot path only: {@code FanOutEngine} does not reference this class
 * (spec FR-032); publish tests assert the client call log stays empty.
 *
 * <p><b>Halt probe</b> (research.md D9): an optional {@link BooleanSupplier}
 * consulted once before the first acquire attempt and on every failed-acquire
 * pause. When it reports {@code true} the wait abandons with
 * {@link AdmissionHaltedException} — thrown while holding no permit, never
 * wrapped, never dead-lettered. The delivered constructor delegates with
 * {@code () -> false}, so delivered behavior is unchanged.
 */
public final class PermitGuardedDownstream {

    /** Per-side metric names: backfill and fan-out emit distinct counters. */
    public record MetricNames(String park, String inflightHwm) {

        public MetricNames {
            Objects.requireNonNull(park, "park must not be null");
            Objects.requireNonNull(inflightHwm, "inflightHwm must not be null");
        }

        /** Delivered fan-out names. */
        public static MetricNames fanout() {
            return new MetricNames("fanout.backpressure", "fanout.downstream.inflight.hwm");
        }

        /** Backfill-share names (spec FR-053). */
        public static MetricNames backfill() {
            return new MetricNames("backfill.permit.park", "backfill.downstream.inflight.hwm");
        }

        /** Real-time repair-share names (spec FR-022). */
        public static MetricNames repair() {
            return new MetricNames("repair.backpressure", "repair.downstream.inflight.hwm");
        }
    }

    private static final BooleanSupplier NO_HALT = () -> false;

    private final DownstreamLoadClient client;
    private final CapacityBudget budget;
    private final BackoffWaiter backoffWaiter;
    private final FanOutMetrics metrics;
    private final FanOutConfig config;
    private final JitterSource jitterSource;
    private final BooleanSupplier haltProbe;
    private final MetricNames metricNames;

    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicLong hwm = new AtomicLong();

    public PermitGuardedDownstream(DownstreamLoadClient client, CapacityBudget budget,
                                   BackoffWaiter backoffWaiter, Clock clock,
                                   FanOutMetrics metrics, FanOutConfig config,
                                   JitterSource jitterSource) {
        this(client, budget, backoffWaiter, clock, metrics, config, jitterSource,
                NO_HALT, MetricNames.fanout());
    }

    /**
     * Halt-aware entry point for the backfill share (spec FR-031): the probe is
     * read live at every acquisition attempt.
     */
    public PermitGuardedDownstream(DownstreamLoadClient client, CapacityBudget budget,
                                   BackoffWaiter backoffWaiter, Clock clock,
                                   FanOutMetrics metrics, FanOutConfig config,
                                   JitterSource jitterSource, BooleanSupplier haltProbe,
                                   MetricNames metricNames) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.budget = Objects.requireNonNull(budget, "budget must not be null");
        this.backoffWaiter = Objects.requireNonNull(backoffWaiter, "backoffWaiter must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.jitterSource = Objects.requireNonNull(jitterSource, "jitterSource must not be null");
        this.haltProbe = Objects.requireNonNull(haltProbe, "haltProbe must not be null");
        this.metricNames = Objects.requireNonNull(metricNames, "metricNames must not be null");
    }

    /**
     * Permit-gated downstream load (contract sequence): admit (pausing on
     * exhaustion, abandoning on halt), invoke inside try/finally with
     * exactly-once release, rethrow client failures as
     * {@link TransientFanOutException}.
     *
     * @throws AdmissionHaltedException when the halt probe fires while waiting
     *         for a permit — no permit is held at that point
     */
    public String load(EngagementRef ref) {
        Objects.requireNonNull(ref, "ref must not be null");
        return load(ref.engagementId(), ref.firmId(), ref.productId());
    }

    /**
     * Permit-gated capture by bare identity — the backfill capture path
     * (research.md D1): identical sequence to {@link #load(EngagementRef)},
     * no version or region needed before the capture.
     *
     * @throws AdmissionHaltedException when the halt probe fires while waiting
     *         for a permit — no permit is held at that point
     */
    public String load(String engagementId, String firmId, String productId) {
        Objects.requireNonNull(engagementId, "engagementId must not be null");
        Objects.requireNonNull(firmId, "firmId must not be null");
        Objects.requireNonNull(productId, "productId must not be null");
        if (haltProbe.getAsBoolean()) {
            throw halted(engagementId, productId);
        }
        while (!budget.tryAcquire()) {
            // backpressure, not failure: paused admission, zero buffering (FR-031)
            metrics.increment(metricNames.park(), 1, "product_id", productId);
            if (haltProbe.getAsBoolean()) {
                throw halted(engagementId, productId);
            }
            backoffWaiter.await(admissionPause());
        }
        int current = inFlight.incrementAndGet();
        if (current > hwm.getAndUpdate(previous -> Math.max(previous, current))) {
            metrics.increment(metricNames.inflightHwm(), 1, "product_id", productId);
        }
        try {
            try {
                return client.loadEngagement(engagementId, firmId, productId);
            } catch (TransientFanOutException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                throw new TransientFanOutException(
                        "downstream load failed for " + engagementId, failure);
            }
        } finally {
            inFlight.decrementAndGet();
            budget.release();
            metrics.increment("fanout.downstream.completed", 1, "product_id", productId);
        }
    }

    /**
     * Current permit-held count (0..C at all times): the binding source for
     * the {@code fanout.downstream.inflight} gauge and the saturation alert's
     * pinned-at-C probe (spec 005). Read-only observation — no behavior
     * change.
     */
    public int inFlight() {
        return inFlight.get();
    }

    private static AdmissionHaltedException halted(String engagementId, String productId) {
        return new AdmissionHaltedException(
                "admission halted for engagement " + engagementId + " of " + productId);
    }

    /** Jittered admission pause (deterministic under the injected seams). */
    private Duration admissionPause() {
        double factor = config.jitterFactorMin()
                + jitterSource.next() * (config.jitterFactorMax() - config.jitterFactorMin());
        return Duration.ofNanos(Math.round(config.retryBaseDelay().toNanos() * factor));
    }
}
