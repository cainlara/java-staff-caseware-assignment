package com.example.fanout.fakes;

import com.example.fanout.ports.CapacityBudget;
import java.util.concurrent.Semaphore;

/**
 * Semaphore-backed {@link CapacityBudget} fake with configurable C (default 20).
 * {@link #release()} without a held permit throws {@link IllegalStateException}.
 */
public final class FakeCapacityBudget implements CapacityBudget {

    public static final int DEFAULT_CAPACITY = 20;

    private final int capacity;
    private final Semaphore permits;

    public FakeCapacityBudget() {
        this(DEFAULT_CAPACITY);
    }

    public FakeCapacityBudget(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        this.capacity = capacity;
        this.permits = new Semaphore(capacity, true);
    }

    @Override
    public boolean tryAcquire() {
        return permits.tryAcquire();
    }

    @Override
    public void release() {
        if (permits.availablePermits() >= capacity) {
            throw new IllegalStateException("release() without a held permit");
        }
        permits.release();
    }

    @Override
    public int available() {
        return permits.availablePermits();
    }

    public int getCapacity() {
        return capacity;
    }
}
