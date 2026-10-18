package com.example.fanout.adapters.inmemory;

import com.example.fanout.ports.FanOutMetrics;
import java.time.Duration;
import java.util.Objects;

/**
 * No-op {@link FanOutMetrics} placeholder: validates arguments (non-blank name,
 * non-null duration, non-negative delta) then records nothing. A real metrics
 * implementation is a later phase.
 */
public final class NoOpFanOutMetrics implements FanOutMetrics {

    @Override
    public void increment(String name, long delta, String... tags) {
        validateName(name);
        if (delta < 0) {
            throw new IllegalArgumentException("delta must be >= 0");
        }
        Objects.requireNonNull(tags, "tags must not be null");
    }

    @Override
    public void recordTime(String name, Duration duration, String... tags) {
        validateName(name);
        Objects.requireNonNull(duration, "duration must not be null");
        Objects.requireNonNull(tags, "tags must not be null");
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must be non-blank");
        }
    }
}
