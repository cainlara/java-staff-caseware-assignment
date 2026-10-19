package com.example.fanout.backfill;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.fanout.domain.DeadLetterUnitKind;
import com.example.fanout.domain.PendingIndexState;
import com.example.fanout.fakes.RecordingFanOutMetrics;
import com.example.fanout.fanout.TransientFanOutException;
import com.example.fanout.fakes.ThrowingCheckpointStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Scenario 7 (quickstart), spec FR-041/FR-051/FR-052, SC-009, B8,
 * contracts/backfill-dlq-identity.md: a permanently poisoned engagement
 * exhausts its bounded retries, lands in the dead-letter store as a
 * {@code BACKFILL_ENGAGEMENT} unit carrying only work identity, still lets
 * the batch checkpoint, and converges after replay — same row set, every row
 * created exactly once, no duplicate records.
 *
 * <p>Deterministic: seeded dataset, injected clock backoff, no sleeps.
 */
class BackfillDeadLetterTest {

    private final BackfillFixture fixture = new BackfillFixture();

    @AfterEach
    void backfillInvariants() {
        assertThat(fixture.metrics.countersWithin(
                RecordingFanOutMetrics.BACKFILL_COUNTER_ALLOWLIST))
                .isTrue();
        assertThat(fixture.budget.available()).isEqualTo(fixture.config.bBackfill());
    }

    @Test
    void poisonedEngagementDeadLettersWithWorkIdentityAndReplayConverges() {
        fixture.seed(3L, 1, 10);
        fixture.seedEffectiveForAllProducts(BackfillFixture.EFFECTIVE_VERSION);
        String firmId = fixture.firmIds.getFirst();
        String productId = "p3-0000";
        String poison = fixture.enumerator.dataset(firmId).get(2).engagementId();
        fixture.downstream.failAlwaysFor(poison);

        BackfillOutcome first = fixture.newWorker().run();

        assertThat(first.status()).isEqualTo(BackfillOutcome.BackfillStatus.COMPLETED);
        assertThat(first.deadLettered()).isEqualTo(1);
        assertThat(first.rowsWritten()).isEqualTo(9);
        assertThat(first.engagementsResolved()).isEqualTo(10);

        assertThat(fixture.deadLetters.count()).isEqualTo(1);
        var record = fixture.deadLetters.all().getFirst();
        var unit = BackfillDeadLetterIdentity.decode(record.eventId());
        assertThat(unit.firmId()).isEqualTo(firmId);
        assertThat(unit.batchIndex()).isZero();
        assertThat(unit.engagementId()).contains(poison);
        assertThat(record.eventId())
                .isEqualTo(BackfillDeadLetterIdentity.engagement(firmId, 0, poison));
        assertThat(record.productId()).isEqualTo(productId);
        assertThat(record.chunkIndex()).isZero();
        assertThat(record.unitKind()).isEqualTo(DeadLetterUnitKind.BACKFILL_ENGAGEMENT);
        assertThat(record.attemptCount())
                .isEqualTo(fixture.config.retryPolicy().maxAttempts());
        assertThat(record.lastFailureType()).isEqualTo(TransientFanOutException.class.getName());
        assertThat(record.lastFailureMessage())
                .as("identifier-only failure message, never engagement content")
                .isEqualTo("injected downstream failure for " + poison);
        assertThat(record.recordedAt()).isEqualTo(fixture.clock.instant());
        assertThat(fixture.deadLetters.byEvent(record.eventId())).hasSize(1);

        assertThat(fixture.metrics.total("fanout.dlq",
                "unit_kind", DeadLetterUnitKind.BACKFILL_ENGAGEMENT.name())).isEqualTo(1L);
        assertThat(fixture.metrics.total("backfill.engagements.dlq")).isEqualTo(1L);

        assertThat(fixture.index.rowCount()).isEqualTo(9);
        assertThat(fixture.index.createCount(poison))
                .as("a dead-lettered engagement writes no row")
                .isZero();
        assertThat(fixture.cursors.read(firmId).orElseThrow().status())
                .as("the batch still checkpoints: later engagements resolved (FR-041)")
                .isEqualTo(BackfillCursorRepository.Status.COMPLETE);

        fixture.resetCursor(firmId);
        fixture.downstream.clearFailures();

        BackfillOutcome replayed = fixture.newWorker().run();

        assertThat(replayed.status()).isEqualTo(BackfillOutcome.BackfillStatus.COMPLETED);
        assertThat(replayed.rowsWritten()).isEqualTo(10);
        assertThat(replayed.deadLettered()).isZero();
        assertThat(fixture.index.rowCount()).isEqualTo(10);
        assertThat(fixture.index.createdTotal())
                .as("replay converges: every row created exactly once (SC-009/FR-052)")
                .isEqualTo(10);
        assertThat(fixture.index.createCount(poison)).isEqualTo(1);
        assertThat(fixture.index.find(poison, BackfillFixture.EFFECTIVE_VERSION,
                PendingIndexState.PENDING)).isPresent();
        assertThat(fixture.deadLetters.count())
                .as("replay adds no new dead letters")
                .isEqualTo(1);
    }

    @Test
    void failedCursorAdvanceWriteDeadLettersAsBackfillBatchAndKeepsProgressTruthful() {
        fixture.seed(5L, 2, 10);
        fixture.seedEffectiveForAllProducts(BackfillFixture.EFFECTIVE_VERSION);

        var flakyStore = new ThrowingCheckpointStore(fixture.backing);
        flakyStore.failAfterWrites(2);
        fixture.store = flakyStore;

        BackfillOutcome outcome = fixture.newWorker().run();

        assertThat(outcome.status())
                .as("an exhausted cursor unit dead-letters and fails the run loudly")
                .isEqualTo(BackfillOutcome.BackfillStatus.FAILED_DEAD_LETTERED);
        assertThat(outcome.deadLettered()).isEqualTo(1);

        var record = fixture.deadLetters.all().stream()
                .filter(r -> r.unitKind() == DeadLetterUnitKind.BACKFILL_CURSOR)
                .findFirst()
                .orElseThrow();
        var unit = BackfillDeadLetterIdentity.decode(record.eventId());
        assertThat(fixture.firmIds).contains(unit.firmId());
        assertThat(unit.batchIndex()).isEqualTo(-1);
        assertThat(unit.engagementId()).isEmpty();
        assertThat(record.eventId())
                .as("cursor units encode bf US firmId with chunkIndex -1 (D4)")
                .isEqualTo(BackfillDeadLetterIdentity.cursor(unit.firmId()));
        assertThat(record.productId()).isEqualTo(BackfillDeadLetterIdentity.UNKNOWN_PRODUCT);
        assertThat(record.chunkIndex()).isEqualTo(-1);
        assertThat(record.attemptCount())
                .isEqualTo(fixture.config.retryPolicy().maxAttempts());
        assertThat(record.lastFailureType()).isEqualTo(TransientFanOutException.class.getName());
        assertThat(fixture.metrics.total("fanout.dlq",
                "unit_kind", DeadLetterUnitKind.BACKFILL_CURSOR.name())).isEqualTo(1L);

        assertThat(fixture.cursors.read(unit.firmId()).orElseThrow().status())
                .as("the failing cursor value never claimed progress it did not make")
                .isEqualTo(BackfillCursorRepository.Status.RUNNING);
        assertThat(fixture.index.createdTotal())
                .as("every engagement of both batches was already written")
                .isEqualTo(20);
        assertThat(flakyStore.writes()).isEqualTo(2);
    }

}
