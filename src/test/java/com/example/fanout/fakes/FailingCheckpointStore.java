package com.example.fanout.fakes;

import com.example.fanout.ports.CheckpointKey;
import com.example.fanout.ports.CheckpointStore;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fault-injection wrapper: once armed, every {@code write} and
 * {@code writeIfAbsent} throws — simulating a crash before the checkpoint
 * claim is durable. Disarmed, delegates to the delegate store.
 */
public final class FailingCheckpointStore implements CheckpointStore {

    private final CheckpointStore delegate;
    private final AtomicBoolean armed = new AtomicBoolean();

    public FailingCheckpointStore(CheckpointStore delegate) {
        this.delegate = delegate;
    }

    public void armFailures() {
        armed.set(true);
    }

    public void disarm() {
        armed.set(false);
    }

    @Override
    public Optional<String> read(CheckpointKey key) {
        return delegate.read(key);
    }

    @Override
    public void write(CheckpointKey key, String value) {
        if (armed.get()) {
            throw new IllegalStateException("simulated crash before checkpoint write");
        }
        delegate.write(key, value);
    }

    @Override
    public boolean writeIfAbsent(CheckpointKey key, String value) {
        if (armed.get()) {
            throw new IllegalStateException("simulated crash before checkpoint claim");
        }
        return delegate.writeIfAbsent(key, value);
    }
}
