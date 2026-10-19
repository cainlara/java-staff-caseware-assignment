package com.example.fanout.fakes;

import com.example.fanout.ports.CapacityBudget;

/**
 * Permit-acquisition seam for crash tests: the load admission event fires
 * BEFORE the permit is taken, so a kill there dies holding nothing (exactly
 * the "died at load admission" boundary). {@code release}/{@code available}
 * delegate untouched — release-exactly-once assertions keep working.
 */
public final class CrashingCapacityBudget implements CapacityBudget {

    private final CapacityBudget delegate;
    private final KillController controller;

    public CrashingCapacityBudget(CapacityBudget delegate, KillController controller) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        if (controller == null) {
            throw new IllegalArgumentException("controller must not be null");
        }
        this.delegate = delegate;
        this.controller = controller;
    }

    @Override
    public boolean tryAcquire() {
        controller.event("load.admission");
        return delegate.tryAcquire();
    }

    @Override
    public void release() {
        delegate.release();
    }

    @Override
    public int available() {
        return delegate.available();
    }
}
