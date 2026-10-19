package com.example.fanout.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.example.fanout.domain.EnumeratedEngagement;
import com.example.fanout.domain.EngagementRef;
import com.example.fanout.fanout.AdmissionGate;
import com.example.fanout.fanout.RetryPolicy;
import com.example.fanout.fanout.TaskRunner;
import com.example.fanout.fakes.RecordingFanOutMetrics;
import com.example.fanout.ports.DownstreamLoadClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * User Story 5 (spec FR-010-FR-012, SC-006, T036): fair enumeration — one
 * giant firm cannot monopolize backfill.
 *
 * <p>Distribution: a 40,000-engagement firm sorting FIRST in firm-id order
 * (the worst case for any non-rotating scan) plus 500 median-40 firms, on a
 * deliberately small backfill share. Every downstream observation asserts the
 * per-firm in-flight cap; the run is terminated by the halt switch once the
 * observable goal is reached: every median firm checkpoint COMPLETE while the
 * giant firm's checkpoint is still incomplete. No sleeps anywhere (FR-055).
 */
class BackfillFairnessTest {

    private static final int GIANT_SIZE = 40_000;
    private static final int MEDIAN_FIRMS = 500;
    private static final int MEDIAN_SIZE = 40;
    private static final String GIANT_FIRM = "f0-giant";
    private static final RetryPolicy RETRY =
            new RetryPolicy(5, Duration.ofMillis(100), Duration.ofSeconds(30), 0.5, 1.5);
    private static final BackfillConfig SMALL_SHARE = new BackfillConfig(
            100, 1, 20, 4, 2, Duration.ofSeconds(1), Duration.ofMinutes(2), RETRY);

    @Test
    void everyMedianFirmCompletesWhileTheGiantFirmStaysBackloggedWithinTheCap()
            throws Exception {
        BackfillFixture fixture = new BackfillFixture(SMALL_SHARE);
        fixture.seed(fairnessDataset());

        CapObservingClient observing = new CapObservingClient(
                fixture.downstream, SMALL_SHARE.firmInFlightCap());
        fixture.loadClient = observing;
        fixture.runner = TaskRunner.fixedPool(SMALL_SHARE.bBackfill());

        BackfillWorker worker = fixture.newWorker();
        AtomicReference<BackfillOutcome> outcome = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread run = new Thread(() -> {
            try {
                outcome.set(worker.run());
            } catch (Throwable failure) {
                error.set(failure);
            }
        }, "fairness-backfill");
        run.start();

        // observe until the fairness goal holds, then terminate via halt
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        boolean goal = false;
        while (!goal) {
            if (!run.isAlive()) {
                fail("run ended before the fairness goal; error=" + error.get());
                return;
            }
            goal = mediansComplete(fixture) == MEDIAN_FIRMS
                    && !isComplete(fixture, GIANT_FIRM);
            if (!goal) {
                if (System.nanoTime() > deadline) {
                    fail("fairness goal not reached within the harness bound");
                    return;
                }
                Thread.onSpinWait();
            }
        }
        fixture.flags.setHalt(true);
        run.join(Duration.ofSeconds(30).toMillis());
        assertThat(run.isAlive()).isFalse();
        assertThat(error.get()).isNull();

        BackfillOutcome result = outcome.get();
        assertThat(result.status()).isEqualTo(BackfillOutcome.BackfillStatus.HALTED);

        // SC-006: 100% of median firms complete, the giant firm does not
        assertThat(mediansComplete(fixture)).isEqualTo(MEDIAN_FIRMS);
        assertThat(isComplete(fixture, GIANT_FIRM))
                .as("the giant firm is still early in its backlog")
                .isFalse();
        assertThat(fixture.cursors.read(GIANT_FIRM))
                .as("the giant firm checkpointed its completed batches")
                .isPresent();

        // SC-005/FR-011: per-firm in-flight never exceeded its cap, and the
        // share ceiling held at every observation point
        assertThat(observing.violations())
                .as("no firm ever exceeded its in-flight cap")
                .isEmpty();
        assertThat(observing.maxPerFirm())
                .isLessThanOrEqualTo(SMALL_SHARE.firmInFlightCap());
        assertThat(fixture.downstream.max())
                .as("total in-flight loads never exceed the backfill share")
                .isLessThanOrEqualTo(SMALL_SHARE.bBackfill());

        // FR-010/FR-012: at most one batch per median firm, while the giant
        // firm keeps cycling — median work never queued behind giant work
        for (String firm : fixture.firmIds) {
            if (firm.equals(GIANT_FIRM)) {
                continue;
            }
            assertThat(fixture.metrics.total("backfill.batches.admitted",
                    "firm_id", firm))
                    .as("a median firm is admitted exactly once, not monopolized")
                    .isEqualTo(1L);
        }
        assertThat(fixture.metrics.total("backfill.batches.admitted",
                "firm_id", GIANT_FIRM))
                .as("the giant firm keeps receiving its single per-cycle batch")
                .isGreaterThanOrEqualTo(2L);

        assertThat(fixture.budget.available()).isEqualTo(SMALL_SHARE.bBackfill());
        assertThat(fixture.metrics.countersWithin(
                RecordingFanOutMetrics.BACKFILL_COUNTER_ALLOWLIST)).isTrue();
        assertThat(observing.errors()).isEmpty();
    }

    @Test
    void eachCycleAdmitsAtMostOneBatchPerFirmInRotatingFirmIdOrder() {
        List<String> firms = List.of("f-a", "f-b", "f-c");
        Map<String, String> cursors = new LinkedHashMap<>();
        firms.forEach(firm -> cursors.put(firm, ""));
        RecordingFanOutMetrics metrics = new RecordingFanOutMetrics();
        BackfillScheduler scheduler = new BackfillScheduler(
                firms, cursors, AdmissionGate.admitAll(metrics), SMALL_SHARE);

        BackfillScheduler.Cycle first = scheduler.admitCycle();
        assertThat(first.admitted())
                .extracting(BackfillScheduler.Admission::firmId)
                .containsExactly("f-a", "f-b", "f-c");
        first.admitted().forEach(scheduler::release);

        BackfillScheduler.Cycle second = scheduler.admitCycle();
        assertThat(second.admitted())
                .as("the scan rotates: next cycle starts one firm later (FR-010)")
                .extracting(BackfillScheduler.Admission::firmId)
                .containsExactly("f-b", "f-c", "f-a");
        assertThat(second.admitted().stream()
                .map(BackfillScheduler.Admission::firmId).distinct().count())
                .as("at most one batch per firm per cycle")
                .isEqualTo(3L);
        second.admitted().forEach(scheduler::release);

        // a firm at its in-flight cap is skipped, never queued ahead
        BackfillScheduler.Cycle third = scheduler.admitCycle();
        assertThat(third.admitted())
                .extracting(BackfillScheduler.Admission::firmId)
                .containsExactly("f-c", "f-a", "f-b");
    }

    // ---- dataset ----

    private static Map<String, List<EnumeratedEngagement>> fairnessDataset() {
        Map<String, List<EnumeratedEngagement>> dataset = new LinkedHashMap<>();
        List<EnumeratedEngagement> giant = new ArrayList<>(GIANT_SIZE);
        for (int i = 0; i < GIANT_SIZE; i++) {
            giant.add(new EnumeratedEngagement(
                    String.format("e0-%08d", i), GIANT_FIRM, "p0"));
        }
        dataset.put(GIANT_FIRM, giant);
        for (int m = 0; m < MEDIAN_FIRMS; m++) {
            String firm = String.format("f1-%04d", m);
            List<EnumeratedEngagement> items = new ArrayList<>(MEDIAN_SIZE);
            for (int i = 0; i < MEDIAN_SIZE; i++) {
                items.add(new EnumeratedEngagement(
                        String.format("e1-%04d-%04d", m, i), firm, "p1"));
            }
            dataset.put(firm, items);
        }
        return dataset;
    }

    // ---- observations ----

    private static int mediansComplete(BackfillFixture fixture) {
        int complete = 0;
        for (String firm : fixture.firmIds) {
            if (!firm.equals(GIANT_FIRM) && isComplete(fixture, firm)) {
                complete++;
            }
        }
        return complete;
    }

    private static boolean isComplete(BackfillFixture fixture, String firm) {
        return fixture.cursors.read(firm)
                .map(cursor -> cursor.status() == BackfillCursorRepository.Status.COMPLETE)
                .orElse(false);
    }

    /**
     * Delegate wrapper observing the per-firm in-flight ceiling at every load
     * entry (exact counters, never timing — FR-055/FR-072).
     */
    private static final class CapObservingClient implements DownstreamLoadClient {

        private final DownstreamLoadClient delegate;
        private final int cap;
        private final Map<String, AtomicInteger> inFlightByFirm = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> maxByFirm = new ConcurrentHashMap<>();
        private final List<String> violations = new ArrayList<>();
        private final List<Throwable> errors = new ArrayList<>();

        private CapObservingClient(DownstreamLoadClient delegate, int cap) {
            this.delegate = delegate;
            this.cap = cap;
        }

        @Override
        public String loadEngagement(EngagementRef ref) {
            return loadEngagement(ref.engagementId(), ref.firmId(), ref.productId());
        }

        @Override
        public String loadEngagement(String engagementId, String firmId, String productId) {
            AtomicInteger current = inFlightByFirm.computeIfAbsent(
                    firmId, key -> new AtomicInteger());
            int now = current.incrementAndGet();
            maxByFirm.computeIfAbsent(firmId, key -> new AtomicInteger())
                    .accumulateAndGet(now, Math::max);
            if (now > cap) {
                synchronized (violations) {
                    violations.add(firmId + " exceeded cap: " + now);
                }
            }
            try {
                return delegate.loadEngagement(engagementId, firmId, productId);
            } catch (RuntimeException failure) {
                synchronized (errors) {
                    errors.add(failure);
                }
                throw failure;
            } finally {
                current.decrementAndGet();
            }
        }

        private List<String> violations() {
            synchronized (violations) {
                return List.copyOf(violations);
            }
        }

        private List<Throwable> errors() {
            synchronized (errors) {
                return List.copyOf(errors);
            }
        }

        private int maxPerFirm() {
            return maxByFirm.values().stream().mapToInt(AtomicInteger::get).max().orElse(0);
        }
    }
}
