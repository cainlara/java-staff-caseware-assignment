package com.example.fanout.fanout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.example.fanout.domain.FanOutOutcomeStatus;
import com.example.fanout.domain.FanOutRunKind;
import com.example.fanout.domain.FanOutRunStatus;
import com.example.fanout.domain.PendingIndexState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * US1 hot path (spec FR-001/FR-004/FR-032, SC-001/SC-010):
 * publish fans out to pending rows from metadata only — downstream call log
 * empty in every test, zero-eligible completes empty, ~20k publish inside the
 * 60 s test-scale budget.
 */
class FanOutEnginePublishTest {

    private FanOutFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new FanOutFixture();
    }

    @AfterEach
    void hotPathInvariants() {
        fixture.assertHotPathInvariants();
    }

    @Test
    void publishWritesOnePendingRowPerEligibleEngagement() {
        var refs = fixture.seedEligible(25, 40);
        fixture.seedPublish(FanOutFixture.PUBLISH, FanOutFixture.TO_VERSION);

        var outcome = fixture.newEngine().runPublish(fixture.publishTrigger(FanOutFixture.PUBLISH));

        assertThat(outcome.status()).isEqualTo(FanOutOutcomeStatus.COMPLETED);
        assertThat(outcome.eventId()).isEqualTo(FanOutFixture.PUBLISH);
        assertThat(outcome.chunkCount()).isEqualTo(2);
        assertThat(outcome.rowsResolved()).isEqualTo(1_000);
        assertThat(outcome.deadLetterCount()).isZero();

        assertThat(refs).hasSize(1_000);
        for (var ref : refs) {
            var row = fixture.index.find(ref.engagementId(), FanOutFixture.TO_VERSION, PendingIndexState.PENDING);
            assertThat(row).isPresent();
            assertThat(row.get().offeredVersion()).isEqualTo(FanOutFixture.TO_VERSION);
            assertThat(row.get().publishIds()).containsExactly(FanOutFixture.PUBLISH);
            assertThat(row.get().rowVersion()).isEqualTo(1);
            assertThat(row.get().state()).isEqualTo(PendingIndexState.PENDING);
        }

        var cursor = fixture.cursors
                .readRun(FanOutRunKind.PUBLISH, FanOutFixture.PUBLISH, FanOutFixture.PRODUCT)
                .orElseThrow();
        assertThat(cursor.status()).isEqualTo(FanOutRunStatus.COMPLETE);
        assertThat(cursor.totalChunks()).isEqualTo(2);
        assertThat(cursor.lastCompletedChunkIndex()).isEqualTo(1);

        assertThat(fixture.metrics.total("fanout.chunks.started")).isEqualTo(2);
        assertThat(fixture.metrics.total("fanout.chunks.completed")).isEqualTo(2);
        assertThat(fixture.metrics.total("fanout.rows.resolved")).isEqualTo(1_000);
        assertThat(fixture.metrics.saw("fanout.publish_to_pending")).isTrue();
        assertThat(fixture.metrics.saw("fanout.backpressure")).isFalse();
        assertThat(fixture.metrics.saw("fanout.downstream.inflight.hwm")).isFalse();
        assertThat(fixture.metrics.saw("fanout.retry")).isFalse();
        // metric allowlist (spec FR-071): identifier/measurement names only;
        // backpressure/inflight-hwm excluded - they never fire on the hot path
        assertThat(fixture.metrics.counterNames()).isSubsetOf(FanOutFixture.HOT_PATH_COUNTER_ALLOWLIST);

        assertThat(fixture.downstream.getCallCount()).isZero();
    }

    @Test
    void zeroEligiblePublishCompletesEmptyWithZeroWritesAndZeroDownstreamCalls() {
        fixture.seedPublish(FanOutFixture.PUBLISH, FanOutFixture.TO_VERSION);

        var outcome = fixture.newEngine().runPublish(fixture.publishTrigger(FanOutFixture.PUBLISH));

        assertThat(outcome.status()).isEqualTo(FanOutOutcomeStatus.COMPLETED_EMPTY);
        assertThat(outcome.chunkCount()).isZero();
        assertThat(outcome.rowsResolved()).isZero();
        assertThat(outcome.deadLetterCount()).isZero();

        assertThat(fixture.writer.writeCount()).isZero();

        var cursor = fixture.cursors
                .readRun(FanOutRunKind.PUBLISH, FanOutFixture.PUBLISH, FanOutFixture.PRODUCT)
                .orElseThrow();
        assertThat(cursor.status()).isEqualTo(FanOutRunStatus.COMPLETE);
        assertThat(cursor.totalChunks()).isZero();
        assertThat(cursor.lastCompletedChunkIndex()).isEqualTo(-1);

        assertThat(fixture.metrics.saw("fanout.publish_to_pending")).isTrue();
        assertThat(fixture.downstream.getCallCount()).isZero();
    }

    @Test
    void typicalTwentyThousandEngagementPublishCompletesInsideSixtySecondBudget() {
        fixture.seedEligible(400, 50);
        fixture.seedPublish(FanOutFixture.PUBLISH, FanOutFixture.TO_VERSION);

        long start = System.nanoTime();
        var outcome = fixture.newEngine().runPublish(fixture.publishTrigger(FanOutFixture.PUBLISH));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

        assertThat(outcome.status()).isEqualTo(FanOutOutcomeStatus.COMPLETED);
        assertThat(outcome.rowsResolved()).isEqualTo(20_000);
        assertThat(outcome.chunkCount()).isEqualTo(40);
        assertThat(elapsedMillis).isLessThan(60_000L);
        assertThat(fixture.downstream.getCallCount()).isZero();
        assertThat(fixture.metrics.saw("fanout.backpressure")).isFalse();
    }

    @Test
    void wrongTriggerKindForEntryPointRejected() {
        fixture.seedPublish(FanOutFixture.PUBLISH, FanOutFixture.TO_VERSION);
        var engine = fixture.newEngine();
        var withdrawLike = fixture.withdrawTrigger("wd-1", FanOutFixture.PUBLISH);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> engine.runPublish(withdrawLike))
                .withMessageContaining("kind");
        assertThat(fixture.downstream.getCallCount()).isZero();
    }
}
