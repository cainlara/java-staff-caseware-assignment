package com.example.fanout.fakes;

import com.example.fanout.fanout.JitterSource;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Deterministic {@link JitterSource} (research.md D5): either a fixed seed
 * ({@code new SeededJitter(42)}) or a scripted sequence of values. Every delay
 * computed from it is reproducible, so tests assert the exact jitter bounds.
 */
public final class SeededJitter implements JitterSource {

    private final AtomicLong seed;
    private final double[] scripted;
    private final AtomicInteger scriptedIndex = new AtomicInteger();

    /** Seeded xorshift-style source in [0, 1). */
    public SeededJitter(long seed) {
        this.seed = new AtomicLong(seed == 0 ? 0x9E3779B97F4A7C15L : seed);
        this.scripted = null;
    }

    /** Scripted source: cycles through the given values in [0, 1). */
    public SeededJitter(double... values) {
        Objects.requireNonNull(values, "values must not be null");
        if (values.length == 0) {
            throw new IllegalArgumentException("at least one scripted value required");
        }
        for (double v : values) {
            if (v < 0.0 || v >= 1.0) {
                throw new IllegalArgumentException("scripted jitter values must be in [0, 1)");
            }
        }
        this.seed = null;
        this.scripted = values.clone();
    }

    @Override
    public double next() {
        if (scripted != null) {
            int index = scriptedIndex.getAndIncrement();
            return scripted[index % scripted.length];
        }
        long x = seed.getAndUpdate(v -> {
            long next = v;
            next ^= (next << 13);
            next ^= (next >>> 7);
            next ^= (next << 17);
            return next;
        });
        long nonNegative = (x >>> 11) & ((1L << 53) - 1);
        return (double) nonNegative / (double) (1L << 53);
    }
}
