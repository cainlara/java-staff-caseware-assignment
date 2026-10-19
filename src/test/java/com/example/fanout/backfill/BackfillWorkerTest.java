package com.example.fanout.backfill;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.fanout.domain.EnumeratedEngagement;
import com.example.fanout.domain.PendingIndexRow;
import com.example.fanout.domain.PendingIndexState;
import com.example.fanout.fanout.TaskRunner;
import com.example.fanout.fakes.RecordingFanOutMetrics;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * User Story 1 happy path (spec FR-002–FR-008, SC-008, SC-010;
 * spec.md US1 independent test): a seeded estate of 5,000 engagements across
 * 50 firms backfills to completion — exact row set, every engagement created
 * exactly once, every load under a backfill-share permit, in-flight never
 * above the share, and engagements already at the effective version (or
 * belonging to a product with no effective version) resolving as no-ops with
 * zero rows and zero failures.
 *
 * <p>Everything is deterministic: seeded dataset, seeded jitter, fake clock,
 * signal-based waits only (spec FR-055).
 */
class BackfillWorkerTest {

    private static final int FIRMS = 50;
    private static final int PER_FIRM = 100;
    private static final int TOTAL = FIRMS * PER_FIRM;

    private BackfillFixture fixture = new BackfillFixture();

    @AfterEach
    void backfillInvariants() {
        assertThat(fixture.metrics.countersWithin(
                RecordingFanOutMetrics.BACKFILL_COUNTER_ALLOWLIST))
                .as("backfill counters stay on the FR-053 allowlist")
                .isTrue();
        assertThat(fixture.deadLetters.count())
                .as("no dead letters on the fault-free path")
                .isZero();
        assertThat(fixture.budget.available())
                .as("every backfill permit released exactly once (SC-005)")
                .isEqualTo(fixture.config.bBackfill());
    }

    @Test
    void backfillsFiveThousandEngagementsAcrossFiftyFirmsExactlyOnce() {
        fixture.seed(7L, FIRMS, PER_FIRM);
        fixture.seedEffectiveForAllProducts(BackfillFixture.EFFECTIVE_VERSION);
        fixture.runner = TaskRunner.fixedPool(fixture.config.bBackfill());

        BackfillOutcome outcome = fixture.newWorker().run();

        assertThat(outcome.status()).isEqualTo(BackfillOutcome.BackfillStatus.COMPLETED);
        assertThat(outcome.firmsRemaining()).isZero();
        assertThat(outcome.rowsWritten()).isEqualTo(TOTAL);
        assertThat(outcome.noOps()).isZero();
        assertThat(outcome.deadLettered()).isZero();
        assertThat(outcome.engagementsResolved()).isEqualTo(TOTAL);

        assertThat(fixture.index.rowCount()).isEqualTo(TOTAL);
        assertThat(fixture.downstream.calls()).isEqualTo(TOTAL);
        assertThat(fixture.downstream.callLog()).doesNotHaveDuplicates();

        for (String firm : fixture.firmIds) {
            for (EnumeratedEngagement item : fixture.enumerator.dataset(firm)) {
                PendingIndexRow row = fixture.index
                        .find(item.engagementId(), BackfillFixture.EFFECTIVE_VERSION,
                                PendingIndexState.PENDING)
                        .orElseThrow(() -> new AssertionError(
                                "missing row for " + item.engagementId()));
                assertThat(row.currentVersion()).isEqualTo(BackfillFixture.STALE_VERSION);
                assertThat(row.firmId()).isEqualTo(item.firmId());
                assertThat(row.productId()).isEqualTo(item.productId());
                assertThat(row.rowVersion()).isEqualTo(1L);
                assertThat(row.publishIds()).hasSize(1);
                assertThat(row.decision()).isNull();
                assertThat(row.pinnedRanges()).isEmpty();
                assertThat(row.summaryKey()).isNull();
                assertThat(fixture.index.createCount(item.engagementId())).isEqualTo(1);
                assertThat(fixture.index.updateCount(item.engagementId())).isZero();
            }
        }

        for (String firm : fixture.firmIds) {
            assertThat(fixture.cursors.read(firm)).isPresent();
            assertThat(fixture.cursors.read(firm).orElseThrow().status())
                    .isEqualTo(BackfillCursorRepository.Status.COMPLETE);
        }

        assertThat(fixture.metrics.total("backfill.batches.admitted")).isEqualTo(FIRMS);
        assertThat(fixture.metrics.total("backfill.batches.completed")).isEqualTo(FIRMS);
        assertThat(fixture.metrics.total("backfill.engagements.written")).isEqualTo(TOTAL);
        assertThat(fixture.metrics.total("backfill.engagements.noop")).isZero();

        assertThat(fixture.downstream.max())
                .as("observed in-flight loads never exceed the backfill share (SC-005/SC-008)")
                .isLessThanOrEqualTo(fixture.config.bBackfill());
        assertThat(fixture.metrics.total("backfill.downstream.inflight.hwm"))
                .isBetween(1L, (long) fixture.config.bBackfill());
        assertThat(fixture.metrics.total("backfill.permit.park")).isZero();
    }

    @Test
    void engagementsAlreadyAtEffectiveVersionResolveAsNoOpsWithoutRows() {
        fixture.seed(7L, 10, 100);
        List<String> writtenProducts = new ArrayList<>();
        List<String> noopProducts = new ArrayList<>();
        int productIndex = 0;
        for (String product : fixture.products()) {
            if (productIndex++ < 5) {
                noopProducts.add(product);
                fixture.seedEffective(product, "pub-" + product, BackfillFixture.STALE_VERSION);
            } else {
                writtenProducts.add(product);
                fixture.seedEffective(product, "pub-" + product, BackfillFixture.EFFECTIVE_VERSION);
            }
        }

        BackfillOutcome outcome = fixture.newWorker().run();

        assertThat(outcome.status()).isEqualTo(BackfillOutcome.BackfillStatus.COMPLETED);
        assertThat(outcome.noOps()).isEqualTo(500);
        assertThat(outcome.rowsWritten()).isEqualTo(500);
        assertThat(outcome.engagementsResolved()).isEqualTo(1000);
        assertThat(outcome.deadLettered()).isZero();
        assertThat(fixture.index.rowCount()).isEqualTo(500);
        assertThat(fixture.metrics.total("backfill.engagements.noop")).isEqualTo(500);

        for (String firm : fixture.firmIds) {
            for (EnumeratedEngagement item : fixture.enumerator.dataset(firm)) {
                boolean noop = noopProducts.contains(item.productId());
                if (noop) {
                    assertThat(fixture.index.createCount(item.engagementId()))
                            .as("no-op engagements produce no row (FR-006)")
                            .isZero();
                    assertThat(fixture.index.find(item.engagementId(),
                            BackfillFixture.EFFECTIVE_VERSION, PendingIndexState.PENDING))
                            .isEmpty();
                } else {
                    assertThat(fixture.index.createCount(item.engagementId())).isEqualTo(1);
                    assertThat(fixture.index.find(item.engagementId(),
                            BackfillFixture.EFFECTIVE_VERSION, PendingIndexState.PENDING))
                            .isPresent();
                }
            }
            assertThat(fixture.cursors.read(firm).orElseThrow().status())
                    .isEqualTo(BackfillCursorRepository.Status.COMPLETE);
        }
        assertThat(writtenProducts).hasSize(5);
        assertThat(noopProducts).hasSize(5);
    }

    @Test
    void productWithoutEffectiveVersionYieldsZeroRowsButStillCheckpoints() {
        fixture.seed(7L, 10, 50);
        int productIndex = 0;
        for (String product : fixture.products()) {
            if (productIndex++ >= 5) {
                fixture.seedEffective(product, "pub-" + product, BackfillFixture.EFFECTIVE_VERSION);
            }
        }

        BackfillOutcome outcome = fixture.newWorker().run();

        assertThat(outcome.status()).isEqualTo(BackfillOutcome.BackfillStatus.COMPLETED);
        assertThat(outcome.rowsWritten()).isEqualTo(250);
        assertThat(outcome.noOps()).isEqualTo(250);
        assertThat(outcome.deadLettered()).isZero();
        assertThat(fixture.index.rowCount()).isEqualTo(250);
        assertThat(fixture.metrics.total("backfill.engagements.noop")).isEqualTo(250);

        for (String firm : fixture.firmIds) {
            assertThat(fixture.cursors.read(firm).orElseThrow().status())
                    .as("a product without publishes still checkpoints (FR-007)")
                    .isEqualTo(BackfillCursorRepository.Status.COMPLETE);
        }
        assertThat(fixture.metrics.total("backfill.batches.completed")).isEqualTo(10);
    }

    @Test
    void reRunningACompletedBackfillIsANoOp() {
        fixture.seed(7L, 5, 20);
        fixture.seedEffectiveForAllProducts(BackfillFixture.EFFECTIVE_VERSION);

        BackfillOutcome first = fixture.newWorker().run();
        BackfillOutcome second = fixture.newWorker().run();

        assertThat(first.status()).isEqualTo(BackfillOutcome.BackfillStatus.COMPLETED);
        assertThat(second.status()).isEqualTo(BackfillOutcome.BackfillStatus.COMPLETED);
        assertThat(second.rowsWritten()).isZero();
        assertThat(second.engagementsResolved()).isZero();
        assertThat(fixture.index.rowCount()).isEqualTo(100);
        assertThat(fixture.index.createdTotal()).isEqualTo(100);
        assertThat(fixture.metrics.total("backfill.batches.admitted")).isEqualTo(5);
    }
}
