package com.example.fanout.fakes;

import com.example.fanout.ports.FanOutMetrics;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Recording {@link FanOutMetrics} fake: captures counter and timer calls so
 * contract tests can assert names/tags are identifier/measurement-shaped only
 * (no engagement content).
 */
public final class RecordingFanOutMetrics implements FanOutMetrics {

    /**
     * Backfill-path counter allowlist (spec FR-053, contracts/backfill-worker.md):
     * every counter the backfill run may emit, identifiers and measurements only.
     */
    public static final List<String> BACKFILL_COUNTER_ALLOWLIST = List.of(
            "backfill.batches.admitted", "backfill.batches.completed",
            "backfill.engagements.written", "backfill.engagements.noop",
            "backfill.engagements.dlq",
            "backfill.admit.reject.halt", "backfill.admit.reject.product",
            "backfill.admit.reject.firm",
            "backfill.halt.abandoned", "backfill.permit.park",
            "backfill.downstream.inflight.hwm", "fanout.dlq", "fanout.retry",
            "fanout.cas.reapply");

    /** Backfill-path timer allowlist (spec FR-053/FR-032). */
    public static final List<String> BACKFILL_TIMER_ALLOWLIST = List.of(
            "backfill.halt.drain");

    public record CounterCall(String name, long delta, List<String> tags) {
    }

    public record TimerCall(String name, Duration duration, List<String> tags) {
    }

    private final List<CounterCall> counters = new ArrayList<>();
    private final List<TimerCall> timers = new ArrayList<>();

    @Override
    public synchronized void increment(String name, long delta, String... tags) {
        validateName(name);
        if (delta < 0) {
            throw new IllegalArgumentException("delta must be >= 0");
        }
        counters.add(new CounterCall(name, delta, List.of(Objects.requireNonNull(tags, "tags"))));
    }

    @Override
    public synchronized void recordTime(String name, Duration duration, String... tags) {
        validateName(name);
        Objects.requireNonNull(duration, "duration must not be null");
        timers.add(new TimerCall(name, duration, List.of(Objects.requireNonNull(tags, "tags"))));
    }

    public synchronized List<CounterCall> getCounters() {
        return List.copyOf(counters);
    }

    public synchronized List<TimerCall> getTimers() {
        return List.copyOf(timers);
    }

    /** Sum of recorded deltas for one counter name (0 when never emitted). */
    public synchronized long total(String name) {
        return counters.stream().filter(c -> c.name().equals(name)).mapToLong(CounterCall::delta).sum();
    }

    /** Number of recorded emissions for one counter name. */
    public synchronized long count(String name) {
        return counters.stream().filter(c -> c.name().equals(name)).count();
    }

    /** True when the counter name was emitted at least once. */
    public synchronized boolean saw(String name) {
        return counters.stream().anyMatch(c -> c.name().equals(name))
                || timers.stream().anyMatch(t -> t.name().equals(name));
    }

    /** Distinct counter names emitted (for allowlist assertions). */
    public synchronized Set<String> counterNames() {
        return counters.stream().map(CounterCall::name).collect(
                Collectors.toUnmodifiableSet());
    }

    /** Sum of recorded deltas for one counter name filtered by a tag pair. */
    public synchronized long total(String name, String tagKey, String tagValue) {
        return counters.stream()
                .filter(c -> c.name().equals(name))
                .filter(c -> hasTag(c.tags(), tagKey, tagValue))
                .mapToLong(CounterCall::delta)
                .sum();
    }

    /** True when every emitted counter name is inside the given allowlist. */
    public synchronized boolean countersWithin(List<String> allowlist) {
        return counterNames().stream().allMatch(allowlist::contains);
    }

    private static boolean hasTag(List<String> tags, String key, String value) {
        for (int i = 0; i + 1 < tags.size(); i += 2) {
            if (tags.get(i).equals(key) && tags.get(i + 1).equals(value)) {
                return true;
            }
        }
        return false;
    }

    /** Distinct timer names emitted (for allowlist assertions). */
    public synchronized Set<String> timerNames() {
        return timers.stream().map(TimerCall::name).collect(
                Collectors.toUnmodifiableSet());
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must be non-blank");
        }
    }
}
