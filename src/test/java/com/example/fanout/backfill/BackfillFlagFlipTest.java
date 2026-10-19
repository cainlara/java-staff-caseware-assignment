package com.example.fanout.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.example.fanout.fakes.RecordingFanOutMetrics;
import com.example.fanout.fakes.TestTaskRunner;
import com.example.fanout.fanout.TaskRunner;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * User Story 6, backfill side (spec SC-007, FR-041-FR-044, T040-T042): flipping
 * one firm's (or one product's) flag off mid-backfill stops that work at the
 * next admission decision — no failure, no dead-letter, no buffered queue —
 * while every other firm finishes; the flag-off state holds across cycles
 * until the run reports GATED; flipping it back on re-derives only the
 * remaining work from the cursor to an exact-once finish.
 *
 * <p>Determinism: hand-off runner, jobs pumped one at a time, the flip lands
 * between the first admission cycle and its executions — no sleeps anywhere
 * (FR-055). The engagement-level re-check (T042) aborts an already-admitted
 * batch before its first gated engagement resolves.
 */
class BackfillFlagFlipTest {

    private static final int FIRMS = 10;
    private static final int PER_FIRM = 60;
    private static final long SEED = 5L;

    @Test
    void flippingOneFirmFlagOffStopsThatFirmAtTheNextAdmissionAndResumeFinishesExactlyOnce()
            throws Exception {
        BackfillFixture fixture = new BackfillFixture();
        fixture.seed(SEED, FIRMS, PER_FIRM);
        fixture.seedEffectiveForAllProducts(BackfillFixture.EFFECTIVE_VERSION);
        TestTaskRunner handOff = new TestTaskRunner(
                fixture.config.bBackfill(), TestTaskRunner.Mode.HAND_OFF);
        fixture.runner = handOff;
        String gatedFirm = fixture.firmIds.get(2);
        List<String> otherFirms = fixture.firmIds.stream()
                .filter(firm -> !firm.equals(gatedFirm)).toList();

        BackfillWorker worker = fixture.newWorker();
        AtomicReference<BackfillOutcome> outcome = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread run = new Thread(() -> {
            try {
                outcome.set(worker.run());
            } catch (Throwable failure) {
                error.set(failure);
            }
        }, "backfill-firm-flag-flip-run");
        run.start();

        // cycle 1 admitted every firm before the flip (flag was on for all)
        awaitHanded(handOff, run, FIRMS, "first admission cycle");
        fixture.flags.setEnabledFirm(gatedFirm, false);

        pumpUntilDone(handOff, run);
        run.join(Duration.ofSeconds(30).toMillis());
        assertThat(run.isAlive()).isFalse();
        assertThat(error.get()).isNull();

        BackfillOutcome gated = outcome.get();
        assertThat(gated.status()).isEqualTo(BackfillOutcome.BackfillStatus.GATED);
        assertThat(gated.firmsRemaining()).isOne();
        assertThat(fixture.deadLetters.count()).isZero();
        assertThat(fixture.metrics.saw("backfill.dlq")).isFalse();
        assertThat(fixture.metrics.saw("backfill.admit.reject.firm")).isTrue();

        // the gated firm: no rows, no cursor advance, bounded batch admissions
        for (String engagementId : engagementIdsOf(fixture, gatedFirm)) {
            assertThat(fixture.index.createCount(engagementId))
                    .as("%s stays out while the flag is off", engagementId)
                    .isZero();
        }
        assertThat(fixture.cursors.read(gatedFirm))
                .as("the batch never advanced the cursor")
                .isEmpty();
        long gatedAdmissions = fixture.metrics.total(
                "backfill.batches.admitted", "firm_id", gatedFirm);
        assertThat(gatedAdmissions)
                .as("only the pre-flip admission ran; later cycles admit nothing "
                        + "(flag off across cycles = no unbounded queue)")
                .isEqualTo(1);

        // every other firm completed and wrote its rows
        for (String firm : otherFirms) {
            assertThat(fixture.cursors.read(firm).orElseThrow().status())
                    .as(firm + " is unaffected by the gated firm")
                    .isEqualTo(BackfillCursorRepository.Status.COMPLETE);
        }
        assertThat(fixture.index.createdTotal()).isEqualTo(otherFirms.size() * PER_FIRM);

        // flag back on: re-derived from the cursor, exact-once completion
        fixture.flags.setEnabledFirm(gatedFirm, true);
        fixture.runner = TaskRunner.fixedPool(fixture.config.bBackfill());
        BackfillOutcome resumed = fixture.newWorker().run();
        assertThat(resumed.status()).isEqualTo(BackfillOutcome.BackfillStatus.COMPLETED);
        assertThat(resumed.firmsRemaining()).isZero();
        assertThat(fixture.index.createdTotal()).isEqualTo(FIRMS * PER_FIRM);
        assertThat(fixture.index.rowCount()).isEqualTo(FIRMS * PER_FIRM);
        for (String engagementId : fixture.allEngagementIds()) {
            assertThat(fixture.index.createCount(engagementId))
                    .as("exact-once across flip and resume for " + engagementId)
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
    void flippingOneProductFlagOffStopsItsEngagementsAndResumeFinishesExactlyOnce()
            throws Exception {
        BackfillFixture fixture = new BackfillFixture();
        fixture.seed(SEED, FIRMS, PER_FIRM);
        fixture.seedEffectiveForAllProducts(BackfillFixture.EFFECTIVE_VERSION);
        TestTaskRunner handOff = new TestTaskRunner(
                fixture.config.bBackfill(), TestTaskRunner.Mode.HAND_OFF);
        fixture.runner = handOff;
        String gatedFirm = fixture.firmIds.get(5);
        String gatedProduct = fixture.enumerator.dataset(gatedFirm).getFirst().productId();
        List<String> otherFirms = fixture.firmIds.stream()
                .filter(firm -> !firm.equals(gatedFirm)).toList();

        BackfillWorker worker = fixture.newWorker();
        AtomicReference<BackfillOutcome> outcome = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread run = new Thread(() -> {
            try {
                outcome.set(worker.run());
            } catch (Throwable failure) {
                error.set(failure);
            }
        }, "backfill-product-flag-flip-run");
        run.start();

        // cycle 1 admitted every firm before the flip
        awaitHanded(handOff, run, FIRMS, "first admission cycle");
        fixture.flags.setEnabled(gatedProduct, false);

        pumpUntilDone(handOff, run);
        run.join(Duration.ofSeconds(30).toMillis());
        assertThat(run.isAlive()).isFalse();
        assertThat(error.get()).isNull();

        BackfillOutcome gated = outcome.get();
        assertThat(gated.status()).isEqualTo(BackfillOutcome.BackfillStatus.GATED);
        assertThat(gated.firmsRemaining()).isOne();
        assertThat(fixture.deadLetters.count()).isZero();
        assertThat(fixture.metrics.saw("backfill.dlq")).isFalse();
        assertThat(fixture.metrics.saw("backfill.admit.reject.product")).isTrue();

        for (String engagementId : engagementIdsOf(fixture, gatedFirm)) {
            assertThat(fixture.index.createCount(engagementId))
                    .as("every %s engagement stays out while the product flag is off",
                            gatedProduct)
                    .isZero();
        }
        assertThat(fixture.cursors.read(gatedFirm))
                .as("the product-gated batch never advanced the cursor")
                .isEmpty();
        long gatedAdmissions = fixture.metrics.total(
                "backfill.batches.admitted", "firm_id", gatedFirm);
        assertThat(gatedAdmissions)
                .as("batch admissions stay bounded across the gated cycles")
                .isBetween(1L, 3L);

        for (String firm : otherFirms) {
            assertThat(fixture.cursors.read(firm).orElseThrow().status())
                    .as(firm + " is unaffected by the gated product")
                    .isEqualTo(BackfillCursorRepository.Status.COMPLETE);
        }
        assertThat(fixture.index.createdTotal()).isEqualTo(otherFirms.size() * PER_FIRM);

        // product flag back on: only the remaining engagements resolve
        fixture.flags.setEnabled(gatedProduct, true);
        fixture.runner = TaskRunner.fixedPool(fixture.config.bBackfill());
        BackfillOutcome resumed = fixture.newWorker().run();
        assertThat(resumed.status()).isEqualTo(BackfillOutcome.BackfillStatus.COMPLETED);
        assertThat(fixture.index.createdTotal()).isEqualTo(FIRMS * PER_FIRM);
        assertThat(fixture.index.rowCount()).isEqualTo(FIRMS * PER_FIRM);
        for (String engagementId : fixture.allEngagementIds()) {
            assertThat(fixture.index.createCount(engagementId))
                    .as("exact-once across flip and resume for " + engagementId)
                    .isEqualTo(1);
        }
        for (String firm : fixture.firmIds) {
            assertThat(fixture.cursors.read(firm).orElseThrow().status())
                    .isEqualTo(BackfillCursorRepository.Status.COMPLETE);
        }
        assertThat(fixture.metrics.countersWithin(
                RecordingFanOutMetrics.BACKFILL_COUNTER_ALLOWLIST)).isTrue();
    }

    // ---- helpers ----

    private static List<String> engagementIdsOf(BackfillFixture fixture, String firm) {
        List<String> ids = new ArrayList<>();
        fixture.enumerator.dataset(firm).forEach(item -> ids.add(item.engagementId()));
        return ids;
    }

    /** Signal-based wait until {@code count} jobs are queued (never sleeps). */
    private static void awaitHanded(TestTaskRunner runner, Thread runThread,
                                    int count, String what) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (runner.handedCount() < count) {
            if (!runThread.isAlive()) {
                fail("run ended before " + what + " was handed off");
            }
            if (System.nanoTime() > deadline) {
                fail(what + " never handed off " + count + " jobs");
            }
            Thread.onSpinWait();
        }
    }

    /** Pump every queued job until the run thread finishes (bounded). */
    private static void pumpUntilDone(TestTaskRunner runner, Thread runThread) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (runThread.isAlive()) {
            Runnable job = runner.poll();
            if (job != null) {
                job.run();
                continue;
            }
            if (System.nanoTime() > deadline) {
                fail("run did not finish within the harness bound");
            }
            Thread.onSpinWait();
        }
    }
}
