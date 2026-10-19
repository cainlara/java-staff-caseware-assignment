package com.example.fanout.fanout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.fanout.domain.DeadLetterUnitKind;
import com.example.fanout.domain.EngagementRef;
import com.example.fanout.domain.FanOutOutcomeStatus;
import com.example.fanout.domain.FanOutRunKind;
import com.example.fanout.domain.FanOutRunStatus;
import com.example.fanout.domain.FanOutRunOutcome;
import com.example.fanout.domain.PendingIndexState;
import com.example.fanout.fakes.FailingIndexWriter.SimulatedCrashException;
import com.example.fanout.fakes.TestTaskRunner;
import com.example.fanout.fakes.TestTaskRunner.Mode;
import com.example.fanout.ports.CheckpointKey;
import com.example.fanout.ports.CheckpointKind;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * US3 crash/resume (spec SC-003, FR-051/FR-052/FR-053): process death after
 * N chunks resumes at chunk N+1 without re-processing completed chunks; death
 * mid-chunk replays the chunk to an identical row set; concurrent duplicate
 * resumes converge to the single-execution row set; completed cursors record
 * chunk count and last completed chunk; corrupt cursor encoding dead-letters
 * loudly (CURSOR_WRITE + fanout.dlq), never silently restarting from zero.
 * Crash injection is scripted (FailingIndexWriter.SimulatedCrashException
 * escaping the engine); no sleeps anywhere (spec FR-072).
 */
class FanOutEngineCrashResumeTest {

    private static final char US = (char) 0x1F;

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
    void crashAfterFirstChunkResumesAtNextChunkWithoutReprocessingCompletedChunks() {
        List<EngagementRef> refs = fixture.seedEligible(25, 40);
        fixture.seedPublish(FanOutFixture.PUBLISH, FanOutFixture.TO_VERSION);

        // 501st write = first row of chunk 1: chunk 0 fully resolved first
        fixture.writer.dieAfterWrites(500);
        assertThatThrownBy(() -> fixture.newEngine()
                .runPublish(fixture.publishTrigger(FanOutFixture.PUBLISH)))
                .isInstanceOf(SimulatedCrashException.class);

        assertThat(fixture.writer.writeCount()).isEqualTo(501);
        var crashed = fixture.cursors
                .readRun(FanOutRunKind.PUBLISH, FanOutFixture.PUBLISH, FanOutFixture.PRODUCT)
                .orElseThrow();
        assertThat(crashed.status()).isEqualTo(FanOutRunStatus.RUNNING);
        assertThat(crashed.totalChunks()).isEqualTo(2);
        assertThat(crashed.lastCompletedChunkIndex()).isEqualTo(0);
        for (EngagementRef ref : refs.subList(0, 500)) {
            assertThat(fixture.index.find(ref.engagementId(), FanOutFixture.TO_VERSION,
                    PendingIndexState.PENDING)).isPresent();
        }
        assertThat(fixture.index.find(refs.get(500).engagementId(), FanOutFixture.TO_VERSION,
                PendingIndexState.PENDING)).isEmpty();

        fixture.writer.dieAfterWrites(Integer.MAX_VALUE - 1);
        var outcome = fixture.newEngine()
                .runPublish(fixture.publishTrigger(FanOutFixture.PUBLISH));

        assertThat(outcome.status()).isEqualTo(FanOutOutcomeStatus.COMPLETED);
        assertThat(outcome.chunkCount()).isEqualTo(2);
        assertThat(outcome.rowsResolved()).isEqualTo(500); // only chunk 1 re-ran
        // chunk 0 saw zero upsert calls on resume: 501 crashed + 500 chunk-1
        assertThat(fixture.writer.writeCount()).isEqualTo(1_001);
        for (EngagementRef ref : refs) {
            var row = fixture.index.find(ref.engagementId(), FanOutFixture.TO_VERSION,
                    PendingIndexState.PENDING);
            assertThat(row).isPresent();
            assertThat(row.get().rowVersion()).isEqualTo(1);
            assertThat(row.get().publishIds()).containsExactly(FanOutFixture.PUBLISH);
        }
        var resumed = fixture.cursors
                .readRun(FanOutRunKind.PUBLISH, FanOutFixture.PUBLISH, FanOutFixture.PRODUCT)
                .orElseThrow();
        assertThat(resumed.status()).isEqualTo(FanOutRunStatus.COMPLETE);
        assertThat(resumed.totalChunks()).isEqualTo(2);
        assertThat(resumed.lastCompletedChunkIndex()).isEqualTo(1);
        assertThat(fixture.downstream.getCallCount()).isZero();
    }

    @Test
    void crashMidChunkBeforeCursorAdvanceReplaysChunkToIdenticalRowSet() {
        List<EngagementRef> refs = fixture.seedEligible(25, 40);
        fixture.seedPublish(FanOutFixture.PUBLISH, FanOutFixture.TO_VERSION);

        fixture.writer.dieAfterWrites(250);
        assertThatThrownBy(() -> fixture.newEngine()
                .runPublish(fixture.publishTrigger(FanOutFixture.PUBLISH)))
                .isInstanceOf(SimulatedCrashException.class);
        assertThat(fixture.writer.writeCount()).isEqualTo(251);
        var crashed = fixture.cursors
                .readRun(FanOutRunKind.PUBLISH, FanOutFixture.PUBLISH, FanOutFixture.PRODUCT)
                .orElseThrow();
        assertThat(crashed.status()).isEqualTo(FanOutRunStatus.RUNNING);
        assertThat(crashed.lastCompletedChunkIndex()).isEqualTo(-1); // chunk never resolved

        fixture.writer.dieAfterWrites(Integer.MAX_VALUE - 1);
        var outcome = fixture.newEngine()
                .runPublish(fixture.publishTrigger(FanOutFixture.PUBLISH));
        assertThat(outcome.status()).isEqualTo(FanOutOutcomeStatus.COMPLETED);
        assertThat(outcome.rowsResolved()).isEqualTo(1_000);

        // identical to a fault-free run of the same inputs
        FanOutFixture reference = new FanOutFixture();
        reference.seedEligible(25, 40);
        reference.seedPublish(FanOutFixture.PUBLISH, FanOutFixture.TO_VERSION);
        assertThat(reference.newEngine()
                .runPublish(reference.publishTrigger(FanOutFixture.PUBLISH)).status())
                .isEqualTo(FanOutOutcomeStatus.COMPLETED);
        for (EngagementRef ref : refs) {
            var crashedRow = fixture.index.find(ref.engagementId(), FanOutFixture.TO_VERSION,
                    PendingIndexState.PENDING);
            var referenceRow = reference.index.find(ref.engagementId(),
                    FanOutFixture.TO_VERSION, PendingIndexState.PENDING);
            assertThat(crashedRow).isPresent();
            assertThat(crashedRow).isEqualTo(referenceRow);
            assertThat(crashedRow.get().rowVersion()).isEqualTo(1);
        }
        var resumed = fixture.cursors
                .readRun(FanOutRunKind.PUBLISH, FanOutFixture.PUBLISH, FanOutFixture.PRODUCT)
                .orElseThrow();
        assertThat(resumed.status()).isEqualTo(FanOutRunStatus.COMPLETE);
        assertThat(resumed.lastCompletedChunkIndex()).isEqualTo(1);
    }

    @Test
    void concurrentDuplicateResumeConvergesToSingleExecutionRowSet() throws Exception {
        List<EngagementRef> refs = fixture.seedEligible(25, 40);
        fixture.seedPublish(FanOutFixture.PUBLISH, FanOutFixture.TO_VERSION);
        fixture.writer.dieAfterWrites(250);
        assertThatThrownBy(() -> fixture.newEngine()
                .runPublish(fixture.publishTrigger(FanOutFixture.PUBLISH)))
                .isInstanceOf(SimulatedCrashException.class);
        fixture.writer.dieAfterWrites(Integer.MAX_VALUE - 1);

        var trigger = fixture.publishTrigger(FanOutFixture.PUBLISH);
        FanOutEngine engineA = fixture.newEngine();
        FanOutEngine engineB = fixture.newSecondInstanceEngine();
        var startGate = new CountDownLatch(1);
        var doneGate = new CountDownLatch(2);
        var failures = new AtomicReference<Throwable>();
        Runnable runA = () -> awaitAndRun(engineA, trigger, startGate, doneGate, failures);
        Runnable runB = () -> awaitAndRun(engineB, trigger, startGate, doneGate, failures);
        new Thread(runA, "resume-a").start();
        new Thread(runB, "resume-b").start();
        startGate.countDown();
        assertThat(doneGate.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(failures.get()).isNull();

        for (EngagementRef ref : refs) {
            var row = fixture.index.find(ref.engagementId(), FanOutFixture.TO_VERSION,
                    PendingIndexState.PENDING);
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
    void completedCursorRecordsChunkCountAndLastCompletedChunk() {
        fixture.seedEligible(25, 40);
        fixture.seedPublish(FanOutFixture.PUBLISH, FanOutFixture.TO_VERSION);

        var outcome = fixture.newEngine()
                .runPublish(fixture.publishTrigger(FanOutFixture.PUBLISH));
        assertThat(outcome.status()).isEqualTo(FanOutOutcomeStatus.COMPLETED);

        var cursor = fixture.cursors
                .readRun(FanOutRunKind.PUBLISH, FanOutFixture.PUBLISH, FanOutFixture.PRODUCT)
                .orElseThrow();
        assertThat(cursor.kind()).isEqualTo(FanOutRunKind.PUBLISH);
        assertThat(cursor.eventId()).isEqualTo(FanOutFixture.PUBLISH);
        assertThat(cursor.productId()).isEqualTo(FanOutFixture.PRODUCT);
        assertThat(cursor.status()).isEqualTo(FanOutRunStatus.COMPLETE);
        assertThat(cursor.totalChunks()).isEqualTo(2);
        assertThat(cursor.lastCompletedChunkIndex()).isEqualTo(1);
    }

    @Test
    void corruptCursorFailsLoudlyToDeadLetterNeverSilentRestart() {
        fixture.seedEligible(25, 40);
        fixture.seedPublish(FanOutFixture.PUBLISH, FanOutFixture.TO_VERSION);
        String keyId = FanOutRunKind.PUBLISH.name() + US
                + FanOutFixture.PUBLISH + US + FanOutFixture.PRODUCT;
        fixture.store.write(new CheckpointKey(CheckpointKind.FANOUT_CURSOR, keyId), "GARBAGE");

        var outcome = fixture.newEngine()
                .runPublish(fixture.publishTrigger(FanOutFixture.PUBLISH));

        assertThat(outcome.status()).isEqualTo(FanOutOutcomeStatus.FAILED_DEAD_LETTERED);
        assertThat(outcome.deadLetterCount()).isEqualTo(1);
        assertThat(outcome.rowsResolved()).isZero();
        assertThat(fixture.deadLetters.byEvent(FanOutFixture.PUBLISH)).hasSize(1);
        var record = fixture.deadLetters.byEvent(FanOutFixture.PUBLISH).getFirst();
        assertThat(record.unitKind()).isEqualTo(DeadLetterUnitKind.CURSOR_WRITE);
        assertThat(record.chunkIndex()).isEqualTo(-1);
        assertThat(record.productId()).isEqualTo(FanOutFixture.PRODUCT);
        assertThat(record.attemptCount()).isEqualTo(1);
        assertThat(fixture.metrics.total("fanout.dlq")).isEqualTo(1);

        // never silently restarted from zero: no index writes happened
        assertThat(fixture.writer.writeCount()).isZero();
        // and the corrupt value is still there, failing loudly on decode
        assertThatThrownBy(() -> fixture.cursors
                .readRun(FanOutRunKind.PUBLISH, FanOutFixture.PUBLISH, FanOutFixture.PRODUCT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("corrupt cursor");
        assertThat(fixture.downstream.getCallCount()).isZero();
    }

    @Test
    void secondLiveRunOnSameInstanceReturnsSkippedInProgress() throws Exception {
        fixture.runner = new TestTaskRunner(fixture.config.poolBound(), Mode.HAND_OFF);
        fixture.seedEligible(25, 40);
        fixture.seedPublish(FanOutFixture.PUBLISH, FanOutFixture.TO_VERSION);
        var engine = fixture.newEngine();
        var trigger = fixture.publishTrigger(FanOutFixture.PUBLISH);

        var workerDone = new AtomicBoolean();
        var firstOutcome = new AtomicReference<FanOutRunOutcome>();
        var failure = new AtomicReference<Throwable>();
        Thread worker = new Thread(() -> {
            try {
                firstOutcome.set(engine.runPublish(trigger));
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                workerDone.set(true);
            }
        }, "live-run");
        worker.start();

        // both chunks handed off; the worker is waiting for completions
        spinUntil(() -> fixture.runner.handedCount() == 2);

        var duplicate = engine.runPublish(trigger);
        assertThat(duplicate.status()).isEqualTo(FanOutOutcomeStatus.SKIPPED_IN_PROGRESS);
        assertThat(duplicate.rowsResolved()).isZero();
        assertThat(fixture.metrics.total("fanout.duplicate.run")).isGreaterThanOrEqualTo(1);

        pumpUntilDone(fixture, worker, workerDone);
        assertThat(failure.get()).isNull();
        assertThat(firstOutcome.get().status()).isEqualTo(FanOutOutcomeStatus.COMPLETED);
        assertThat(fixture.writer.writeCount()).isEqualTo(1_000);
    }

    // ---- harness ----

    private static void awaitAndRun(FanOutEngine engine,
                                    com.example.fanout.domain.FanOutTrigger trigger,
                                    CountDownLatch startGate, CountDownLatch doneGate,
                                    AtomicReference<Throwable> failures) {
        try {
            startGate.await();
            engine.runPublish(trigger);
        } catch (Throwable t) {
            failures.set(t);
        } finally {
            doneGate.countDown();
        }
    }

    private static void pumpUntilDone(FanOutFixture fixture, Thread worker,
                                      AtomicBoolean workerDone) throws InterruptedException {
        long spins = 0;
        while (!workerDone.get() || fixture.runner.handedCount() > 0) {
            Runnable task = fixture.runner.poll();
            if (task == null) {
                if (++spins > 2_000_000_000L) {
                    worker.interrupt();
                    throw new AssertionError("stalled pumping hand-off queue");
                }
                Thread.onSpinWait();
                continue;
            }
            spins = 0;
            task.run();
        }
        worker.join(30_000L);
        assertThat(worker.isAlive()).isFalse();
    }

    private static void spinUntil(BooleanSupplier condition) {
        long spins = 0;
        while (!condition.getAsBoolean()) {
            if (++spins > 2_000_000_000L) {
                throw new AssertionError("condition never became true");
            }
            Thread.onSpinWait();
        }
    }
}
