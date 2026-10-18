package com.example.fanout.fakes;

import com.example.fanout.domain.EngagementRef;
import com.example.fanout.ports.DownstreamLoadClient;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fake {@link DownstreamLoadClient} with configurable simulated latency and
 * failure injection. Default simulated latency is 60 s; contract tests assert
 * that default on configuration and use tiny values when actually executing.
 */
public final class FakeDownstreamLoadClient implements DownstreamLoadClient {

    public static final Duration DEFAULT_SIMULATED_LATENCY = Duration.ofSeconds(60);

    private volatile Duration latency;
    private final AtomicInteger failNext = new AtomicInteger();
    private final AtomicInteger callCount = new AtomicInteger();

    public FakeDownstreamLoadClient() {
        this(DEFAULT_SIMULATED_LATENCY);
    }

    public FakeDownstreamLoadClient(Duration latency) {
        this.latency = Objects.requireNonNull(latency, "latency must not be null");
        if (latency.isNegative()) {
            throw new IllegalArgumentException("latency must not be negative");
        }
    }

    @Override
    public String loadEngagement(EngagementRef ref) {
        Objects.requireNonNull(ref, "ref must not be null");
        callCount.incrementAndGet();
        sleep(latency);
        if (failNext.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
            throw new IllegalStateException("injected downstream failure");
        }
        return "v-" + ref.currentVersion();
    }

    public Duration getSimulatedLatency() {
        return latency;
    }

    public void setLatency(Duration latency) {
        this.latency = Objects.requireNonNull(latency, "latency must not be null");
        if (latency.isNegative()) {
            throw new IllegalArgumentException("latency must not be negative");
        }
    }

    /** Make the next {@code n} calls throw an injected failure. */
    public void failNextCalls(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("n must be >= 0");
        }
        failNext.set(n);
    }

    public void reset() {
        failNext.set(0);
        callCount.set(0);
    }

    public int getCallCount() {
        return callCount.get();
    }

    private static void sleep(Duration d) {
        if (d.isZero()) {
            return;
        }
        try {
            Thread.sleep(d.toMillis(), (int) (d.toNanos() % 1_000_000L));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during simulated latency", e);
        }
    }
}
