package com.example.fanout.backfill;

import com.example.fanout.fanout.AdmissionGate;
import com.example.fanout.fanout.AdmissionGate.Verdict;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Semaphore;

/**
 * Demand-driven round-robin batch admission for backfill (spec B3,
 * contracts/backfill-worker.md, research.md D7): NOT a pre-materialized chunk
 * list — each cycle every incomplete firm contributes at most one batch, in
 * firm-id order from a rotating scan position, gated by
 * {@link AdmissionGate#forBackfill} (halt + firm gate; the product gate is
 * re-checked once the batch supplies product ids) and by the per-firm
 * in-flight slot. A firm at its cap is skipped for the cycle, never queued
 * ahead of others; a gated firm is skipped too (not a failure, not buffered).
 *
 * <p>This class owns only run-local bookkeeping — the durable truth stays in
 * {@link BackfillCursorRepository}; cursors here are seeded from a durable
 * snapshot at run start and mirrored after each successful advance.
 * The driver (admit/evaluate) runs single-threaded, but completion hooks fire
 * from parallel batch-job threads — all shared bookkeeping is synchronized
 * (the delivered scheduler guards its counters the same way).
 */
public final class BackfillScheduler {

    /** One firm admitted for exactly one enumeration fetch this cycle. */
    public record Admission(String firmId, String afterCursor, int batchIndex) {

        public Admission {
            Objects.requireNonNull(firmId, "firmId must not be null");
            Objects.requireNonNull(afterCursor, "afterCursor must not be null");
            if (batchIndex < 0) {
                throw new IllegalArgumentException("batchIndex must be >= 0");
            }
        }
    }

    /** Result of one admission cycle. */
    public record Cycle(List<Admission> admitted, boolean halted) {

        public Cycle {
            admitted = List.copyOf(admitted);
        }
    }

    private final List<String> firmIds;
    private final Map<String, String> cursors;
    private final Set<String> completeFirms;
    private final Map<String, Semaphore> slots;
    private final AdmissionGate gate;
    private int scanIndex;
    private int batchOrdinals;

    /**
     * @param firmIds     firms to backfill (sorted ascending internally)
     * @param startCursors durable resume bound per firm (empty string = start)
     */
    public BackfillScheduler(Collection<String> firmIds, Map<String, String> startCursors,
                             AdmissionGate gate, BackfillConfig config) {
        Objects.requireNonNull(firmIds, "firmIds must not be null");
        Objects.requireNonNull(startCursors, "startCursors must not be null");
        this.gate = Objects.requireNonNull(gate, "gate must not be null");
        Objects.requireNonNull(config, "config must not be null");
        this.firmIds = List.copyOf(new TreeSet<>(firmIds));
        this.cursors = new LinkedHashMap<>();
        this.completeFirms = new HashSet<>();
        this.slots = new LinkedHashMap<>();
        for (String firm : this.firmIds) {
            this.cursors.put(firm, startCursors.getOrDefault(firm, ""));
            this.slots.put(firm, new Semaphore(config.firmInFlightCap()));
        }
    }

    /**
     * Admit at most one batch per incomplete firm, rotating the scan start
     * each call. Stops admitting on the first {@code HALTED} verdict (halt
     * dominates) — already-admitted entries stay in the returned list for the
     * caller to drain or cancel.
     */
    public synchronized Cycle admitCycle() {
        List<Admission> admitted = new ArrayList<>();
        boolean halted = false;
        int size = firmIds.size();
        int start = scanIndex;
        for (int offset = 0; offset < size; offset++) {
            String firm = firmIds.get((start + offset) % size);
            if (completeFirms.contains(firm)) {
                continue;
            }
            Verdict verdict = gate.admitFirm(firm);
            if (verdict == Verdict.HALTED) {
                halted = true;
                break;
            }
            if (verdict != Verdict.ADMIT) {
                continue;
            }
            if (!slots.get(firm).tryAcquire()) {
                continue;
            }
            admitted.add(new Admission(firm, cursors.get(firm), batchOrdinals++));
        }
        if (size > 0) {
            scanIndex = (start + 1) % size;
        }
        return new Cycle(admitted, halted);
    }

    /** Release one admission's firm slot — call exactly once per admission. */
    public synchronized void release(Admission admission) {
        slots.get(admission.firmId()).release();
    }

    /** Mirror a durable advance into the run-local resume bound. */
    public synchronized void markAdvanced(Admission admission, String newCursor) {
        cursors.put(admission.firmId(), newCursor);
    }

    /** Mark the firm's enumeration finished (done batch observed). */
    public synchronized void markComplete(Admission admission) {
        completeFirms.add(admission.firmId());
    }

    /** Firms whose cursor is not yet complete. */
    public synchronized int firmsRemaining() {
        return firmIds.size() - completeFirms.size();
    }

    /** True when every firm of this run completed. */
    public synchronized boolean allComplete() {
        return completeFirms.size() == firmIds.size();
    }
}
