package com.example.fanout.fakes;

import com.example.fanout.fanout.TransientFanOutException;
import com.example.fanout.ports.CheckpointKey;
import com.example.fanout.ports.CheckpointStore;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Storage-unavailable seam (spec edge case "store unavailable",
 * contracts/backfill-checkpoint.md): reads pass through, writes fail once
 * {@code n} writes have succeeded with the transient port failure
 * {@link TransientFanOutException} (the bounded retry path).
 * {@link IllegalStateException} stays reserved for corrupt cursor values,
 * which are loud, un-retried, and dead-lettered.
 *
 * <p>Seams live outside the retry guard's client call, so the failure must be
 * wrapped on purpose by the unit under test (research.md US2 crash design).
 */
public final class ThrowingCheckpointStore implements CheckpointStore {

    private final CheckpointStore delegate;
    private final AtomicInteger writes = new AtomicInteger();
    private volatile int failAfter = Integer.MAX_VALUE;

    public ThrowingCheckpointStore(CheckpointStore delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        this.delegate = delegate;
    }

    /** Fail every write from the {@code n}th one onwards ({@code n} >= 0). */
    public void failAfterWrites(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("n must be >= 0");
        }
        this.failAfter = n;
    }

    /** Succeeded writes so far. */
    public int writes() {
        return writes.get();
    }

    @Override
    public Optional<String> read(CheckpointKey key) {
        return delegate.read(key);
    }

    @Override
    public void write(CheckpointKey key, String value) {
        if (writes.get() >= failAfter) {
            throw new TransientFanOutException("checkpoint store unavailable");
        }
        writes.incrementAndGet();
        delegate.write(key, value);
    }
}
