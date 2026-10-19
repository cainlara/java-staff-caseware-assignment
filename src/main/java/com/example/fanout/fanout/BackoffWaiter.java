package com.example.fanout.fanout;

import java.time.Duration;
import java.util.Objects;

/**
 * Backoff waiting seam (research.md D5): production sleeps on the wall clock;
 * tests inject a waiter that advances a {@code FakeClock} instead of sleeping,
 * so retry schedules stay fully deterministic (spec FR-042, SC-006).
 */
public interface BackoffWaiter {

    void await(Duration delay);

    /** Production waiter: sleeps on the wall clock. */
    static BackoffWaiter sleeping() {
        return delay -> {
            Objects.requireNonNull(delay, "delay must not be null");
            if (delay.isNegative()) {
                throw new IllegalArgumentException("delay must not be negative");
            }
            if (delay.isZero()) {
                return;
            }
            try {
                Thread.sleep(delay.toMillis(), (int) (delay.toNanos() % 1_000_000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted during backoff wait", e);
            }
        };
    }
}
