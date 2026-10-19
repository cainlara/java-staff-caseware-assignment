package com.example.fanout.fanout;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.fanout.domain.FanOutOutcomeStatus;
import com.example.fanout.domain.FanOutRunKind;
import com.example.fanout.domain.FanOutRunStatus;
import com.example.fanout.domain.PendingIndexRow;
import com.example.fanout.domain.PendingIndexState;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * US1 idempotency (spec SC-001/SC-004, FR-021/FR-052/FR-053):
 * replay converges with zero writes, rewound-cursor re-execution changes no
 * row, concurrent duplicate executions equal single execution, and the CAS
 * stale-rejection cycle preserves recorded decision history.
 */
class FanOutEngineIdempotencyTest {

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
    void replayOfCompletedPublishSkipsDuplicateWithUnchangedRows() {
        var refs = fixture.seedEligible(25, 40);
        fixture.seedPublish(FanOutFixture.PUBLISH, FanOutFixture.TO_VERSION);
        var engine = fixture.newEngine();

        var first = engine.runPublish(fixture.publishTrigger(FanOutFixture.PUBLISH));
        assertThat(first.status()).isEqualTo(FanOutOutcomeStatus.COMPLETED);
        Map<String, PendingIndexRow> snapshot = snapshot(refs);
        int writesAfterFirstRun = fixture.writer.writeCount();

        var second = engine.runPublish(fixture.publishTrigger(FanOutFixture.PUBLISH));
        assertThat(second.status()).isEqualTo(FanOutOutcomeStatus.SKIPPED_DUPLICATE);
        assertThat(second.chunkCount()).isEqualTo(2);
        assertThat(second.rowsResolved()).isZero();
        assertThat(second.deadLetterCount()).isZero();

        assertThat(snapshot(refs)).isEqualTo(snapshot);
        assertThat(fixture.writer.writeCount()).isEqualTo(writesAfterFirstRun);
        assertThat(fixture.metrics.total("fanout.duplicate.run")).isGreaterThanOrEqualTo(1);
        assertThat(fixture.downstream.getCallCount()).isZero();
    }

    @Test
    void rewoundCursorReexecutionYieldsIdenticalRowsAndSingleCursorAdvance() {
        var refs = fixture.seedEligible(25, 40);
        fixture.seedPublish(FanOutFixture.PUBLISH, FanOutFixture.TO_VERSION);

        var firstEngine = fixture.newEngine();
        assertThat(firstEngine.runPublish(fixture.publishTrigger(FanOutFixture.PUBLISH)).status())
                .isEqualTo(FanOutOutcomeStatus.COMPLETED);
        Map<String, PendingIndexRow> snapshot = snapshot(refs);

        // simulate: all writes done, cursor never advanced (crash before advance)
        fixture.cursors.advance(FanOutRunKind.PUBLISH, FanOutFixture.PUBLISH, FanOutFixture.PRODUCT, 2, -1);

        var resumed = fixture.newEngine().runPublish(fixture.publishTrigger(FanOutFixture.PUBLISH));
        assertThat(resumed.status()).isEqualTo(FanOutOutcomeStatus.COMPLETED);
        assertThat(resumed.rowsResolved()).isEqualTo(1_000);

        assertThat(snapshot(refs)).isEqualTo(snapshot);
        for (var row : snapshot(refs).values()) {
            assertThat(row.rowVersion()).isEqualTo(1);
        }
        var cursor = fixture.cursors
                .readRun(FanOutRunKind.PUBLISH, FanOutFixture.PUBLISH, FanOutFixture.PRODUCT)
                .orElseThrow();
        assertThat(cursor.status()).isEqualTo(FanOutRunStatus.COMPLETE);
        assertThat(cursor.lastCompletedChunkIndex()).isEqualTo(1);
        assertThat(fixture.downstream.getCallCount()).isZero();
    }

    @Test
    void concurrentDuplicateExecutionsConvergeToSingleExecutionRowSet() throws Exception {
        var refs = fixture.seedEligible(25, 40);
        fixture.seedPublish(FanOutFixture.PUBLISH, FanOutFixture.TO_VERSION);

        var engineA = fixture.newEngine();
        var engineB = fixture.newSecondInstanceEngine();
        var trigger = fixture.publishTrigger(FanOutFixture.PUBLISH);
        var startGate = new CountDownLatch(1);
        var failures = new AtomicReference<Throwable>();
        var done = new CountDownLatch(2);

        Runnable runA = () -> {
            try {
                startGate.await();
                engineA.runPublish(trigger);
            } catch (Throwable t) {
                failures.set(t);
            } finally {
                done.countDown();
            }
        };
        Runnable runB = () -> {
            try {
                startGate.await();
                engineB.runPublish(trigger);
            } catch (Throwable t) {
                failures.set(t);
            } finally {
                done.countDown();
            }
        };
        new Thread(runA, "engine-a").start();
        new Thread(runB, "engine-b").start();
        startGate.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(failures.get()).isNull();

        // row set equals single execution: exactly one row per engagement, never bumped
        for (var ref : refs) {
            var row = fixture.index.find(ref.engagementId(), FanOutFixture.TO_VERSION, PendingIndexState.PENDING);
            assertThat(row).isPresent();
            assertThat(row.get().rowVersion()).isEqualTo(1);
            assertThat(row.get().publishIds()).containsExactly(FanOutFixture.PUBLISH);
        }
        var cursor = fixture.cursors
                .readRun(FanOutRunKind.PUBLISH, FanOutFixture.PUBLISH, FanOutFixture.PRODUCT)
                .orElseThrow();
        assertThat(cursor.status()).isEqualTo(FanOutRunStatus.COMPLETE);
        assertThat(cursor.lastCompletedChunkIndex()).isEqualTo(1);
        assertThat(fixture.downstream.getCallCount()).isZero();
    }

    @Test
    void staleRejectionReapplyPreservesRecordedDecisionHistory() {
        var ref = FanOutFixture.ref(0, 0);
        fixture.eligibility.seedEligible(
                FanOutFixture.PRODUCT, FanOutFixture.PUBLISH, List.of(ref));
        fixture.seedPublish(FanOutFixture.PUBLISH, FanOutFixture.TO_VERSION);

        // existing offer row carrying recorded history at rowVersion 2
        fixture.index.upsert(new PendingIndexRow(
                ref.engagementId(), ref.firmId(), ref.productId(), ref.currentVersion(),
                FanOutFixture.TO_VERSION, PendingIndexState.PENDING,
                null, List.of(), null, Set.of("pub-seed"), 1L, fixture.clock.instant()));
        fixture.index.upsert(new PendingIndexRow(
                ref.engagementId(), ref.firmId(), ref.productId(), ref.currentVersion(),
                FanOutFixture.TO_VERSION, PendingIndexState.PENDING,
                "declined", List.of("r1"), "sum-9", Set.of("pub-seed"), 1L, fixture.clock.instant()));
        assertThat(fixture.index.stored(ref.engagementId(), FanOutFixture.TO_VERSION,
                PendingIndexState.PENDING).rowVersion()).isEqualTo(2);

        var outcome = fixture.newEngine().runPublish(fixture.publishTrigger(FanOutFixture.PUBLISH));

        assertThat(outcome.status()).isEqualTo(FanOutOutcomeStatus.COMPLETED);
        assertThat(outcome.rowsResolved()).isEqualTo(1);
        var stored = fixture.index.stored(ref.engagementId(), FanOutFixture.TO_VERSION,
                PendingIndexState.PENDING);
        assertThat(stored.rowVersion()).isEqualTo(3);
        assertThat(stored.decision()).isEqualTo("declined");
        assertThat(stored.pinnedRanges()).containsExactly("r1");
        assertThat(stored.summaryKey()).isEqualTo("sum-9");
        assertThat(stored.publishIds()).containsExactlyInAnyOrder("pub-seed", FanOutFixture.PUBLISH);
        assertThat(fixture.metrics.total("fanout.cas.reapply")).isEqualTo(1);
        assertThat(fixture.downstream.getCallCount()).isZero();
    }

    private Map<String, PendingIndexRow> snapshot(List<com.example.fanout.domain.EngagementRef> refs) {
        Map<String, PendingIndexRow> rows = new HashMap<>();
        for (var ref : refs) {
            rows.put(ref.engagementId(), fixture.index
                    .find(ref.engagementId(), FanOutFixture.TO_VERSION, PendingIndexState.PENDING)
                    .orElseThrow());
        }
        return rows;
    }
}
