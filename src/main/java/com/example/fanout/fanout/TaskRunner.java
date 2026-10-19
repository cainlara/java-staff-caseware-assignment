package com.example.fanout.fanout;

import java.util.concurrent.Executors;

/**
 * Bounded chunk-execution seam (research.md D5): production runs tasks on a
 * fixed platform-thread pool of {@link #bound()} threads; tests inject a
 * test-controlled runner (inline or hand-off) so all invariants are asserted
 * on counters/latches, never on timing.
 *
 * <p>{@link #execute(Runnable)} may run the task synchronously (inline) or
 * asynchronously; the engine's scheduler tracks completion itself and never
 * assumes either mode.
 */
public interface TaskRunner {

    void execute(Runnable task);

    /** Maximum number of tasks this runner executes concurrently. */
    int bound();

    /** Production runner: fixed daemon-thread pool of {@code bound} threads. */
    static TaskRunner fixedPool(int bound) {
        if (bound < 1) {
            throw new IllegalArgumentException("bound must be >= 1");
        }
        var executor = Executors.newFixedThreadPool(bound, runnable -> {
            Thread thread = new Thread(runnable, "fanout-task-runner");
            thread.setDaemon(true);
            return thread;
        });
        return new TaskRunner() {
            @Override
            public void execute(Runnable task) {
                executor.submit(task);
            }

            @Override
            public int bound() {
                return bound;
            }
        };
    }
}
