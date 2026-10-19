package com.example.fanout.fakes;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ordinal kill switch for the crash-resume tests (research.md D12): every
 * instrumented seam calls {@link #event(String)}; run {@code i} arms
 * {@code armRun(target_i)} so the {@code target_i}-th event of THAT run dies
 * with {@link SimulatedProcessDeath}. The fault-free baseline run keeps the
 * controller disarmed and records {@code totalEvents} (T), from which the
 * fixed-seed targets {@code ceil(i * T / (k + 1))} derive — deterministic,
 * no randomness at kill time (the seed table supplies variation).
 *
 * <p>First-occurrence index per phase is recorded for diagnostics.
 */
public final class KillController {

    private final AtomicInteger runEvents = new AtomicInteger();
    private final AtomicInteger totalEvents = new AtomicInteger();
    private final AtomicInteger killsFired = new AtomicInteger();
    private final Map<String, Integer> firstPhaseIndex = new ConcurrentHashMap<>();
    private volatile int killAt = -1;

    /** One instrumented event; throws when it is the armed kill point. */
    public void event(String phase) {
        int index = runEvents.incrementAndGet();
        totalEvents.incrementAndGet();
        firstPhaseIndex.putIfAbsent(phase, index);
        if (index == killAt) {
            killsFired.incrementAndGet();
            throw new SimulatedProcessDeath(
                    "simulated process death at run event " + index + " (" + phase + ")");
        }
    }

    /** Arm the kill point for one upcoming run (event ordinal within the run). */
    public void armRun(int killAtEvent) {
        runEvents.set(0);
        killAt = killAtEvent;
    }

    /** Count events without ever killing (fault-free baseline). */
    public void disarm() {
        runEvents.set(0);
        killAt = -1;
    }

    /** Events observed in the current (armed) run. */
    public int runEvents() {
        return runEvents.get();
    }

    /** Events across every run — the baseline run gives the total T. */
    public int totalEvents() {
        return totalEvents.get();
    }

    /** Kills actually thrown. */
    public int killsFired() {
        return killsFired.get();
    }

    /** First event index (within a run) of one phase, for diagnostics. */
    public int firstIndexOf(String phase) {
        return firstPhaseIndex.getOrDefault(phase, -1);
    }
}
