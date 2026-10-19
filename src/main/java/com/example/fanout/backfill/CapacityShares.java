package com.example.fanout.backfill;

import com.example.fanout.ports.CapacityBudget;
import java.util.concurrent.Semaphore;

/**
 * Partition of the shared downstream capacity {@code C} into two disjoint
 * permit pools (spec B5, FR-020–FR-022, contracts/capacity-shares.md,
 * research.md D5): a backfill share and a real-time repair share.
 *
 * <p>Validation (FR-021): {@code parentC >= 1}, {@code backfill >= 1},
 * {@code repair >= 0}, {@code backfill + repair <= parentC} — over-partitioned
 * configurations are rejected at construction, i.e. at startup.
 *
 * <p>{@link #backfillBudget()} and {@link #repairBudget()} return two
 * independent budgets built on separate semaphores: there is no parent
 * semaphore and no shared wait queue, so a waiter on one share can never
 * acquire from the other and {@code total <= parentC} holds by construction.
 */
public record CapacityShares(int parentC, int backfill, int repair) {

    public CapacityShares {
        if (parentC < 1) {
            throw new IllegalArgumentException("parentC must be >= 1");
        }
        if (backfill < 1) {
            throw new IllegalArgumentException("backfill must be >= 1");
        }
        if (repair < 0) {
            throw new IllegalArgumentException("repair must be >= 0");
        }
        if (backfill + repair > parentC) {
            throw new IllegalArgumentException(
                    "backfill + repair must be <= parentC but was "
                            + (backfill + repair) + " > " + parentC);
        }
    }

    /** Spec-default partition: C = 20, B_backfill = 18, B_repair = 2. */
    public static CapacityShares defaults() {
        return new CapacityShares(20, 18, 2);
    }

    /** Independent permit pool for the backfill share. */
    public CapacityBudget backfillBudget() {
        return new SemaphoreBudget(backfill);
    }

    /**
     * Independent permit pool for the real-time repair share. Capacity may be
     * 0 (no repair capacity reserved) — {@code tryAcquire} then always fails.
     */
    public CapacityBudget repairBudget() {
        return new SemaphoreBudget(repair);
    }

    /**
     * Counted-permit budget over a private semaphore: non-blocking acquire,
     * exactly-once release, {@code release()} without a held permit throws
     * {@link IllegalStateException} (delivered {@code CapacityBudget} port).
     */
    private static final class SemaphoreBudget implements CapacityBudget {

        private final int capacity;
        private final Semaphore permits;

        private SemaphoreBudget(int capacity) {
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
    }
}
