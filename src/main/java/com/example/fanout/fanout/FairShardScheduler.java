package com.example.fanout.fanout;

import com.example.fanout.domain.FanOutTask;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

/**
 * Per-firm round-robin chunk scheduler (spec R3, FR-010…FR-012; research.md D4).
 *
 * <p>State: per-firm FIFO deque of pending chunks (index order), a
 * {@code TreeSet} of firms with pending chunks in ascending order with a
 * rotating scan cursor, one {@code Semaphore(firmInFlightCap)} per firm, and
 * one global {@code Semaphore(poolBound)} for the total in-flight ceiling.
 *
 * <p>Invariants (asserted by tests at every observation point):
 * per-firm in-flight <= {@code firmInFlightCap}; total in-flight <=
 * {@code poolBound}. Firms at their cap are skipped for the scan, never
 * queued ahead of others. Chunks of one firm run in index order.
 *
 * <p>Completion waits are signal-based ({@code wait}/{@code notifyAll}) — no
 * sleeps (spec FR-072).
 */
public final class FairShardScheduler {

    private final Map<String, Deque<FanOutTask>> pendingByFirm = new ConcurrentHashMap<>();
    private final NavigableSet<String> pendingFirms = new TreeSet<>();
    private final Map<String, Semaphore> firmPermits = new ConcurrentHashMap<>();
    private final Semaphore poolPermits;
    private final int poolBound;
    private final int firmInFlightCap;
    private String scanCursor;

    private long submitted;
    private long completed;

    public FairShardScheduler(List<FanOutTask> chunks, int poolBound, int firmInFlightCap) {
        Objects.requireNonNull(chunks, "chunks must not be null");
        if (poolBound < 1) {
            throw new IllegalArgumentException("poolBound must be >= 1");
        }
        if (firmInFlightCap < 1) {
            throw new IllegalArgumentException("firmInFlightCap must be >= 1");
        }
        this.poolBound = poolBound;
        this.firmInFlightCap = firmInFlightCap;
        this.poolPermits = new Semaphore(poolBound, true);
        for (FanOutTask chunk : chunks) {
            Objects.requireNonNull(chunk, "chunk must not be null");
            pendingByFirm.computeIfAbsent(chunk.firmId(), firm -> new ArrayDeque<>()).add(chunk);
            pendingFirms.add(chunk.firmId());
            firmPermits.computeIfAbsent(chunk.firmId(), firm -> new Semaphore(firmInFlightCap, true));
        }
    }

    /**
     * Run every pending chunk to completion on the given runner, honoring the
     * per-firm cap and pool bound. Returns only when all chunks resolved
     * (handler returned or threw — throws propagate to the caller after
     * invariants are released).
     */
    public void runAll(TaskRunner runner, Consumer<FanOutTask> chunkHandler) {
        Objects.requireNonNull(runner, "runner must not be null");
        Objects.requireNonNull(chunkHandler, "chunkHandler must not be null");
        while (true) {
            FanOutTask pick = tryPick();
            if (pick != null) {
                acquirePoolSlot(pick);
                synchronized (this) {
                    submitted++;
                }
                runner.execute(wrap(pick, chunkHandler));
                continue;
            }
            synchronized (this) {
                if (submitted == completed) {
                    if (pendingFirms.isEmpty()) {
                        return;
                    }
                    // nothing in flight yet a pick failed: firm permits must be
                    // free (a completion released them after our pick attempt).
                    // If they are not, permits leaked — fail loudly.
                    String stuck = pendingFirms.stream()
                            .filter(firm -> inFlightFor(firm) >= firmInFlightCap)
                            .findFirst()
                            .orElse(null);
                    if (stuck != null) {
                        throw new IllegalStateException(
                                "scheduler stalled: firm " + stuck
                                        + " at cap with no in-flight chunk");
                    }
                    continue; // permits freed after the pick attempt: retry pick
                }
                awaitProgress();
            }
        }
    }

    /** Total in-flight chunks at this instant (pool permits held). */
    public int inFlightTotal() {
        return poolBound - poolPermits.availablePermits();
    }

    /** In-flight chunks for one firm at this instant. */
    public int inFlightFor(String firmId) {
        Semaphore permits = firmPermits.get(firmId);
        if (permits == null) {
            return 0;
        }
        return firmInFlightCap - permits.availablePermits();
    }

    // ---- internals ----

    private FanOutTask tryPick() {
        List<String> scanOrder = scanOrder();
        for (String firm : scanOrder) {
            Deque<FanOutTask> queue = pendingByFirm.get(firm);
            if (queue == null || queue.isEmpty()) {
                pendingFirms.remove(firm);
                continue;
            }
            Semaphore permits = firmPermits.get(firm);
            if (!permits.tryAcquire()) {
                continue; // firm at its in-flight cap: skip, do not queue ahead
            }
            FanOutTask task = queue.poll();
            advanceScanCursorPast(firm);
            if (queue.isEmpty()) {
                pendingFirms.remove(firm);
            }
            return task;
        }
        return null;
    }

    private List<String> scanOrder() {
        List<String> order = new ArrayList<>(pendingFirms.size());
        if (pendingFirms.isEmpty()) {
            return order;
        }
        String start = scanCursor == null || !pendingFirms.contains(scanCursor)
                ? pendingFirms.first()
                : scanCursor;
        order.addAll(pendingFirms.tailSet(start, true));
        order.addAll(pendingFirms.headSet(start, false));
        return order;
    }

    private void advanceScanCursorPast(String firm) {
        NavigableSet<String> successors = pendingFirms.tailSet(firm, false);
        scanCursor = successors.isEmpty()
                ? (pendingFirms.isEmpty() ? null : pendingFirms.first())
                : successors.first();
    }

    private void acquirePoolSlot(FanOutTask pick) {
        // pool full: pause admission (blocking) — completions release slots
        try {
            poolPermits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            firmPermits.get(pick.firmId()).release();
            throw new IllegalStateException("interrupted while admitting chunk", e);
        }
    }

    private Runnable wrap(FanOutTask task, Consumer<FanOutTask> handler) {
        return () -> {
            try {
                handler.accept(task);
            } finally {
                firmPermits.get(task.firmId()).release();
                poolPermits.release();
                synchronized (this) {
                    completed++;
                    notifyAll();
                }
            }
        };
    }

    private void awaitProgress() {
        try {
            wait();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for chunk completion", e);
        }
    }
}
