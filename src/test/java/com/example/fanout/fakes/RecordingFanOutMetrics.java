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
