package com.example.fanout.fakes;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manually advanced {@link Clock} fake (research.md D5): time moves only when
 * a test advances it — retry/backoff timing stays deterministic (spec FR-042).
 */
public final class FakeClock extends Clock {

    private final AtomicReference<Instant> current;

    public FakeClock(Instant start) {
        this.current = new AtomicReference<>(Objects.requireNonNull(start, "start must not be null"));
    }

    public static FakeClock startingAt(String isoInstant) {
        return new FakeClock(Instant.parse(isoInstant));
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return current.get();
    }

    /** Move the clock forward. */
    public void advance(Duration duration) {
        Objects.requireNonNull(duration, "duration must not be null");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative");
        }
        current.updateAndGet(now -> now.plus(duration));
    }

    public void advanceMillis(long millis) {
        advance(Duration.ofMillis(millis));
    }
}
