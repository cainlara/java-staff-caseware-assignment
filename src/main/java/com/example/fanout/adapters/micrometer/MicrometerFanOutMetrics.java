package com.example.fanout.adapters.micrometer;

import com.example.fanout.observability.FreshnessSource;
import com.example.fanout.ports.FanOutMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Micrometer implementation of {@link FanOutMetrics}
 * (contracts/micrometer-fan-out-metrics.md, research D1/D8/D13).
 *
 * <p>Mapping: {@code increment} → {@link Counter}, {@code recordTime} →
 * {@link Timer} (the freshness timer additionally publishes a percentile
 * histogram and feeds the adapter-side p99 ring), {@code gauge} →
 * {@link Gauge} bound to a strongly-referenced holder. Every meter is held in
 * a {@link ConcurrentHashMap} so Micrometer's weak references cannot collect
 * a live meter.
 *
 * <p><b>Tag rules</b>: {@code key, value} pairs; odd-length varargs, blank
 * values, and the forbidden {@code engagement_id} key are rejected with
 * {@link IllegalArgumentException} (cardinality + confidentiality,
 * FR-003/FR-012).
 *
 * <p><b>Registry failures never fail fan-out work</b>: any
 * {@link RuntimeException} thrown by the registry is caught, the sample is
 * dropped, and the {@code fanout.metrics.dropped} counter is incremented
 * best-effort. Only programming errors (null/blank name, negative delta,
 * malformed tags) throw.
 *
 * <p>The windowed freshness ring (N = 1,024) backs
 * {@link #freshnessP99()} / {@link #worstSample()} for the
 * {@code FRESHNESS_BURN} alert (research D13): deterministic, bounded, no
 * registry-config subtleties. Empty ring → p99 undefined (never fires).
 */
public final class MicrometerFanOutMetrics implements FanOutMetrics, FreshnessSource {

    /** Freshness timer: publishes a percentile histogram and feeds the ring. */
    public static final String FRESHNESS_TIMER = "fanout.publish_to_pending";

    /** Best-effort count of registry-dropped samples. */
    public static final String DROPPED_COUNTER = "fanout.metrics.dropped";

    /** Forbidden metric tag key (FR-003/FR-012). */
    static final String FORBIDDEN_TAG_KEY = "engagement_id";

    private static final int RING_CAPACITY = 1024;

    private static final char KEY_SEPARATOR = '\u0001';

    private final MeterRegistry registry;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final Map<String, Timer> timers = new ConcurrentHashMap<>();
    private final Map<String, GaugeHolder> gauges = new ConcurrentHashMap<>();
    private final FreshnessRing ring = new FreshnessRing(RING_CAPACITY);
    private final AtomicLong dropped = new AtomicLong();

    public MicrometerFanOutMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    @Override
    public void increment(String name, long delta, String... tags) {
        validateName(name);
        if (delta < 0) {
            throw new IllegalArgumentException("delta must be >= 0");
        }
        String[] pairs = validateTags(tags);
        try {
            counter(name, pairs).increment(delta);
        } catch (RuntimeException registryFailure) {
            countDropped();
        }
    }

    @Override
    public void recordTime(String name, Duration duration, String... tags) {
        validateName(name);
        Objects.requireNonNull(duration, "duration must not be null");
        String[] pairs = validateTags(tags);
        if (FRESHNESS_TIMER.equals(name)) {
            ring.add(duration, tagValue(pairs, "product_id"), tagValue(pairs, "publish_id"));
        }
        try {
            timer(name, pairs).record(duration);
        } catch (RuntimeException registryFailure) {
            countDropped();
        }
    }

    @Override
    public void gauge(String name, double value, String... tags) {
        validateName(name);
        String[] pairs = validateTags(tags);
        try {
            holder(name, pairs).set(value);
        } catch (RuntimeException registryFailure) {
            countDropped();
        }
    }

    @Override
    public OptionalDouble freshnessP99() {
        return ring.p99();
    }

    @Override
    public Optional<Sample> worstSample() {
        return ring.worst();
    }

    /** Total samples dropped because the registry rejected them. */
    public long droppedCount() {
        return dropped.get();
    }

    // ---- meter lookup / registration ----

    private Counter counter(String name, String[] pairs) {
        return counters.computeIfAbsent(cacheKey(name, pairs),
                key -> Counter.builder(name).tags(pairs).register(registry));
    }

    private Timer timer(String name, String[] pairs) {
        return timers.computeIfAbsent(cacheKey(name, pairs),
                key -> {
                    Timer.Builder builder = Timer.builder(name).tags(pairs);
                    if (FRESHNESS_TIMER.equals(name)) {
                        builder.publishPercentileHistogram();
                    }
                    return builder.register(registry);
                });
    }

    private GaugeHolder holder(String name, String[] pairs) {
        return gauges.computeIfAbsent(cacheKey(name, pairs), key -> {
            GaugeHolder holder = new GaugeHolder();
            Gauge.builder(name, holder, GaugeHolder::value).tags(pairs).register(registry);
            return holder;
        });
    }

    private void countDropped() {
        dropped.incrementAndGet();
        try {
            counters.computeIfAbsent(cacheKey(DROPPED_COUNTER, new String[0]),
                            key -> Counter.builder(DROPPED_COUNTER).register(registry))
                    .increment();
        } catch (RuntimeException registryFailure) {
            // best-effort: a broken registry cannot record its own drop count
        }
    }

    private static String cacheKey(String name, String[] pairs) {
        if (pairs.length == 0) {
            return name;
        }
        String[] joined = new String[pairs.length / 2];
        for (int i = 0; i < pairs.length; i += 2) {
            joined[i / 2] = pairs[i] + KEY_SEPARATOR + pairs[i + 1];
        }
        Arrays.sort(joined);
        StringBuilder key = new StringBuilder(name);
        for (String pair : joined) {
            key.append(KEY_SEPARATOR).append(pair);
        }
        return key.toString();
    }

    // ---- validation ----

    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must be non-blank");
        }
    }

    private static String[] validateTags(String[] tags) {
        Objects.requireNonNull(tags, "tags must not be null");
        if (tags.length % 2 != 0) {
            throw new IllegalArgumentException("tags must be even-length key/value pairs");
        }
        for (int i = 0; i < tags.length; i += 2) {
            String key = tags[i];
            String value = tags[i + 1];
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("tag key must be non-blank");
            }
            if (FORBIDDEN_TAG_KEY.equals(key)) {
                throw new IllegalArgumentException(
                        "forbidden metric tag key engagement_id (FR-003/FR-012)");
            }
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(
                        "tag value must be non-blank for key " + key);
            }
        }
        return tags;
    }

    private static String tagValue(String[] pairs, String wanted) {
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i].equals(wanted)) {
                return pairs[i + 1];
            }
        }
        return null;
    }

    /** Strongly referenced gauge target (Micrometer holds weak references). */
    private static final class GaugeHolder {

        private volatile double value;

        double value() {
            return value;
        }

        void set(double value) {
            this.value = value;
        }
    }

    /**
     * Thread-safe ring of the last N freshness samples. p99 uses the
     * nearest-rank definition: {@code sorted[ceil(0.99 * n) - 1]}.
     */
    private static final class FreshnessRing {

        private final Sample[] buffer;
        private int size;
        private int next;

        FreshnessRing(int capacity) {
            this.buffer = new Sample[capacity];
        }

        synchronized void add(Duration duration, String productId, String publishId) {
            buffer[next] = new Sample(duration, productId, publishId);
            next = (next + 1) % buffer.length;
            if (size < buffer.length) {
                size++;
            }
        }

        synchronized OptionalDouble p99() {
            if (size == 0) {
                return OptionalDouble.empty();
            }
            long[] sorted = new long[size];
            for (int i = 0; i < size; i++) {
                sorted[i] = buffer[i].duration().toNanos();
            }
            Arrays.sort(sorted);
            int index = (int) Math.ceil(0.99 * size) - 1;
            return OptionalDouble.of(sorted[Math.max(index, 0)]);
        }

        synchronized Optional<Sample> worst() {
            Sample worst = null;
            for (int i = 0; i < size; i++) {
                Sample candidate = buffer[i];
                if (worst == null
                        || candidate.duration().compareTo(worst.duration()) > 0) {
                    worst = candidate;
                }
            }
            return Optional.ofNullable(worst);
        }
    }
}
