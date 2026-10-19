package com.example.fanout.fanout;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.fanout.domain.EngagementRef;
import com.example.fanout.domain.FanOutOutcomeStatus;
import com.example.fanout.domain.FanOutRunKind;
import com.example.fanout.domain.FanOutRunOutcome;
import com.example.fanout.domain.FanOutRunStatus;
import com.example.fanout.domain.PendingIndexState;
import com.example.fanout.fakes.TestTaskRunner;
import com.example.fanout.fakes.TestTaskRunner.Mode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * US2 fairness simulation (spec SC-005, FR-011/FR-012): one 40,000-engagement
 * firm plus 500 median-40 firms in a single publish. Observed at every pumped
 * chunk completion: the pool ceiling holds, and at some observation all
 * median-firm rows are present while the giant firm's rows are still
 * incomplete - the giant backlog never starves median firms. Final state:
 * every row exactly once, cursor COMPLETE, downstream call log empty
 * (spec FR-032). No sleeps anywhere (spec FR-072): the run is driven by the
 * test-controlled runner on a pumped hand-off queue.
 */
class LargePublishSimulationTest {

    private static final int GIANT_ROWS = 40_000;
    private static final int MEDIAN_FIRMS = 500;
    private static final int MEDIAN_ROWS = 40;
    private static final int CHUNK_SIZE = 500;
    private static final String GIANT_FIRM = "firm-0000";

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
    void skewedPublishCapsGiantFirmAndCompletesMedianFirmsFirst() throws Exception {
        fixture.runner = new TestTaskRunner(fixture.config.poolBound(), Mode.HAND_OFF);

        List<EngagementRef> giantRefs = fixture.eligibility.seedEligible(
                FanOutFixture.PRODUCT, FanOutFixture.PUBLISH, GIANT_FIRM, GIANT_ROWS);
        List<EngagementRef> medianRefs = new ArrayList<>(MEDIAN_FIRMS * MEDIAN_ROWS);
        for (int i = 1; i <= MEDIAN_FIRMS; i++) {
            medianRefs.addAll(fixture.eligibility.seedEligible(
                    FanOutFixture.PRODUCT, FanOutFixture.PUBLISH,
                    String.format("firm-%04d", 1000 + i), MEDIAN_ROWS));
        }
        int totalRows = GIANT_ROWS + MEDIAN_FIRMS * MEDIAN_ROWS;
        int totalChunks = totalRows / CHUNK_SIZE;
        fixture.seedPublish(FanOutFixture.PUBLISH, FanOutFixture.TO_VERSION);

        int poolBound = fixture.config.poolBound();
        AtomicReference<FanOutRunOutcome> outcome = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean done = new AtomicBoolean();
        AtomicBoolean mediansCompleteWhileGiantPending = new AtomicBoolean();

        var engine = fixture.newEngine();
        var trigger = fixture.publishTrigger(FanOutFixture.PUBLISH);
        Thread worker = new Thread(() -> {
            try {
                outcome.set(engine.runPublish(trigger));
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.set(true);
            }
        }, "large-publish-worker");
        worker.start();

        // pump handed chunks on this thread; every completion is an observation
        // point for the ceiling invariant and the fairness ordering
        long spins = 0;
        while (!done.get() || fixture.runner.handedCount() > 0) {
            Runnable task = fixture.runner.poll();
            if (task == null) {
                if (++spins > 2_000_000_000L) {
                    worker.interrupt();
                    throw new AssertionError("large publish stalled");
                }
                Thread.onSpinWait();
                continue;
            }
            spins = 0;
            task.run();

            assertThat(fixture.runner.inFlight()).isLessThanOrEqualTo(poolBound);
            assertThat(fixture.runner.maxInFlight()).isLessThanOrEqualTo(poolBound);

            if (!mediansCompleteWhileGiantPending.get()
                    && allPresent(medianRefs, fixture)
                    && countPresent(giantRefs, fixture) < GIANT_ROWS) {
                mediansCompleteWhileGiantPending.set(true);
            }
        }
        worker.join(120_000L);
        assertThat(worker.isAlive()).isFalse();

        assertThat(failure.get()).isNull();
        assertThat(outcome.get().status()).isEqualTo(FanOutOutcomeStatus.COMPLETED);
        assertThat(outcome.get().chunkCount()).isEqualTo(totalChunks);
        assertThat(outcome.get().rowsResolved()).isEqualTo(totalRows);
        assertThat(outcome.get().deadLetterCount()).isZero();

        // fairness verdict: every median-firm row was present at an
        // observation where the giant firm still had pending rows
        assertThat(mediansCompleteWhileGiantPending.get())
                .as("all median rows present while the giant firm still incomplete (SC-005)")
                .isTrue();

        // exact row set: all giant rows present exactly once at the expected identity
        assertThat(countPresent(giantRefs, fixture)).isEqualTo(GIANT_ROWS);
        for (EngagementRef ref : giantRefs) {
            var row = fixture.index.find(ref.engagementId(), FanOutFixture.TO_VERSION,
                    PendingIndexState.PENDING);
            assertThat(row).isPresent();
            assertThat(row.get().firmId()).isEqualTo(GIANT_FIRM);
        }
        assertThat(fixture.writer.writeCount()).isEqualTo(totalRows);

        var cursor = fixture.cursors
                .readRun(FanOutRunKind.PUBLISH, FanOutFixture.PUBLISH, FanOutFixture.PRODUCT)
                .orElseThrow();
        assertThat(cursor.status()).isEqualTo(FanOutRunStatus.COMPLETE);
        assertThat(cursor.totalChunks()).isEqualTo(totalChunks);
        assertThat(cursor.lastCompletedChunkIndex()).isEqualTo(totalChunks - 1);

        assertThat(fixture.downstream.getCallCount()).isZero();
        assertThat(fixture.metrics.saw("fanout.backpressure")).isFalse();
        assertThat(fixture.metrics.total("fanout.duplicate.run")).isZero();
        assertThat(fixture.runner.maxInFlight()).isLessThanOrEqualTo(poolBound);
    }

    private static boolean allPresent(List<EngagementRef> refs, FanOutFixture fixture) {
        return countPresent(refs, fixture) == refs.size();
    }

    private static int countPresent(List<EngagementRef> refs, FanOutFixture fixture) {
        int count = 0;
        for (EngagementRef ref : refs) {
            if (fixture.index.find(ref.engagementId(), FanOutFixture.TO_VERSION,
                    PendingIndexState.PENDING).isPresent()) {
                count++;
            }
        }
        return count;
    }
}
