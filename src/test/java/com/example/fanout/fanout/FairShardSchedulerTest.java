package com.example.fanout.fanout;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.fanout.domain.EngagementRef;
import com.example.fanout.domain.FanOutRunKind;
import com.example.fanout.domain.FanOutTask;
import com.example.fanout.fakes.TestTaskRunner;
import com.example.fanout.fakes.TestTaskRunner.Mode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * US2 scheduler invariants (spec SC-009, FR-011, research.md D4):
 * per-firm in-flight never exceeds firmInFlightCap at any observation point,
 * round-robin interleave in firm-id order with cap-skip (never queued ahead),
 * total in-flight never exceeds poolBound. All interleaving is driven by the
 * test-controlled runner - no sleeps anywhere (spec FR-072).
 */
class FairShardSchedulerTest {

    private static final String EVENT = "pub-1";
    private static final String PRODUCT = "prod-1";

    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicBoolean workerDone = new AtomicBoolean();

    @Test
    void roundRobinInterleavesFirmsInAscendingOrderWithRotatingCursor() throws Exception {
        List<FanOutTask> chunks = new ArrayList<>();
        chunks.addAll(firmChunks("firm-a", 2, 0));
        chunks.addAll(firmChunks("firm-b", 2, 2));
        chunks.addAll(firmChunks("firm-c", 2, 4));
        var scheduler = new FairShardScheduler(chunks, 16, 10);
        var runner = new TestTaskRunner(16, Mode.HAND_OFF);
        List<String> order = new CopyOnWriteArrayList<>();

        Thread worker = start(scheduler, runner, task -> order.add(task.firmId() + ":" + task.chunkIndex()));
        pumpUntilDone(runner, worker);
        awaitWorkerFinished(worker);

        assertThat(failure.get()).isNull();
        assertThat(order).containsExactly(
                "firm-a:0", "firm-b:2", "firm-c:4",
                "firm-a:1", "firm-b:3", "firm-c:5");
        assertThat(scheduler.inFlightTotal()).isZero();
        assertThat(runner.maxInFlight()).isLessThanOrEqualTo(16);
    }

    @Test
    void firmAtCapIsSkippedThisCycleNeverQueuedAhead() throws Exception {
        List<FanOutTask> chunks = new ArrayList<>();
        chunks.addAll(firmChunks("firm-a", 2, 0));
        chunks.addAll(firmChunks("firm-b", 2, 2));
        var scheduler = new FairShardScheduler(chunks, 16, 1);
        var runner = new TestTaskRunner(16, Mode.HAND_OFF);
        List<String> order = new CopyOnWriteArrayList<>();

        Thread worker = start(scheduler, runner, task -> {
            assertThat(scheduler.inFlightFor(task.firmId())).isLessThanOrEqualTo(1);
            assertThat(scheduler.inFlightTotal()).isLessThanOrEqualTo(16);
            order.add(task.firmId() + ":" + task.chunkIndex());
        });

        // a0 and b0 handed; a is at its cap (1) so a1 must not be handed while
        // a0 is in flight, and b is at cap too: the worker blocks here.
        spinUntil(() -> runner.handedCount() == 2);
        assertThat(runner.handedCount()).isEqualTo(2);

        pumpUntilDone(runner, worker);
        awaitWorkerFinished(worker);

        assertThat(failure.get()).isNull();
        assertThat(order).containsExactly(
                "firm-a:0", "firm-b:2", "firm-a:1", "firm-b:3");
        assertThat(runner.executed()).isEqualTo(4);
        assertThat(scheduler.inFlightFor("firm-a")).isZero();
        assertThat(scheduler.inFlightFor("firm-b")).isZero();
    }

    @Test
    void totalInFlightNeverExceedsPoolBound() throws Exception {
        List<FanOutTask> chunks = new ArrayList<>();
        chunks.addAll(firmChunks("firm-a", 2, 0));
        chunks.addAll(firmChunks("firm-b", 2, 2));
        chunks.addAll(firmChunks("firm-c", 2, 4));
        var scheduler = new FairShardScheduler(chunks, 2, 10);
        var runner = new TestTaskRunner(2, Mode.HAND_OFF);
        List<String> order = new CopyOnWriteArrayList<>();

        Thread worker = start(scheduler, runner, task -> {
            assertThat(scheduler.inFlightTotal()).isLessThanOrEqualTo(2);
            assertThat(scheduler.inFlightFor(task.firmId())).isLessThanOrEqualTo(10);
            order.add(task.firmId() + ":" + task.chunkIndex());
        });

        // pool bound 2: exactly two chunks admitted before any completion
        spinUntil(() -> runner.handedCount() == 2);
        assertThat(runner.handedCount()).isEqualTo(2);

        pumpUntilDone(runner, worker);
        awaitWorkerFinished(worker);

        assertThat(failure.get()).isNull();
        assertThat(runner.executed()).isEqualTo(6);
        assertThat(runner.maxInFlight()).isLessThanOrEqualTo(2);
        assertThat(scheduler.inFlightTotal()).isZero();
        assertThat(order).hasSize(6);
        assertThat(order.subList(0, 2)).containsExactly("firm-a:0", "firm-b:2");
    }

    @Test
    void inlineRunnerCompletesEveryChunkOnTheCallingThread() {
        List<FanOutTask> chunks = new ArrayList<>();
        chunks.addAll(firmChunks("firm-a", 3, 0));
        chunks.addAll(firmChunks("firm-b", 3, 3));
        var scheduler = new FairShardScheduler(chunks, 4, 2);
        var runner = new TestTaskRunner(4, Mode.INLINE);
        List<String> order = new CopyOnWriteArrayList<>();

        scheduler.runAll(runner, task -> {
            assertThat(scheduler.inFlightFor(task.firmId())).isLessThanOrEqualTo(2);
            assertThat(scheduler.inFlightTotal()).isLessThanOrEqualTo(4);
            order.add(task.firmId() + ":" + task.chunkIndex());
        });

        assertThat(order).containsExactly(
                "firm-a:0", "firm-b:3", "firm-a:1", "firm-b:4", "firm-a:2", "firm-b:5");
        assertThat(runner.executed()).isEqualTo(6);
        assertThat(scheduler.inFlightTotal()).isZero();
    }

    // ---- harness ----

    private Thread start(FairShardScheduler scheduler, TestTaskRunner runner,
                         Consumer<FanOutTask> handler) {
        Thread worker = new Thread(() -> {
            try {
                scheduler.runAll(runner, handler);
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                workerDone.set(true);
            }
        }, "scheduler-worker");
        worker.start();
        return worker;
    }

    /** Pump handed tasks on this thread until the worker finished and none remain. */
    private void pumpUntilDone(TestTaskRunner runner, Thread worker) throws InterruptedException {
        long spins = 0;
        while (!workerDone.get() || runner.handedCount() > 0) {
            Runnable task = runner.poll();
            if (task == null) {
                if (++spins > 2_000_000_000L) {
                    worker.interrupt();
                    throw new AssertionError("scheduler stalled: nothing handed and worker not done");
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

    private void awaitWorkerFinished(Thread worker) throws InterruptedException {
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

    private static List<FanOutTask> firmChunks(String firmId, int count, int firstChunkIndex) {
        List<FanOutTask> chunks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int chunkIndex = firstChunkIndex + i;
            EngagementRef ref = new EngagementRef(
                    firmId + "-eng-" + String.format("%06d", chunkIndex),
                    firmId,
                    PRODUCT,
                    "v1",
                    "EU");
            chunks.add(new FanOutTask(EVENT, PRODUCT, FanOutRunKind.PUBLISH,
                    chunkIndex, firmId, List.of(ref)));
        }
        return chunks;
    }
}
