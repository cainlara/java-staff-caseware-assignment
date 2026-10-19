package com.example.fanout.fanout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.fanout.domain.DeadLetterUnitKind;
import com.example.fanout.domain.EngagementRef;
import com.example.fanout.fakes.FakeCapacityBudget;
import com.example.fanout.fakes.LatchDownstreamClient;
import com.example.fanout.fakes.SeededJitter;
import com.example.fanout.ports.CapacityBudget;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * US4 off-path permit gate (spec SC-002, FR-030/FR-031/FR-033/FR-041,
 * contracts/permit-guard.md): C = 3 admits exactly 3 concurrent calls
 * (latch-client max-tracker proves it, not sampling), transient failures
 * release the permit before backoff, exhaustion dead-letters
 * DOWNSTREAM_CALL, permit exhaustion emits fanout.backpressure with zero
 * buffering, and quiescence returns every permit. All waiting is
 * latch-based or FakeClock-driven - no sleeps anywhere (FR-072).
 */
class PermitGuardedDownstreamTest {

    private static final EngagementRef REF =
            new EngagementRef("eng-000001", "firm-0001", "prod-1", "v1", "EU");

    private FanOutFixture fixture;
    private LatchDownstreamClient client;

    @BeforeEach
    void setUp() {
        fixture = new FanOutFixture();
        client = new LatchDownstreamClient();
    }

    @Test
    void threePermitsCapConcurrentDownstreamCallsExactly() throws Exception {
        var budget = new FakeCapacityBudget(3);
        var guard = guard(budget);
        var pool = Executors.newFixedThreadPool(10);
        List<Future<String>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 10; i++) {
                EngagementRef ref = new EngagementRef(
                        "eng-" + i, "firm-0001", "prod-1", "v1", "EU");
                results.add(pool.submit(() -> guard.load(ref)));
            }

            spinUntil(() -> client.inFlight() == 3);
            // exactly 3 admitted: the other 7 are paused, not buffered
            assertThat(client.calls()).isEqualTo(3);
            assertThat(client.max()).isEqualTo(3);
            assertThat(budget.available()).isZero();
            assertThat(guard.inFlight()).isEqualTo(3);
            assertThat(fixture.metrics.total("fanout.backpressure")).isPositive();
            assertThat(fixture.metrics.total("fanout.downstream.inflight.hwm"))
                    .isBetween(1L, 3L);

            client.releaseAll();
            for (Future<String> result : results) {
                assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo("v-test");
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(client.max()).isEqualTo(3);
        assertThat(budget.available()).isEqualTo(3);
        assertThat(guard.inFlight()).isZero();
        assertThat(client.calls()).isEqualTo(10);
        assertThat(fixture.metrics.total("fanout.downstream.inflight.hwm"))
                .isBetween(1L, 3L);
    }

    @Test
    void transientFailureReleasesPermitBeforeBackoffThenSucceeds() {
        var budget = new FakeCapacityBudget(3);
        client.releaseAll(); // open the gate: admission stays free, no blocking
        client.failNext(2);
        List<Duration> delays = new ArrayList<>();
        AtomicInteger backpressureDuringWait = new AtomicInteger();
        BackoffWaiter waiter = delay -> {
            // permit must be back in the budget before any backoff wait (FR-033)
            assertThat(budget.available()).isEqualTo(3);
            assertThat(client.inFlight()).isZero();
            backpressureDuringWait.incrementAndGet();
            delays.add(delay);
            fixture.clock.advance(delay);
        };
        var guard = new PermitGuardedDownstream(client, budget, waiter, fixture.clock,
                fixture.metrics, fixture.config, new SeededJitter(42L));
        var executor = new RetryExecutor(fixture.config, waiter, fixture.clock,
                new SeededJitter(42L), fixture.metrics, fixture.deadLetters);

        String result = executor.callWithRetry("pub-1", "prod-1", -1,
                DeadLetterUnitKind.DOWNSTREAM_CALL, () -> guard.load(REF));

        assertThat(result).isEqualTo("v-test");
        assertThat(client.calls()).isEqualTo(3);
        assertThat(backpressureDuringWait.get()).isEqualTo(2);
        assertThat(delays).hasSize(2);
        assertThat(budget.available()).isEqualTo(3);
        assertThat(guard.inFlight()).isZero();
        assertThat(fixture.metrics.total("fanout.retry")).isEqualTo(2);
        assertThat(fixture.metrics.total("fanout.backpressure")).isZero();
        assertThat(fixture.metrics.total("fanout.downstream.inflight.hwm")).isEqualTo(1);
        assertThat(fixture.deadLetters.count()).isZero();
    }

    @Test
    void exhaustionStoresDownstreamCallDeadLetterAndAlerts() {
        var budget = new FakeCapacityBudget(3);
        client.failNext(Integer.MAX_VALUE);
        var guard = guard(budget);
        var executor = new RetryExecutor(fixture.config, delay -> fixture.clock.advance(delay),
                fixture.clock, new SeededJitter(42L), fixture.metrics, fixture.deadLetters);

        assertThatThrownBy(() -> executor.callWithRetry("pub-2", "prod-1", -1,
                DeadLetterUnitKind.DOWNSTREAM_CALL, () -> guard.load(REF)))
                .isInstanceOf(FanOutExhaustedException.class)
                .satisfies(thrown -> {
                    var record = ((FanOutExhaustedException) thrown).record();
                    assertThat(record.unitKind()).isEqualTo(DeadLetterUnitKind.DOWNSTREAM_CALL);
                    assertThat(record.eventId()).isEqualTo("pub-2");
                    assertThat(record.productId()).isEqualTo("prod-1");
                    assertThat(record.chunkIndex()).isEqualTo(-1);
                    assertThat(record.attemptCount()).isEqualTo(5);
                });

        assertThat(fixture.deadLetters.byEvent("pub-2")).hasSize(1);
        assertThat(fixture.metrics.total("fanout.dlq")).isEqualTo(1);
        assertThat(budget.available()).isEqualTo(3); // every permit returned
        assertThat(guard.inFlight()).isZero();
        assertThat(client.inFlight()).isZero();
    }

    @Test
    void permitExhaustionBackpressuresWithoutBuffering() throws Exception {
        var budget = new FakeCapacityBudget(1);
        var guard = guard(budget);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = pool.submit(() -> guard.load(REF));
            spinUntil(() -> client.inFlight() == 1);

            EngagementRef secondRef = new EngagementRef(
                    "eng-000002", "firm-0001", "prod-1", "v1", "EU");
            Future<String> second = pool.submit(() -> guard.load(secondRef));
            spinUntil(() -> fixture.metrics.total("fanout.backpressure") >= 1);

            // the second caller is paused at admission: not in the client, not buffered
            assertThat(client.calls()).isEqualTo(1);
            assertThat(budget.available()).isZero();
            assertThat(client.inFlight()).isEqualTo(1);

            client.releaseAll();
            assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo("v-test");
            assertThat(second.get(30, TimeUnit.SECONDS)).isEqualTo("v-test");
        } finally {
            pool.shutdownNow();
        }

        assertThat(client.max()).isEqualTo(1);
        assertThat(budget.available()).isEqualTo(1);
        assertThat(guard.inFlight()).isZero();
    }

    @Test
    void starvedBudgetPausesAdmissionWithoutProgress() {
        CapacityBudget none = new CapacityBudget() {
            @Override
            public boolean tryAcquire() {
                return false;
            }

            @Override
            public void release() {
                throw new IllegalStateException("release without hold");
            }

            @Override
            public int available() {
                return 0;
            }
        };
        AtomicInteger waits = new AtomicInteger();
        // time-bounded waiter: proves pause (waiting happened), not progress
        BackoffWaiter boundedWaiter = delay -> {
            if (waits.incrementAndGet() > 5) {
                throw new IllegalStateException("admission paused, no progress");
            }
            fixture.clock.advance(delay);
        };
        var guard = new PermitGuardedDownstream(client, none, boundedWaiter, fixture.clock,
                fixture.metrics, fixture.config, new SeededJitter(42L));

        assertThatThrownBy(() -> guard.load(REF))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("admission paused, no progress");

        assertThat(client.calls()).isZero();
        assertThat(waits.get()).isEqualTo(6);
        assertThat(fixture.metrics.total("fanout.backpressure")).isEqualTo(6);
        assertThat(guard.inFlight()).isZero();
        assertThat(fixture.deadLetters.count()).isZero();
    }

    // ---- helpers ----

    private PermitGuardedDownstream guard(CapacityBudget budget) {
        return new PermitGuardedDownstream(client, budget,
                delay -> fixture.clock.advance(delay), fixture.clock,
                fixture.metrics, fixture.config, new SeededJitter(42L));
    }

    private static void spinUntil(BooleanSupplier condition) {
        long spins = 0;
        while (!condition.getAsBoolean()) {
            if (++spins > 2_000_000_000L) {
                throw new AssertionError("condition never became true");
            }
            Thread.onSpinWait();
        }
    }
}
