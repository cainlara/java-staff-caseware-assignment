package com.example.fanout.fakes;

import com.example.fanout.ports.CheckpointKey;
import com.example.fanout.ports.CheckpointStore;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cursor checkpoint seam for crash tests, two kill positions per write:
 *
 * <ul>
 *   <li><b>before</b> — dies before the value lands: the "kill between the
 *       last row write and the cursor advance" window (SC-003); on restart the
 *       whole batch re-runs and idempotent writes absorb it;</li>
 *   <li><b>after</b> — dies once the value is durable: "after the k-th batch
 *       completion".</li>
 * </ul>
 *
 * <p>{@link #killBeforeNextWrite()} arms a one-shot pre-write death for the
 * focused SC-003 test; the randomized table drives the ordinal events.
 * Reads never kill.
 */
public final class CrashingCheckpointStore implements CheckpointStore {

    private final CheckpointStore delegate;
    private final KillController controller;
    private final AtomicBoolean killBeforeNextWrite = new AtomicBoolean();

    public CrashingCheckpointStore(CheckpointStore delegate, KillController controller) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        if (controller == null) {
            throw new IllegalArgumentException("controller must not be null");
        }
        this.delegate = delegate;
        this.controller = controller;
    }

    /** The next write dies before delegating (one-shot, SC-003 seam). */
    public void killBeforeNextWrite() {
        killBeforeNextWrite.set(true);
    }

    @Override
    public Optional<String> read(CheckpointKey key) {
        return delegate.read(key);
    }

    @Override
    public void write(CheckpointKey key, String value) {
        if (killBeforeNextWrite.compareAndSet(true, false)) {
            throw new SimulatedProcessDeath(
                    "simulated process death before cursor write for " + key.id());
        }
        controller.event("checkpoint.before");
        delegate.write(key, value);
        controller.event("checkpoint.after");
    }
}
