package com.example.fanout.fanout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.example.fanout.domain.EngagementRef;
import com.example.fanout.domain.FanOutConfig;
import com.example.fanout.domain.FanOutOutcomeStatus;
import com.example.fanout.domain.FanOutRunOutcome;
import com.example.fanout.domain.FanOutRunKind;
import com.example.fanout.domain.FanOutRunStatus;
import com.example.fanout.fakes.FakeFeatureFlags;
import com.example.fanout.fakes.TestTaskRunner;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * User Story 6, fan-out side (spec SC-007, FR-041-FR-044, T039): flipping one
 * firm's flag off mid-run stops every subsequent admission decision for that
 * firm while the other firms finish — no failure, no dead-letter, no buffered
 * work — and flipping it back on lets the same trigger resume from the
 * persisted publish cursor to an exact-once finish.
 *
 * <p>Admission is a point-in-time observation at the pick (research.md D8:
 * the gate stores nothing). The flip therefore lands after chunk 4's decision
 * but before chunk 5's: chunk 4 was admitted while the flag was on and drains,
 * everything after the flip rejects. Determinism (FR-072): hand-off runner,
 * jobs pumped one at a time, chunk 4's pick happens on the run thread within
 * the first loop iterations while the flip waits for chunk 0's 50 loads to
 * execute — no sleeps anywhere. The publish is built so every chunk maps to
 * exactly one firm (chunkSize divides the per-firm engagement count).
 */
class FanOutEngineFlagFlipTest {

    private static final int FIRMS = 4;
    private static final int PER_FIRM = 100;
    private static final int CHUNK_SIZE = 50;
    private static final int TOTAL = FIRMS * PER_FIRM;
    private static final String GATED_FIRM = "firm-0002";

    private static final FanOutConfig CONFIG = new FanOutConfig(
            CHUNK_SIZE, 2, 2, 5,
            Duration.ofMillis(100), Duration.ofSeconds(30), 0.5, 1.5);

    @Test
    void flippingOneFirmFlagGatesOnlyThatFirmAndResumingFinishesExactlyOnce()
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
        }, "fanout-flag-flip-run");
        run.start();

        // let real progress land (the first chunk is durable), then flip
        while (!prefixSeen(fixture, 0)) {
            Runnable job = awaitJob(handOff, run);
            if (job == null) {
                fail("run ended before any chunk completed; error=" + error.get());
                return;
            }
            job.run();
        }
        flags.setEnabledFirm(GATED_FIRM, false);

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

        // gated, not failed: other firms finished, the gated firm stopped at
        // the first admission decision after the flip
        FanOutRunOutcome gated = outcome.get();
        assertThat(gated.status()).isEqualTo(FanOutOutcomeStatus.GATED);
        assertThat(gated.deadLetterCount()).isZero();
        assertThat(fixture.deadLetters.count()).isZero();
        assertThat(fixture.metrics.saw("fanout.dlq")).isFalse();
        assertThat(fixture.metrics.saw("fanout.admit.reject.firm")).isTrue();
        for (EngagementRef ref : refs) {
            boolean inAdmittedChunk = inFirstFirmChunks(ref);
            assertThat(fixture.index.createCount(ref.engagementId()))
                    .as("%s: admitted chunk drains, everything after the flip stays out",
                            ref.engagementId())
                    .isEqualTo(inAdmittedChunk ? 1 : 0);
        }
        assertThat(fixture.index.rowCount())
                .as("7 of 8 chunks ran: everything except the gated firm's post-flip chunk")
                .isEqualTo((TOTAL / CHUNK_SIZE - 1) * CHUNK_SIZE);
        var cursor = fixture.cursors.readRun(
                        FanOutRunKind.PUBLISH,
                        FanOutFixture.PUBLISH, FanOutFixture.PRODUCT)
                .orElseThrow(() -> new AssertionError("publish cursor missing"));
        assertThat(cursor.status()).isEqualTo(FanOutRunStatus.RUNNING);
        assertThat(cursor.lastCompletedChunkIndex())
                .as("prefix holds at 4: chunks 0-4 complete, 5 gated, 6-7 out of order")
                .isEqualTo(4);

        // flip back on: the same trigger resumes from the persisted cursor
        flags.setEnabledFirm(GATED_FIRM, true);
        fixture.runner = new TestTaskRunner(CONFIG.poolBound());
        FanOutEngine resumedEngine = fixture.newEngine(new AdmissionGate(flags, fixture.metrics));
        FanOutRunOutcome resumed = resumedEngine.runPublish(trigger);

        assertThat(resumed.status()).isEqualTo(FanOutOutcomeStatus.COMPLETED);
        assertThat(fixture.index.rowCount()).isEqualTo(TOTAL);
        assertThat(fixture.index.createdTotal())
                .as("resume re-derives only the gated gap (SC-007)")
                .isEqualTo(TOTAL);
        for (EngagementRef ref : refs) {
            assertThat(fixture.index.createCount(ref.engagementId()))
                    .as("exact-once for " + ref.engagementId())
                    .isEqualTo(1);
        }
        assertThat(fixture.cursors.readRun(
                        FanOutRunKind.PUBLISH,
                        FanOutFixture.PUBLISH, FanOutFixture.PRODUCT)
                .orElseThrow().status())
                .isEqualTo(FanOutRunStatus.COMPLETE);
        assertThat(fixture.deadLetters.count()).isZero();
    }

    /** True for every engagement outside the gated firm's post-flip chunk. */
    private static boolean inFirstFirmChunks(EngagementRef ref) {
        if (!GATED_FIRM.equals(ref.firmId())) {
            return true; // ungated firms ran all of their chunks
        }
        int offsetInFirm = Integer.parseInt(
                ref.engagementId().substring(ref.engagementId().lastIndexOf('-') + 1));
        // the gated firm's chunks are global 4 (offsets 0-49, admitted) and 5
        // (offsets 50-99, rejected at the first decision after the flip)
        return offsetInFirm / CHUNK_SIZE == 0;
    }

    private static boolean prefixSeen(FanOutFixture fixture, int minIndex) {
        return fixture.cursors.readRun(
                        FanOutRunKind.PUBLISH,
                        FanOutFixture.PUBLISH, FanOutFixture.PRODUCT)
                .filter(cursor -> cursor.lastCompletedChunkIndex() >= minIndex)
                .isPresent();
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
