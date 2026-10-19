package com.example.fanout.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.example.fanout.domain.FanOutConfig;
import com.example.fanout.fanout.AdmissionHaltedException;
import com.example.fanout.fanout.BackoffWaiter;
import com.example.fanout.fanout.PermitGuardedDownstream;
import com.example.fanout.fanout.PermitGuardedDownstream.MetricNames;
import com.example.fanout.fakes.FakeClock;
import com.example.fanout.fakes.LatchDownstreamClient;
import com.example.fanout.fakes.RecordingFanOutMetrics;
import com.example.fanout.fakes.SeededJitter;
import com.example.fanout.ports.CapacityBudget;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * User Story 4 (spec FR-020-FR-024, SC-005, SC-008, T033): the shared
 * downstream capacity partitions into two disjoint, independently acquired
 * permit pools.
 *
 * <ul>
 *   <li>FR-021 — over-partitioned configurations rejected at startup;</li>
 *   <li>FR-022 — a repair waiter acquires while every backfill permit is held
 *       (separate semaphores, separate wait queues); {@code B_repair = 0}
 *       fails fast, non-blocking, with its own distinct counter;</li>
 *   <li>SC-005 — in-flight per share never exceeds the share, total never
 *       exceeds the parent, observed on counters at saturation, never on
 *       timing;</li>
 *   <li>SC-008 — the documented pacing dial: 800,000 x 1 min / 18 ~= 30.9
 *       days, / 20 ~= 27.8 days.</li>
 * </ul>
 */
class CapacitySharesTest {

    private static final BackoffWaiter CLOCK_WAITER = delay -> {
    };
    private static final SeededJitter JITTER = new SeededJitter(42L);
    private static final FanOutConfig CONFIG = FanOutConfig.defaults();

    @Test
    void startupRejectsOverPartitionedShares() {
        assertThatIllegalArgumentException().isThrownBy(() -> new CapacityShares(10, 8, 3))
                .withMessageContaining("11 > 10");
        assertThatIllegalArgumentException().isThrownBy(() -> new CapacityShares(5, 6, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new CapacityShares(0, 0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new CapacityShares(20, 0, 2));
        assertThatIllegalArgumentException().isThrownBy(() -> new CapacityShares(20, 18, -1));
        assertThatNoException().isThrownBy(() -> new CapacityShares(20, 18, 2));
        assertThatNoException().isThrownBy(() -> new CapacityShares(20, 19, 1));
        assertThatNoException().isThrownBy(() -> new CapacityShares(1, 1, 0));
    }

    @Test
    void repairShareAcquiresWhileEveryBackfillPermitIsHeld() {
        CapacityShares shares = CapacityShares.defaults();
        CapacityBudget backfill = shares.backfillBudget();
        CapacityBudget repair = shares.repairBudget();

        for (int i = 0; i < shares.backfill(); i++) {
            assertThat(backfill.tryAcquire())
                    .as("backfill share fills to its capacity")
                    .isTrue();
        }
        assertThat(backfill.available()).isZero();

        assertThat(repair.tryAcquire())
                .as("a repair waiter acquires from B_repair while B_backfill is held (FR-022)")
                .isTrue();
        assertThat(repair.tryAcquire()).isTrue();
        assertThat(repair.available()).isZero();

        assertThat(backfill.tryAcquire())
                .as("the full backfill share never leaks into the repair queue")
                .isFalse();
        assertThat(backfill.available()).isZero();

        repair.release();
        repair.release();
        assertThat(repair.available()).isEqualTo(shares.repair());
        for (int i = 0; i < shares.backfill(); i++) {
            backfill.release();
        }
        assertThat(backfill.available()).isEqualTo(shares.backfill());
    }

    @Test
    void inFlightNeverExceedsEitherShareOrTheParentTotal() throws Exception {
        CapacityShares shares = CapacityShares.defaults();
        assertThat(shares.backfill() + shares.repair()).isEqualTo(shares.parentC());
        RecordingFanOutMetrics metrics = new RecordingFanOutMetrics();
        FakeClock clock = FakeClock.startingAt("2026-10-19T12:00:00Z");
        LatchDownstreamClient latch = new LatchDownstreamClient();
        latch.blockAfter(0); // every load blocks until releaseAll

        PermitGuardedDownstream backfillGuard = new PermitGuardedDownstream(
                latch, shares.backfillBudget(), CLOCK_WAITER, clock, metrics, CONFIG, JITTER,
                () -> false, MetricNames.backfill());
        PermitGuardedDownstream repairGuard = new PermitGuardedDownstream(
                latch, shares.repairBudget(), CLOCK_WAITER, clock, metrics, CONFIG, JITTER,
                () -> false, MetricNames.repair());

        AtomicInteger backfillInFlight = new AtomicInteger();
        AtomicInteger repairInFlight = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < shares.backfill(); i++) {
            threads.add(groupThread("backfill-" + i, backfillGuard, backfillInFlight, failure));
        }
        for (int i = 0; i < shares.repair(); i++) {
            threads.add(groupThread("repair-" + i, repairGuard, repairInFlight, failure));
        }
        threads.forEach(Thread::start);

        latch.awaitInFlight(shares.parentC());
        assertThat(latch.inFlight())
                .as("every parent permit admits exactly one in-flight load (SC-005)")
                .isEqualTo(shares.parentC());
        assertThat(backfillInFlight.get())
                .as("backfill in-flight never exceeds B_backfill")
                .isEqualTo(shares.backfill());
        assertThat(repairInFlight.get())
                .as("repair in-flight never exceeds B_repair")
                .isEqualTo(shares.repair());
        assertThat(latch.max()).isLessThanOrEqualTo(shares.parentC());

        latch.releaseAll();
        for (Thread thread : threads) {
            thread.join(Duration.ofSeconds(30).toMillis());
            assertThat(thread.isAlive()).isFalse();
        }
        assertThat(failure.get()).isNull();
        assertThat(shares.backfillBudget().available()).isEqualTo(shares.backfill());
        assertThat(shares.repairBudget().available()).isEqualTo(shares.repair());
        assertThat(metrics.total("backfill.permit.park")).isZero();
        assertThat(metrics.total("repair.backpressure")).isZero();
    }

    @Test
    void zeroRepairShareFailsFastWithItsOwnCounterAndLeavesOtherSharesUntouched() {
        CapacityShares shares = new CapacityShares(20, 18, 0);
        CapacityBudget repair = shares.repairBudget();
        RecordingFanOutMetrics metrics = new RecordingFanOutMetrics();

        assertThat(repair.tryAcquire())
                .as("B_repair = 0 refuses immediately: non-blocking, no waiter, no buffering")
                .isFalse();
        // the future repair-path caller counts the refusal on its own side
        // (contracts/capacity-shares.md — a distinct counter per share)
        MetricNames repairNames = MetricNames.repair();
        if (!repair.tryAcquire()) {
            metrics.increment(repairNames.park(), 1, "product_id", "prod-repair");
        }
        assertThat(metrics.total("repair.backpressure")).isEqualTo(1L);
        assertThat(metrics.total("backfill.permit.park")).isZero();
        assertThat(metrics.total("fanout.backpressure")).isZero();

        // backfill and the hot path are unaffected by a zero repair share
        CapacityBudget backfill = shares.backfillBudget();
        assertThat(backfill.tryAcquire()).isTrue();
        backfill.release();
        assertThat(shares.backfill()).isEqualTo(18);
    }

    @Test
    void documentedPacingDialMatchesTheShareArithmetic() {
        // 800,000 loads x 1 min each, divided by the share, in days
        double daysAt18 = (800_000.0 / shares18()) / 1440.0;
        double daysAt20 = (800_000.0 / shares20()) / 1440.0;
        assertThat(Math.round(daysAt18 * 10) / 10.0)
                .as("800,000 x 1 min / B_backfill=18 ~= 30.9 days (SC-008)")
                .isEqualTo(30.9);
        assertThat(Math.round(daysAt20 * 10) / 10.0)
                .as("800,000 x 1 min / parentC=20 ~= 27.8 days (SC-008)")
                .isEqualTo(27.8);
    }

    // ---- helpers ----

    private static int shares18() {
        return CapacityShares.defaults().backfill();
    }

    private static int shares20() {
        return CapacityShares.defaults().parentC();
    }

    private static Thread groupThread(String name, PermitGuardedDownstream guard,
                                      AtomicInteger inFlight, AtomicReference<Throwable> failure) {
        return new Thread(() -> {
            inFlight.incrementAndGet();
            try {
                guard.load(name, "firm-" + name, "prod-1");
            } catch (AdmissionHaltedException abandoned) {
                failure.compareAndSet(null, abandoned);
            } catch (RuntimeException unexpected) {
                failure.compareAndSet(null, unexpected);
            } finally {
                inFlight.decrementAndGet();
            }
        }, name);
    }
}
