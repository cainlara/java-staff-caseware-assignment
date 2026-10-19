package com.example.fanout.fakes;

import com.example.fanout.domain.PendingIndexRow;
import com.example.fanout.domain.PendingIndexState;
import com.example.fanout.fanout.TransientFanOutException;
import com.example.fanout.ports.IndexWriter;
import com.example.fanout.ports.WriteResult;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Scripted-failure {@link IndexWriter} decorating {@link InMemoryIndexWriter}
 * (research.md D5). {@code failNext(n)} makes the next {@code n} upserts throw
 * a {@link TransientFanOutException} (retry-eligible); {@code dieAfterWrites(n)}
 * makes upsert {@code n + 1} throw a {@link SimulatedCrashException} — an
 * unchecked RuntimeException the engine does NOT catch, so a test can abort a
 * run mid-flight exactly like process death (crash-resume tests).
 */
public final class FailingIndexWriter implements IndexWriter {

    /** Test-only signal simulating process death; must escape the engine. */
    public static final class SimulatedCrashException extends RuntimeException {
        public SimulatedCrashException(String message) {
            super(message);
        }
    }

    private final IndexWriter delegate;
    private final AtomicInteger failNext = new AtomicInteger();
    private final AtomicInteger dieAfterWrites = new AtomicInteger(-1);
    private final AtomicInteger writeCount = new AtomicInteger();

    public FailingIndexWriter(IndexWriter delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    }

    /** Next {@code n} upserts throw a transient failure. */
    public void failNext(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("n must be >= 0");
        }
        failNext.set(n);
    }

    /** Fail transiently on the next {@code n} upserts, then succeed. */
    public void failTimes(int n) {
        failNext(n);
    }

    /** Kill the run when the {@code n + 1}-th upsert arrives (crash injection). */
    public void dieAfterWrites(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("n must be >= 0");
        }
        dieAfterWrites.set(n);
    }

    public int writeCount() {
        return writeCount.get();
    }

    @Override
    public WriteResult upsert(PendingIndexRow row) {
        int count = writeCount.incrementAndGet();
        int dieAt = dieAfterWrites.get();
        if (dieAt >= 0 && count > dieAt) {
            throw new SimulatedCrashException("simulated process death after " + dieAt + " writes");
        }
        if (failNext.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
            throw new TransientFanOutException("injected index write failure");
        }
        return delegate.upsert(row);
    }

    @Override
    public Optional<PendingIndexRow> find(
            String engagementId, String offeredVersion, PendingIndexState state) {
        return delegate.find(engagementId, offeredVersion, state);
    }
}
