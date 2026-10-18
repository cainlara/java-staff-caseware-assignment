package com.example.fanout.ports;

/**
 * Bounds in-flight calls to the shared downstream engagement-load service.
 *
 * <p><b>Accounting</b>: permits are counted resources, not idempotent keys.
 * Every successful {@link #tryAcquire()} MUST be matched by exactly one
 * {@link #release()}. {@code release()} without a held permit MUST throw
 * {@link IllegalStateException} (never silently inflate the budget). Thread-safe:
 * concurrent acquire/release must never admit more than C in flight.
 *
 * <p><b>Capacity</b>: max in-flight {@code C}, configurable, default 20.
 * When exhausted, {@code tryAcquire()} returns {@code false}; callers must back
 * off or pause admission (no unbounded buffering).
 *
 * <p><b>Hot path</b>: the publish hot path (index writes) does NOT consume
 * permits; only off-hot-path downstream loads acquire permits.
 */
public interface CapacityBudget {

    /** Non-blocking acquire: {@code true} = permit taken. */
    boolean tryAcquire();

    /** Give back one permit; must pair with a successful acquire. */
    void release();

    /** Remaining permits (for tests/metrics). */
    int available();
}
