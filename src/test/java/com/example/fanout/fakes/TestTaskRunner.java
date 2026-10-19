package com.example.fanout.fakes;

import com.example.fanout.fanout.TaskRunner;

import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-controlled {@link TaskRunner} (research.md D5): INLINE runs each task
 * on the caller thread; HAND_OFF queues the task for the test to pump. Exposes
 * exact in-flight counters and a max tracker — every ceiling invariant is
 * asserted on counters, never on timing (spec FR-072).
 */
public final class TestTaskRunner implements TaskRunner {

    public enum Mode {
        INLINE,
        HAND_OFF
    }

    private final int bound;
    private volatile Mode mode;
    private final Queue<Runnable> handed = new ConcurrentLinkedQueue<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private final AtomicInteger executed = new AtomicInteger();

    public TestTaskRunner(int bound) {
        this(bound, Mode.INLINE);
    }

    public TestTaskRunner(int bound, Mode mode) {
        if (bound < 1) {
            throw new IllegalArgumentException("bound must be >= 1");
        }
        this.bound = bound;
        this.mode = Objects.requireNonNull(mode, "mode must not be null");
    }

    @Override
    public void execute(Runnable task) {
        Objects.requireNonNull(task, "task must not be null");
        if (mode == Mode.HAND_OFF) {
            handed.add(() -> runCounted(task));
            return;
        }
        runCounted(task);
    }

    @Override
    public int bound() {
        return bound;
    }

    /** Poll one hand-off task for the test to run (null when queue empty). */
    public Runnable poll() {
        return handed.poll();
    }

    public int handedCount() {
        return handed.size();
    }

    public void setMode(Mode newMode) {
        mode = Objects.requireNonNull(newMode, "newMode must not be null");
    }

    public int inFlight() {
        return inFlight.get();
    }

    public int maxInFlight() {
        return maxInFlight.get();
    }

    public int executed() {
        return executed.get();
    }

    private void runCounted(Runnable task) {
        beginTask();
        try {
            task.run();
        } finally {
            endTask();
        }
    }

    private void beginTask() {
        int current = inFlight.incrementAndGet();
        maxInFlight.accumulateAndGet(current, Math::max);
    }

    private void endTask() {
        inFlight.decrementAndGet();
        executed.incrementAndGet();
    }
}
