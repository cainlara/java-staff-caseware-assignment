package com.example.fanout.ports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.example.fanout.fakes.FakeCapacityBudget;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Pins CapacityBudget contract (contracts/capacity-budget.md): never admits more
 * than C in flight; release required after acquire; double-release throws;
 * C configurable.
 */
class CapacityBudgetContractTest {

    @Test
    void exhaustionAtCapacityThenReleaseAdmitsAgain() {
        var budget = new FakeCapacityBudget(3);
        assertThat(budget.tryAcquire()).isTrue();
        assertThat(budget.tryAcquire()).isTrue();
        assertThat(budget.tryAcquire()).isTrue();
        assertThat(budget.tryAcquire()).isFalse();
        assertThat(budget.available()).isZero();

        budget.release();
        assertThat(budget.available()).isEqualTo(1);
        assertThat(budget.tryAcquire()).isTrue();
    }

    @Test
    void releaseWithoutHeldPermitThrows() {
        var budget = new FakeCapacityBudget(2);
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(budget::release);
    }

    @Test
    void defaultCapacityIsTwenty() {
        var budget = new FakeCapacityBudget();
        assertThat(budget.getCapacity()).isEqualTo(20);
        for (int i = 0; i < 20; i++) {
            assertThat(budget.tryAcquire()).isTrue();
        }
        assertThat(budget.tryAcquire()).isFalse();
    }

    @Test
    void nonDefaultCapacityRespected() {
        var one = new FakeCapacityBudget(1);
        assertThat(one.tryAcquire()).isTrue();
        assertThat(one.tryAcquire()).isFalse();

        var fifty = new FakeCapacityBudget(50);
        int acquired = 0;
        while (fifty.tryAcquire()) {
            acquired++;
        }
        assertThat(acquired).isEqualTo(50);
    }

    @Test
    void concurrentHammerNeverExceedsCapacity() throws Exception {
        int capacity = 10;
        int threads = 40;
        int iterations = 200;
        var budget = new FakeCapacityBudget(capacity);
        var inFlight = new AtomicInteger();
        var maxInFlight = new AtomicInteger();
        var start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < iterations; i++) {
                            if (budget.tryAcquire()) {
                                int now = inFlight.incrementAndGet();
                                maxInFlight.accumulateAndGet(now, Math::max);
                                Thread.onSpinWait();
                                inFlight.decrementAndGet();
                                budget.release();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(maxInFlight.get()).isLessThanOrEqualTo(capacity);
        assertThat(budget.available()).isEqualTo(capacity);
    }
}
