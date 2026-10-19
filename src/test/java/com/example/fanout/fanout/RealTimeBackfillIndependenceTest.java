package com.example.fanout.fanout;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.fanout.backfill.BackfillConfig;
import com.example.fanout.backfill.BackfillFixture;
import com.example.fanout.backfill.BackfillOutcome;
import com.example.fanout.backfill.BackfillWorker;
import com.example.fanout.backfill.CapacityShares;
import com.example.fanout.domain.FanOutConfig;
import com.example.fanout.domain.FanOutOutcomeStatus;
import com.example.fanout.domain.FanOutRunOutcome;
import com.example.fanout.fakes.LatchDownstreamClient;
import com.example.fanout.fanout.PermitGuardedDownstream.MetricNames;
import com.example.fanout.fakes.RecordingFanOutMetrics;
import com.example.fanout.ports.CapacityBudget;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * User Story 4 (spec SC-004, FR-023, FR-024, T034/T035): real-time publishes
 * are structurally independent of backfill.
 *
 * <p>The backfill fixture saturates its share (all 18 B_backfill permits held
 * by latch-blocked loads) while a ~20,000-engagement publish runs on the
 * fan-out runner: the publish completes inside the freshness budget
 * (p99 &lt; 60 s, spec SC-010) with zero permit acquisitions, zero
 * attributable downstream calls, and throughput identical to a backfill-free
 * baseline — every observation on counters and the injected FakeClock, never
 * on wall-clock timing (FR-055/FR-072).
 */
class RealTimeBackfillIndependenceTest {

    private static final int BACKFILL_FIRMS = BackfillConfig.defaults().bBackfill();
    private static final int PUBLISH_FIRMS = 200;
    private static final int PUBLISH_PER_FIRM = 100;
    private static final int PUBLISH_TOTAL = PUBLISH_FIRMS * PUBLISH_PER_FIRM;
    private static final Duration FRESHNESS_BUDGET = Duration.ofSeconds(60);

    @Test
    void publishCompletesFreshWhileBackfillHoldsItsWholeShare() throws Exception {
        // ---- saturate the backfill share ----
        BackfillFixture backfill = new BackfillFixture();
        backfill.seed(3L, BACKFILL_FIRMS, 60);
        backfill.seedEffectiveForAllProducts(BackfillFixture.EFFECTIVE_VERSION);
        LatchDownstreamClient latch = new LatchDownstreamClient();
        latch.blockAfter(0); // every backfill load blocks, holding its permit
        backfill.loadClient = latch;
        backfill.runner = TaskRunner.fixedPool(BACKFILL_FIRMS);
        BackfillWorker worker = backfill.newWorker();

        AtomicReference<BackfillOutcome> backfillOutcome = new AtomicReference<>();
        AtomicReference<Throwable> backfillError = new AtomicReference<>();
        Thread backfillRun = new Thread(() -> {
            try {
                backfillOutcome.set(worker.run());
            } catch (Throwable failure) {
                backfillError.set(failure);
            }
        }, "saturated-backfill");
        backfillRun.start();
        latch.awaitInFlight(BACKFILL_FIRMS);
        assertThat(backfill.budget.available())
                .as("every backfill permit is held while the publish runs")
                .isZero();
        long blockedLoadsBeforePublish = latch.calls();

        // ---- the publish: ~20,000 engagements on the fan-out runner ----
        FanOutFixture publish = new FanOutFixture();
        publish.seedPublish(FanOutFixture.PUBLISH, FanOutFixture.TO_VERSION);
        publish.seedEligible(PUBLISH_FIRMS, PUBLISH_PER_FIRM);
        FanOutEngine engine = publish.newEngine();

        FanOutRunOutcome outcome = engine.runPublish(publish.publishTrigger(FanOutFixture.PUBLISH));

        assertThat(outcome.status()).isEqualTo(FanOutOutcomeStatus.COMPLETED);
        assertThat(outcome.chunkCount()).isEqualTo(PUBLISH_TOTAL / FanOutConfig.defaults().chunkSize());
        assertThat(outcome.rowsResolved()).isEqualTo(PUBLISH_TOTAL);

        // freshness: within the SLO on the injected clock
        var timer = publish.metrics.getTimers().stream()
                .filter(t -> t.name().equals("fanout.publish_to_pending"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("publish timer missing"));
        assertThat(timer.duration())
                .as("publish finishes inside the freshness budget (SC-004/SC-010)")
                .isLessThanOrEqualTo(FRESHNESS_BUDGET);

        // zero permit acquisitions and zero attributable downstream calls
        publish.assertHotPathInvariants();
        assertThat(publish.downstream.getCallCount())
                .as("the publish performs zero downstream loads (FR-023)")
                .isZero();
        assertThat(latch.calls())
                .as("the downstream log holds only the pre-existing blocked backfill loads")
                .isEqualTo(blockedLoadsBeforePublish);

        // ---- baseline without any backfill traffic ----
        FanOutFixture baseline = new FanOutFixture();
        baseline.seedPublish(FanOutFixture.PUBLISH, FanOutFixture.TO_VERSION);
        baseline.seedEligible(PUBLISH_FIRMS, PUBLISH_PER_FIRM);
        FanOutRunOutcome baseOutcome =
                baseline.newEngine().runPublish(baseline.publishTrigger(FanOutFixture.PUBLISH));

        var baseTimer = baseline.metrics.getTimers().stream()
                .filter(t -> t.name().equals("fanout.publish_to_pending"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("baseline timer missing"));
        assertThat(baseOutcome.status()).isEqualTo(FanOutOutcomeStatus.COMPLETED);
        assertThat(timer.duration())
                .as("backfill saturation leaves fan-out throughput unchanged (FR-024)")
                .isEqualTo(baseTimer.duration());
        assertThat(outcome.rowsResolved()).isEqualTo(baseOutcome.rowsResolved());
        assertThat(outcome.chunkCount()).isEqualTo(baseOutcome.chunkCount());
        assertThat(publish.index.createdTotal())
                .isEqualTo(baseline.index.createdTotal());

        // ---- drain the backfill fixture so no test thread leaks ----
        latch.releaseAll();
        backfillRun.join(Duration.ofSeconds(30).toMillis());
        assertThat(backfillRun.isAlive()).isFalse();
        assertThat(backfillError.get()).isNull();
        assertThat(backfillOutcome.get().status())
                .isEqualTo(BackfillOutcome.BackfillStatus.COMPLETED);
        assertThat(backfill.budget.available())
                .as("the whole share returns once the latch opens")
                .isEqualTo(BACKFILL_FIRMS);
        assertThat(backfill.metrics.countersWithin(
                RecordingFanOutMetrics.BACKFILL_COUNTER_ALLOWLIST)).isTrue();
    }

    @Test
    void structuralWiringKeepsCapacityOutOfFanOutAndSharesOutOfTheHotPath()
            throws Exception {
        // FanOutEngine holds no capacity or permit dependency (FR-023/T035)
        for (Field field : FanOutEngine.class.getDeclaredFields()) {
            assertThat(PermitGuardedDownstream.class.isAssignableFrom(field.getType()))
                    .as("FanOutEngine must not hold a PermitGuardedDownstream: " + field)
                    .isFalse();
            assertThat(CapacityBudget.class.isAssignableFrom(field.getType()))
                    .as("FanOutEngine must not hold a CapacityBudget: " + field)
                    .isFalse();
        }

        // BackfillWorker is constructed with the backfill share and its own
        // dedicated runner — never the fan-out pool or the repair share
        BackfillFixture fixture = new BackfillFixture();
        fixture.seed(3L, 4, 20);
        fixture.runner = TaskRunner.fixedPool(fixture.config.bBackfill());
        BackfillWorker worker = fixture.newWorker();

        CapacityShares shares = fixture.shares;
        assertThat(shares.backfill()).isEqualTo(BackfillConfig.defaults().bBackfill());
        assertThat(shares.repair()).isPositive();
        assertThat(fixture.budget.available())
                .as("the worker consumes the backfill share only")
                .isEqualTo(shares.backfill());

        Field guardField = findField(BackfillWorker.class, PermitGuardedDownstream.class);
        assertThat(guardField)
                .as("the worker reaches the downstream only through its permit guard")
                .isNotNull();
        PermitGuardedDownstream guard =
                (PermitGuardedDownstream) get(guardField, worker);
        Field guardBudget = findField(PermitGuardedDownstream.class, CapacityBudget.class);
        assertThat(guardBudget).isNotNull();
        CapacityBudget workerBudget = (CapacityBudget) get(guardBudget, guard);
        assertThat(workerBudget.available())
                .as("the guard is wired to the backfill share only")
                .isEqualTo(shares.backfill());

        TaskRunner workerRunner = (TaskRunner) get(findField(BackfillWorker.class,
                TaskRunner.class), worker);
        assertThat(workerRunner.bound())
                .as("the dedicated backfill pool is sized to the backfill share")
                .isEqualTo(shares.backfill());
        assertThat(workerRunner.bound())
                .as("the backfill pool is never the fan-out pool bound")
                .isNotEqualTo(FanOutConfig.defaults().poolBound());
        assertThat(MetricNames.backfill().park()).isEqualTo("backfill.permit.park");
        assertThat(MetricNames.repair().park()).isEqualTo("repair.backpressure");
    }

    private static Object get(Field field, Object target) {
        assertThat(field).isNotNull();
        field.setAccessible(true);
        try {
            return field.get(target);
        } catch (IllegalAccessException e) {
            throw new AssertionError("cannot read " + field, e);
        }
    }

    // ---- helpers ----

    private static Field findField(Class<?> type, Class<?> wanted) {
        for (Field field : type.getDeclaredFields()) {
            if (wanted.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                return field;
            }
        }
        return null;
    }
}
