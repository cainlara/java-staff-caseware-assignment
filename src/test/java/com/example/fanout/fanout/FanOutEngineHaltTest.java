package com.example.fanout.fanout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.example.fanout.domain.EngagementRef;
import com.example.fanout.domain.FanOutConfig;
import com.example.fanout.domain.FanOutCursor;
import com.example.fanout.domain.FanOutOutcomeStatus;
import com.example.fanout.domain.FanOutRunKind;
import com.example.fanout.domain.FanOutRunStatus;
import com.example.fanout.domain.FanOutRunOutcome;
import com.example.fanout.fakes.FakeFeatureFlags;
import com.example.fanout.fakes.TestTaskRunner;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * User Story 3, fan-out halt protocol (spec US3 acceptance 5 / SC-002, FR-030,
 * FR-042, T029): a halt switch observed while chunks remain pending returns
 * outcome {@code HALTED}, the cursor persists the completed prefix, no trigger
 * is dropped, and clearing the halt lets the same trigger finish.
 *
 * <p>Determinism: hand-off runner, jobs pumped one at a time by the test; the
 * halt switch flips between pumps — no sleeps anywhere (FR-072).
 */
class FanOutEngineHaltTest {

    private static final int FIRMS = 4;
    private static final int PER_FIRM = 60;
    private static final int TOTAL = FIRMS * PER_FIRM;
    private static final int CHUNK_SIZE = 25;
    private static final int TOTAL_CHUNKS = (TOTAL + CHUNK_SIZE - 1) / CHUNK_SIZE;

    private static final FanOutConfig CONFIG = new FanOutConfig(
            CHUNK_SIZE, 16, 2, 5,
            Duration.ofMillis(100), Duration.ofSeconds(30), 0.5, 1.5);

    @Test
    void haltMidPublishKeepsThePrefixAndResumeCompletesWithoutDroppingTheTrigger()
            throws Exception {
        FanOutFixture fixture = new FanOutFixture(CONFIG);
        fixture.seedPublish(FanOutFixture.PUBLISH, FanOutFixture.TO_VERSION);
        List<EngagementRef> refs = fixture.seedEligible(FIRMS, PER_FIRM);
        FakeFeatureFlags flags = new FakeFeatureFlags();
        flags.setEnabled(FanOutFixture.PRODUCT, true);
        refs.stream().map(EngagementRef::firmId).distinct()
                .forEach(firm -> flags.setEnabledFirm(firm, true));
        TestTaskRunner handOff = new TestTaskRunner(CONFIG.poolBound(),
                TestTaskRunner.Mode.HAND_OFF);
        fixture.runner = handOff;
        FanOutEngine engine = fixture.newEngine(new AdmissionGate(flags, fixture.metrics));
        var trigger = fixture.publishTrigger(FanOutFixture.PUBLISH);

        AtomicReference<FanOutRunOutcome> outcome = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread run = new Thread(() -> {
            try {
                outcome.set(engine.runPublish(trigger));
            } catch (Throwable failure) {
                error.set(failure);
            }
        }, "fanout-halt-run");
        run.start();

        // pump chunks until the first prefix advance is durable
        while (prefixPersisted(fixture).isEmpty()
                || prefixPersisted(fixture).orElseThrow().lastCompletedChunkIndex() < 0) {
            Runnable job = awaitJob(handOff, run);
            if (job == null) {
                fail("run ended before any chunk completed; error=" + error.get());
                return;
            }
            job.run();
        }
        int prefixAtHalt = prefixPersisted(fixture).orElseThrow().lastCompletedChunkIndex();

        flags.setHalt(true);
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

        // halted with pending chunks: prefix held, no trigger dropped
        FanOutRunOutcome halted = outcome.get();
        assertThat(halted.status()).isEqualTo(FanOutOutcomeStatus.HALTED);
        assertThat(halted.chunkCount()).isEqualTo(TOTAL_CHUNKS);
        FanOutCursor cursor = prefixPersisted(fixture).orElseThrow();
        assertThat(cursor.status()).isNotEqualTo(FanOutRunStatus.COMPLETE);
        assertThat(cursor.lastCompletedChunkIndex())
                .as("the durable prefix never regresses (already-admitted chunks drain)")
                .isGreaterThanOrEqualTo(prefixAtHalt);
        assertThat(prefixAtHalt)
                .as("the halt landed with chunks still pending")
                .isLessThan(TOTAL_CHUNKS - 1);
        assertThat(fixture.metrics.saw("fanout.admit.reject.halt")).isTrue();
        assertThat(fixture.deadLetters.count())
                .as("a halt is not a failure: nothing dead-lettered")
                .isZero();

        // clear the halt, re-invoke the same trigger: it resumes, exact-once
        flags.setHalt(false);
        fixture.runner = new TestTaskRunner(CONFIG.poolBound());
        FanOutEngine resumedEngine = fixture.newEngine(new AdmissionGate(flags, fixture.metrics));
        FanOutRunOutcome resumed = resumedEngine.runPublish(trigger);

        assertThat(resumed.status()).isEqualTo(FanOutOutcomeStatus.COMPLETED);
        assertThat(resumed.chunkCount()).isEqualTo(TOTAL_CHUNKS);
        assertThat(prefixPersisted(fixture).orElseThrow().status())
                .isEqualTo(FanOutRunStatus.COMPLETE);
        assertThat(fixture.index.rowCount()).isEqualTo(TOTAL);
        assertThat(fixture.index.createdTotal())
                .as("prefix chunks are not re-executed on resume")
                .isEqualTo(TOTAL);
        for (EngagementRef ref : refs) {
            assertThat(fixture.index.createCount(ref.engagementId()))
                    .as("exact-once for " + ref.engagementId())
                    .isEqualTo(1);
        }
        assertThat(fixture.deadLetters.count()).isZero();
    }

    // ---- helpers ----

    private static Optional<FanOutCursor> prefixPersisted(FanOutFixture fixture) {
        return fixture.cursors.readRun(
                FanOutRunKind.PUBLISH, FanOutFixture.PUBLISH, FanOutFixture.PRODUCT);
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
