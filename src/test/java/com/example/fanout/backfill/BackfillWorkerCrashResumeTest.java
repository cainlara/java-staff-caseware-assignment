package com.example.fanout.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.example.fanout.domain.PendingIndexRow;
import com.example.fanout.fakes.CrashingCapacityBudget;
import com.example.fanout.fakes.CrashingCheckpointStore;
import com.example.fanout.fakes.CrashingIndexWriter;
import com.example.fanout.fakes.KillController;
import com.example.fanout.fakes.KillingFeatureFlags;
import com.example.fanout.fakes.RecordingFanOutMetrics;
import com.example.fanout.ports.WriteResult;
import com.example.fanout.fakes.SimulatedProcessDeath;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * User Story 2 (spec US2 / SC-001 / SC-003, research.md D12, T026/T027):
 * kills at admission, load, write, and checkpoint points followed by restarts
 * always converge to the fault-free row set — N rows, per-engagement
 * create-count exactly one, no other mutations, every firm checkpoint
 * complete.
 *
 * <p>Random-kill property: a fixed seed table of (seed, k) pairs with
 * {@code k in [1, 7]} kills; kill points are deterministic ordinals
 * {@code ceil(i * T / (k + 1))} derived from the fault-free baseline's event
 * count T on identical seams (the seed table supplies variation; kill time is
 * pure arithmetic — no sleeping, no wall-clock dependence, FR-055).
 *
 * <p>Focused case: death between the last row write and the cursor advance —
 * the whole batch re-runs once, duplicate-skips absorb it, exactly one batch
 * of redundant loads (SC-003).
 */
class BackfillWorkerCrashResumeTest {

    private static final int FIRMS = 50;
    private static final int PER_FIRM = 60;
    private static final int TOTAL = FIRMS * PER_FIRM;
    private static final int B_BACKFILL = BackfillConfig.defaults().bBackfill();

    /** Fixed seed table: (seed, k) with k in [1, 7] (research D12). */
    private record CrashCase(long seed, int kills) {
    }

    private static final List<CrashCase> SEED_TABLE = List.of(
            new CrashCase(11L, 1),
            new CrashCase(22L, 3),
            new CrashCase(33L, 7));

    @Test
    void randomKillsAcrossTheFixedSeedTableAlwaysConvergeToTheFaultFreeRowSet() {
        for (CrashCase crashCase : SEED_TABLE) {
            assertCrashResume(crashCase);
        }
    }

    @Test
    void killBetweenLastWriteAndCursorAdvanceRerunsExactlyOneBatch() {
        KillController controller = new KillController();
        int engagements = 40;

        BackfillFixture baseline = new BackfillFixture();
        baseline.seed(5L, 1, engagements);
        baseline.seedEffectiveForAllProducts(BackfillFixture.EFFECTIVE_VERSION);
        BackfillOutcome baseOutcome = baseline.newWorker().run();
        assertThat(baseOutcome.status()).isEqualTo(BackfillOutcome.BackfillStatus.COMPLETED);
        Map<String, String> expectedRows = businessRows(baseline.index.allRows());
        assertThat(expectedRows).hasSize(engagements);

        BackfillFixture crash = new BackfillFixture();
        crash.seed(5L, 1, engagements);
        crash.seedEffectiveForAllProducts(BackfillFixture.EFFECTIVE_VERSION);
        CrashingCheckpointStore crashingStore =
                new CrashingCheckpointStore(crash.backing, controller);
        crash.store = crashingStore;
        crashingStore.killBeforeNextWrite();

        try {
            crash.newWorker().run();
            fail("expected a process death before the first cursor advance");
        } catch (SimulatedProcessDeath expectedDeath) {
            // restart below
        }

        BackfillOutcome restarted = crash.newWorker().run();

        assertThat(restarted.status()).isEqualTo(BackfillOutcome.BackfillStatus.COMPLETED);
        assertThat(crash.index.rowCount()).isEqualTo(engagements);
        assertThat(crash.index.createdTotal())
                .as("the re-executed batch is absorbed by duplicate skips (SC-003)")
                .isEqualTo(engagements);
        assertThat(businessRows(crash.index.allRows())).isEqualTo(expectedRows);
        for (String engagementId : engagementIds(baseline)) {
            assertThat(crash.index.createCount(engagementId)).isEqualTo(1);
            assertThat(crash.index.updateCount(engagementId)).isZero();
        }
        assertThat(crash.metrics.total("backfill.batches.admitted"))
                .as("exactly one batch re-executed: one per run (FR-005)")
                .isEqualTo(2L);
        assertThat(crash.metrics.total("backfill.batches.completed"))
                .as("only the restarted run's advance committed: the killed one died mid-write")
                .isEqualTo(1L);
        assertThat(crash.downstream.calls())
                .as("exactly one batch of redundant loads per firm")
                .isEqualTo(2L * engagements);
        for (String firm : crash.firmIds) {
            assertThat(crash.cursors.read(firm).orElseThrow().status())
                    .isEqualTo(BackfillCursorRepository.Status.COMPLETE);
        }
    }

    // ---- crash-resume property ----

    private void assertCrashResume(CrashCase crashCase) {
        long seed = crashCase.seed();
        int k = crashCase.kills();

        // fault-free baseline on identical seams (kill never armed)
        KillController baselineController = new KillController();
        BackfillFixture baseline = seamedFixture(seed, baselineController);
        BackfillOutcome baseOutcome = baseline.newWorker().run();
        assertThat(baseOutcome.status()).isEqualTo(BackfillOutcome.BackfillStatus.COMPLETED);
        int totalEvents = baselineController.totalEvents();
        assertThat(totalEvents).isPositive();
        assertThat(baseline.index.rowCount()).isEqualTo(TOTAL);
        assertThat(baseline.index.createdTotal()).isEqualTo(TOTAL);
        Map<String, String> expectedRows = businessRows(baseline.index.allRows());

        // crash runs: kill i at ordinal ceil(i * T / (k + 1)) of run i
        KillController controller = new KillController();
        BackfillFixture crash = seamedFixture(seed, controller);
        BackfillOutcome outcome = runUntilCompleted(crash, controller, totalEvents, k);

        assertThat(outcome.firmsRemaining()).isZero();
        assertThat(controller.killsFired())
                .isGreaterThanOrEqualTo(1)
                .isLessThanOrEqualTo(k);

        for (String firm : crash.firmIds) {
            assertThat(crash.cursors.read(firm).orElseThrow().status())
                    .as("every firm checkpoint completes after restart")
                    .isEqualTo(BackfillCursorRepository.Status.COMPLETE);
        }
        assertThat(crash.index.rowCount()).isEqualTo(TOTAL);
        assertThat(crash.index.createdTotal()).isEqualTo(TOTAL);
        assertThat(businessRows(crash.index.allRows()))
                .as("final row set equals the fault-free run (SC-001)")
                .isEqualTo(expectedRows);

        List<String> ids = engagementIds(baseline);
        for (String engagementId : ids) {
            assertThat(crash.index.createCount(engagementId))
                    .as("create-count exactly one for " + engagementId)
                    .isEqualTo(1);
            assertThat(crash.index.updateCount(engagementId)).isZero();
        }
        for (var event : crash.index.audit()) {
            assertThat(ids).contains(event.engagementId());
            assertThat(event.updated()).isFalse();
            assertThat(event.result()).isIn(WriteResult.WRITTEN, WriteResult.SKIPPED_DUPLICATE);
        }
        assertThat(crash.budget.available()).isEqualTo(B_BACKFILL);
        assertThat(crash.metrics.countersWithin(
                RecordingFanOutMetrics.BACKFILL_COUNTER_ALLOWLIST)).isTrue();
    }

    private static BackfillOutcome runUntilCompleted(BackfillFixture crash,
                                                     KillController controller,
                                                     int totalEvents, int k) {
        int maxRuns = k + 3;
        for (int run = 1; run <= maxRuns; run++) {
            if (run <= k) {
                int target = (int) ((run * (long) totalEvents + k) / (k + 1L));
                controller.armRun(target);
            } else {
                controller.disarm();
            }
            try {
                BackfillOutcome outcome = crash.newWorker().run();
                assertThat(outcome.status())
                        .as("a completed run must be COMPLETED, never FAILED or HALTED")
                        .isEqualTo(BackfillOutcome.BackfillStatus.COMPLETED);
                return outcome;
            } catch (SimulatedProcessDeath death) {
                // process died: the loop restarts with a fresh worker
            }
        }
        return fail("backfill did not complete within " + maxRuns + " restarts");
    }

    // ---- fixtures and helpers ----

    /** Fixture on the full crash-seam wiring (kill never fires unless armed). */
    private static BackfillFixture seamedFixture(long seed, KillController controller) {
        BackfillFixture fixture = new BackfillFixture();
        fixture.seed(seed, FIRMS, PER_FIRM);
        fixture.seedEffectiveForAllProducts(BackfillFixture.EFFECTIVE_VERSION);
        fixture.gateFlags = new KillingFeatureFlags(fixture.flags, controller);
        fixture.budget = new CrashingCapacityBudget(fixture.budget, controller);
        fixture.indexWriter = new CrashingIndexWriter(fixture.index, controller);
        fixture.store = new CrashingCheckpointStore(fixture.backing, controller);
        return fixture;
    }

    /** Fault-free business row set keyed by engagement id (updatedAt excluded). */
    private static Map<String, String> businessRows(List<PendingIndexRow> rows) {
        Map<String, String> byId = new LinkedHashMap<>();
        for (PendingIndexRow row : rows) {
            byId.put(row.engagementId(),
                    row.engagementId() + "|" + row.firmId() + "|" + row.productId()
                            + "|" + row.currentVersion() + "|" + row.offeredVersion()
                            + "|" + row.state() + "|" + row.decision()
                            + "|" + row.pinnedRanges() + "|" + row.summaryKey()
                            + "|" + row.publishIds() + "|" + row.rowVersion());
        }
        return byId;
    }

    private static List<String> engagementIds(BackfillFixture fixture) {
        List<String> ids = new ArrayList<>();
        for (String firm : fixture.firmIds) {
            fixture.enumerator.dataset(firm)
                    .forEach(item -> ids.add(item.engagementId()));
        }
        return ids;
    }
}
