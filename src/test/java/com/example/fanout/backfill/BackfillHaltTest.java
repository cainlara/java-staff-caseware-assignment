package com.example.fanout.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.example.fanout.domain.EnumeratedEngagement;
import com.example.fanout.fakes.LatchDownstreamClient;
import com.example.fanout.fakes.RecordingFanOutMetrics;
import com.example.fanout.fakes.TestTaskRunner;
import com.example.fanout.fanout.TaskRunner;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * User Story 3, backfill halt protocol (spec US3 acceptance / SC-002 / SC-003,
 * FR-030-FR-034, T028): a halt observed mid-run drains in-flight loads under
 * normal rules, abandons blocked permit waiters, releases every permit,
 * persists every completed-batch checkpoint, and a later clear-and-rerun
 * converges fault-free with exact-once rows.
 *
 * <p>Determinism: signal-based waits only (FR-055). The saturation test runs
 * {@code firmCount == bBackfill} firms on the production fixed pool so every
 * permit is provably held at the halt observation; the checkpoint test pumps
 * hand-off jobs one at a time and flips the halt switch between pumps.
 */
class BackfillHaltTest {

    private static final BackfillConfig CONFIG = BackfillConfig.defaults();
    private static final int B = CONFIG.bBackfill();
    private static final Duration DRAIN_BOUND = CONFIG.haltDrainBound();

    @Test
    void haltWhileEveryPermitIsHeldDrainsInFlightWorkAndReleasesTheWholeShare()
            throws Exception {
        BackfillFixture fixture = new BackfillFixture();
        fixture.seed(7L, B, 60);
        fixture.seedEffectiveForAllProducts(BackfillFixture.EFFECTIVE_VERSION);
        LatchDownstreamClient latch = new LatchDownstreamClient();
        fixture.loadClient = latch; // blockAfter(0): every load blocks
        fixture.runner = TaskRunner.fixedPool(B);

        BackfillWorker worker = fixture.newWorker();
        AtomicReference<BackfillOutcome> outcome = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread run = new Thread(() -> {
            try {
                outcome.set(worker.run());
            } catch (Throwable failure) {
                error.set(failure);
            }
        }, "backfill-halt-run");
        run.start();

        latch.awaitInFlight(B); // the whole share is saturated: every permit held
        fixture.flags.setHalt(true);

        long loadsAtObservation = latch.calls();
        long admittedAtObservation = fixture.metrics.total("backfill.batches.admitted");
        long completedAtObservation = fixture.metrics.total("backfill.batches.completed");

        latch.releaseAll();
        run.join(Duration.ofSeconds(30).toMillis());
        assertThat(run.isAlive()).as("halt drains within the join bound").isFalse();
        assertThat(error.get()).isNull();

        BackfillOutcome result = outcome.get();
        assertThat(result.status()).isEqualTo(BackfillOutcome.BackfillStatus.HALTED);

        // zero new batch/engagement/permit admissions after the observation:
        // no new downstream loads, no new batch admitted, nothing advanced
        assertThat(latch.calls()).isEqualTo(loadsAtObservation);
        assertThat(fixture.metrics.total("backfill.batches.admitted"))
                .isEqualTo(admittedAtObservation);
        assertThat(fixture.metrics.total("backfill.batches.completed"))
                .isEqualTo(completedAtObservation);
        assertThat(fixture.metrics.total("backfill.permit.park")).isZero();
        assertThat(fixture.metrics.total("backfill.engagements.written"))
                .as("only the already-admitted in-flight engagements drain")
                .isEqualTo(B);

        // every permit released; the drain was recorded within the bound
        assertThat(latch.max()).isEqualTo(B);
        assertThat(fixture.budget.available()).isEqualTo(B);
        var drain = fixture.metrics.getTimers().stream()
                .filter(t -> t.name().equals("backfill.halt.drain"))
                .findFirst();
        assertThat(drain).as("backfill.halt.drain timer recorded").isPresent();
        assertThat(drain.get().duration()).isLessThanOrEqualTo(DRAIN_BOUND);

        // clear the halt and re-run: fault-free convergence, exact-once rows
        fixture.flags.setHalt(false);
        BackfillOutcome resumed = fixture.newWorker().run();
        assertThat(resumed.status()).isEqualTo(BackfillOutcome.BackfillStatus.COMPLETED);
        assertThat(resumed.firmsRemaining()).isZero();
        int expected = B * 60;
        assertThat(fixture.index.rowCount()).isEqualTo(expected);
        assertThat(fixture.index.createdTotal()).isEqualTo(expected);
        for (String engagementId : fixture.allEngagementIds()) {
            assertThat(fixture.index.createCount(engagementId))
                    .as("exact-once across halt and resume for " + engagementId)
                    .isEqualTo(1);
        }
        for (String firm : fixture.firmIds) {
            assertThat(fixture.cursors.read(firm).orElseThrow().status())
                    .isEqualTo(BackfillCursorRepository.Status.COMPLETE);
        }
        assertThat(fixture.metrics.countersWithin(
                RecordingFanOutMetrics.BACKFILL_COUNTER_ALLOWLIST)).isTrue();
    }

    @Test
    void haltPreservesCompletedCheckpointsAndARerunResumesFaultFree()
            throws Exception {
        BackfillFixture fixture = new BackfillFixture();
        Map<String, List<EnumeratedEngagement>> dataset = new LinkedHashMap<>();
        for (int firm = 0; firm < 3; firm++) {
            dataset.put("f9-" + String.format("%04d", firm), items(firm, 60));
        }
        String giantFirm = "f9-0100";
        dataset.put(giantFirm, items(100, 4000));
        fixture.seed(dataset);
        fixture.seedEffectiveForAllProducts(BackfillFixture.EFFECTIVE_VERSION);
        TestTaskRunner handOff = new TestTaskRunner(B, TestTaskRunner.Mode.HAND_OFF);
        fixture.runner = handOff;

        BackfillWorker worker = fixture.newWorker();
        AtomicReference<BackfillOutcome> outcome = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread run = new Thread(() -> {
            try {
                outcome.set(worker.run());
            } catch (Throwable failure) {
                error.set(failure);
            }
        }, "backfill-halt-pump-run");
        run.start();

        // pump hand-off jobs until at least one firm checkpoint is COMPLETE
        while (!anyComplete(fixture)) {
            Runnable job = awaitJob(handOff, run);
            if (job == null) {
                fail("run ended before any firm checkpoint completed");
                return;
            }
            job.run();
        }
        List<String> completedBeforeHalt = completeFirms(fixture);

        fixture.flags.setHalt(true);
        while (run.isAlive()) {
            Runnable job = handOff.poll();
            if (job != null) {
                job.run();
                continue;
            }
            Thread.onSpinWait();
        }
        run.join(Duration.ofSeconds(30).toMillis());
        assertThat(run.isAlive()).isFalse();
        assertThat(error.get()).isNull();

        BackfillOutcome result = outcome.get();
        assertThat(result.status()).isEqualTo(BackfillOutcome.BackfillStatus.HALTED);
        assertThat(completedBeforeHalt).isNotEmpty();
        for (String firm : completedBeforeHalt) {
            assertThat(fixture.cursors.read(firm).orElseThrow().status())
                    .as("completed-batch checkpoint persisted through the halt")
                    .isEqualTo(BackfillCursorRepository.Status.COMPLETE);
        }
        assertThat(fixture.cursors.read(giantFirm).orElseThrow().status())
                .as("the giant firm stays behind its last completed batch")
                .isEqualTo(BackfillCursorRepository.Status.RUNNING);
        assertThat(fixture.budget.available()).isEqualTo(B);
        assertThat(fixture.metrics.saw("backfill.halt.drain")).isTrue();

        // clear the halt and rerun on the production pool: exact-once finish
        fixture.flags.setHalt(false);
        fixture.runner = TaskRunner.fixedPool(B);
        BackfillOutcome resumed = fixture.newWorker().run();
        assertThat(resumed.status()).isEqualTo(BackfillOutcome.BackfillStatus.COMPLETED);
        int expected = 3 * 60 + 4000;
        assertThat(fixture.index.createdTotal()).isEqualTo(expected);
        assertThat(fixture.index.rowCount()).isEqualTo(expected);
        for (String firm : fixture.firmIds) {
            assertThat(fixture.cursors.read(firm).orElseThrow().status())
                    .isEqualTo(BackfillCursorRepository.Status.COMPLETE);
        }
        assertThat(fixture.metrics.countersWithin(
                RecordingFanOutMetrics.BACKFILL_COUNTER_ALLOWLIST)).isTrue();
    }

    // ---- helpers ----

    private static List<EnumeratedEngagement> items(int firmNumber, int count) {
        List<EnumeratedEngagement> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            items.add(new EnumeratedEngagement(
                    String.format("e9-%04d-%08d", firmNumber, i),
                    String.format("f9-%04d", firmNumber),
                    String.format("p9-%04d", i % 5)));
        }
        return items;
    }

    private static boolean anyComplete(BackfillFixture fixture) {
        return !completeFirms(fixture).isEmpty();
    }

    private static List<String> completeFirms(BackfillFixture fixture) {
        List<String> complete = new ArrayList<>();
        for (String firm : fixture.firmIds) {
            var cursor = fixture.cursors.read(firm);
            if (cursor.isPresent()
                    && cursor.get().status() == BackfillCursorRepository.Status.COMPLETE) {
                complete.add(firm);
            }
        }
        return complete;
    }

    /** Signal-based wait for the next hand-off job (bounded, never sleeps). */
    private static Runnable awaitJob(TestTaskRunner runner, Thread runThread) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (true) {
            Runnable job = runner.poll();
            if (job != null) {
                return job;
            }
            if (!runThread.isAlive()) {
                return null;
            }
            if (System.nanoTime() > deadline) {
                return fail("no hand-off job within the harness bound");
            }
            Thread.onSpinWait();
        }
    }
}
