package com.example.fanout.fakes;

import com.example.fanout.domain.EngagementRef;
import com.example.fanout.fanout.TransientFanOutException;
import com.example.fanout.ports.DownstreamLoadClient;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Latch-blocked {@link DownstreamLoadClient} for concurrency contract tests
 * (contracts/permit-guard.md test harness): every call blocks on a shared
 * gate until {@link #releaseAll()}; the fake maintains an exact in-flight
 * counter plus an atomic max tracker - ceiling invariants are asserted on
 * these counters, never on timing (spec FR-072, SC-002). {@code failNext(n)}
 * makes the next n calls fail transiently (while counted as in flight, so
 * permit release paths are exercised).
 */
public final class LatchDownstreamClient implements DownstreamLoadClient {

    private final CountDownLatch gate = new CountDownLatch(1);
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger max = new AtomicInteger();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger failNext = new AtomicInteger();
    private final AtomicInteger blockAfter = new AtomicInteger(0);
    private final Object arrivals = new Object();

    @Override
    public String loadEngagement(EngagementRef ref) {
        Objects.requireNonNull(ref, "ref must not be null");
        return loadEngagement(ref.engagementId(), ref.firmId(), ref.productId());
    }

    @Override
    public String loadEngagement(String engagementId, String firmId, String productId) {
        Objects.requireNonNull(engagementId, "engagementId must not be null");
        Objects.requireNonNull(firmId, "firmId must not be null");
        Objects.requireNonNull(productId, "productId must not be null");
        calls.incrementAndGet();
        int current = inFlight.incrementAndGet();
        max.accumulateAndGet(current, Math::max);
        synchronized (arrivals) {
            arrivals.notifyAll();
        }
        try {
            if (failNext.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                throw new TransientFanOutException(
                        "injected downstream failure for " + engagementId);
            }
            if (calls.get() > blockAfter.get()) {
                try {
                    gate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted in latch client", e);
                }
            }
            return "v-test";
        } finally {
            inFlight.decrementAndGet();
            synchronized (arrivals) {
                arrivals.notifyAll();
            }
        }
    }

    /**
     * Block on the gate every call after the first {@code n} (signal-based,
     * no sleeps): the first {@code n} calls return immediately, so tests can
     * complete real batches before saturating the share. Default is
     * {@code blockAfter(0)} — every call blocks until {@link #releaseAll()}.
     */
    public void blockAfter(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("n must be >= 0");
        }
        blockAfter.set(n);
    }

    /** Wait (signal-based) until at least {@code n} calls are in flight. */
    public void awaitInFlight(int n) throws InterruptedException {
        synchronized (arrivals) {
            while (inFlight.get() < n) {
                arrivals.wait();
            }
        }
    }

    /** Open the gate permanently: every current and future call proceeds. */
    public void releaseAll() {
        gate.countDown();
    }

    /** Next n calls fail transiently (before blocking). */
    public void failNext(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("n must be >= 0");
        }
        failNext.set(n);
    }

    /** Exact concurrent-call counter at this instant. */
    public int inFlight() {
        return inFlight.get();
    }

    /** Highest concurrent-call count ever observed. */
    public int max() {
        return max.get();
    }

    /** Total calls observed (enters, including failing ones). */
    public int calls() {
        return calls.get();
    }
}
