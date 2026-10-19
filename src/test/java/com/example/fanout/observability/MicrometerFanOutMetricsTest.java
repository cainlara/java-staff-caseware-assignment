package com.example.fanout.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.example.fanout.adapters.micrometer.MicrometerFanOutMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Pins contracts/micrometer-fan-out-metrics.md test vectors 1–5: meter
 * mapping, tag-pair rules, forbidden engagement_id tag, name allowlist,
 * windowed p99 ring, gauge tracking, and registry-failure soft-drop.
 */
class MicrometerFanOutMetricsTest {

    /**
     * Documented meter-name allowlist (specs 003/004 emissions + the spec-005
     * additions from contracts/micrometer-fan-out-metrics.md).
     */
    private static final List<String> ALLOWLIST = List.of(
            // spec-003 counters
            "fanout.chunks.started", "fanout.chunks.completed", "fanout.rows.resolved",
            "fanout.retry", "fanout.dlq", "fanout.backpressure", "fanout.duplicate.run",
            "fanout.cas.reapply", "fanout.downstream.inflight.hwm",
            "fanout.admit.reject.halt", "fanout.admit.reject.product",
            "fanout.admit.reject.firm",
            // spec-004 backfill counters
            "backfill.batches.admitted", "backfill.batches.completed",
            "backfill.engagements.written", "backfill.engagements.noop",
            "backfill.engagements.dlq", "backfill.admit.reject.halt",
            "backfill.admit.reject.product", "backfill.admit.reject.firm",
            "backfill.halt.abandoned", "backfill.permit.park",
            "backfill.downstream.inflight.hwm",
            // repair-share counters
            "repair.backpressure", "repair.downstream.inflight.hwm",
            // ingest counters
            "ingest.published", "ingest.withdrawn", "ingest.resolved",
            "ingest.deferred", "ingest.duplicate",
            // spec-005 additions
            "fanout.downstream.completed", "fanout.metrics.dropped",
            // timers
            "fanout.publish_to_pending", "backfill.halt.drain", "ingest.lag",
            // gauges
            "fanout.downstream.inflight", "fanout.checkpoint.lag.chunks",
            "fanout.dlq.depth");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MicrometerFanOutMetrics metrics = new MicrometerFanOutMetrics(registry);

    @Test
    void incrementMapsToAccumulatingCounter() {
        metrics.increment("fanout.chunks.started", 1, "product_id", "prod-1");
        metrics.increment("fanout.chunks.started", 3, "product_id", "prod-1");

        assertThat(registry.counter("fanout.chunks.started", "product_id", "prod-1").count())
                .isEqualTo(4.0);
    }

    @Test
    void recordTimeMapsToTimerWithTags() {
        metrics.recordTime("ingest.lag", Duration.ofSeconds(5), "product_id", "prod-1");

        var timers = registry.find("ingest.lag").timers();
        assertThat(timers).hasSize(1);
        var timer = timers.iterator().next();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.SECONDS)).isEqualTo(5.0);
    }

    @Test
    void gaugeBindsOnceThenUpdates() {
        metrics.gauge("fanout.downstream.inflight", 3.0);
        metrics.gauge("fanout.downstream.inflight", 7.0);

        var gauges = registry.find("fanout.downstream.inflight").gauges();
        assertThat(gauges).hasSize(1);
        assertThat(gauges.iterator().next().value()).isEqualTo(7.0);
    }

    @Test
    void oddLengthTagsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> metrics.increment("fanout.retry", 1, "product_id"))
                .withMessageContaining("pairs");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> metrics.recordTime("ingest.lag", Duration.ofSeconds(1), "firm_id"))
                .withMessageContaining("pairs");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> metrics.gauge("fanout.dlq.depth", 1.0, "product_id"))
                .withMessageContaining("pairs");
    }

    @Test
    void engagementIdTagKeyRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> metrics.increment("fanout.retry", 1,
                        "engagement_id", "eng-1"))
                .withMessageContaining("engagement_id");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> metrics.gauge("fanout.dlq.depth", 1.0,
                        "product_id", "p", "engagement_id", "eng-1"))
                .withMessageContaining("engagement_id");
    }

    @Test
    void blankTagValueAndBlankNameRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> metrics.increment("fanout.retry", 1, "product_id", " "))
                .withMessageContaining("product_id");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> metrics.increment(" ", 1))
                .withMessageContaining("name");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> metrics.gauge(null, 1.0))
                .withMessageContaining("name");
    }

    @Test
    void meterNameSetStaysWithinAllowlist() {
        exerciseEveryPath();

        Set<String> names = registry.getMeters().stream()
                .map(Meter::getId)
                .map(Meter.Id::getName)
                .collect(Collectors.toUnmodifiableSet());
        assertThat(names).isSubsetOf(ALLOWLIST);
        assertThat(names).noneMatch(n -> n.contains("engagement"));
    }

    @Test
    void noMeterCarriesEngagementIdTag() {
        exerciseEveryPath();

        registry.getMeters().forEach(meter -> assertThat(meter.getId().getTag("engagement_id"))
                .as("engagement_id is never a metric tag (FR-003/FR-012)")
                .isNull());
    }

    @Test
    void freshnessRingP99MatchesManualP99() {
        List<Duration> samples = knownSamples();

        samples.forEach(d -> metrics.recordTime("fanout.publish_to_pending", d,
                "product_id", "prod-1", "publish_id", "pub-1"));

        double manualP99Nanos = nearestRankP99Nanos(samples);
        assertThat(metrics.freshnessP99()).isPresent();
        assertThat(metrics.freshnessP99().getAsDouble()).isEqualTo(manualP99Nanos);
        assertThat(metrics.freshnessP99().getAsDouble())
                .isEqualTo(Duration.ofSeconds(10).toNanos());
    }

    @Test
    void freshnessRingKeepsOnlyLast1024Samples() {
        List<Duration> recent = new ArrayList<>();
        for (int i = 0; i < 1024; i++) {
            Duration d = Duration.ofSeconds(i % 7);
            recent.add(d);
            metrics.recordTime("fanout.publish_to_pending", d,
                    "product_id", "prod-1", "publish_id", "pub-" + i);
        }
        for (int i = 0; i < 500; i++) {
            metrics.recordTime("fanout.publish_to_pending", Duration.ofSeconds(90),
                    "product_id", "prod-1", "publish_id", "old-" + i);
        }
        List<Duration> expected = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            expected.add(Duration.ofSeconds(i % 7));
        }
        for (int i = 0; i < 524; i++) {
            expected.add(Duration.ofSeconds(90));
        }

        assertThat(metrics.freshnessP99()).isPresent();
        assertThat(metrics.freshnessP99().getAsDouble())
                .isEqualTo(nearestRankP99Nanos(expected));
    }

    @Test
    void emptyRingYieldsNoP99() {
        assertThat(metrics.freshnessP99()).isEmpty();
        assertThat(metrics.worstSample()).isEmpty();
    }

    @Test
    void freshnessTimerCountAndRingStayMeasurable() {
        metrics.recordTime("fanout.publish_to_pending", Duration.ofSeconds(90),
                "product_id", "prod-1", "publish_id", "pub-1");
        metrics.recordTime("fanout.publish_to_pending", Duration.ofSeconds(10),
                "product_id", "prod-1", "publish_id", "pub-2");

        Collection<Timer> timers = registry.find("fanout.publish_to_pending").timers();
        assertThat(timers).hasSize(2);
        long count = timers.stream().mapToLong(Timer::count).sum();
        assertThat(count).isEqualTo(2);
        double maxRecorded = timers.stream()
                .mapToDouble(t -> t.max(TimeUnit.NANOSECONDS))
                .max()
                .orElseThrow();
        assertThat(maxRecorded).isEqualTo(Duration.ofSeconds(90).toNanos());
        assertThat(metrics.freshnessP99()).isPresent();
        assertThat(metrics.freshnessP99().getAsDouble())
                .isEqualTo(Duration.ofSeconds(90).toNanos());
    }

    @Test
    void worstSampleCarriesTheSlowestPublishIdentity() {
        metrics.recordTime("fanout.publish_to_pending", Duration.ofSeconds(10),
                "product_id", "prod-1", "publish_id", "pub-fast");
        metrics.recordTime("fanout.publish_to_pending", Duration.ofSeconds(90),
                "product_id", "prod-2", "publish_id", "pub-slow");

        var worst = metrics.worstSample();
        assertThat(worst).isPresent();
        assertThat(worst.orElseThrow().duration()).isEqualTo(Duration.ofSeconds(90));
        assertThat(worst.orElseThrow().productId()).isEqualTo("prod-2");
        assertThat(worst.orElseThrow().publishId()).isEqualTo("pub-slow");
    }

    @Test
    void registryFailureSoftDropsAndCounts() {
        SimpleMeterRegistry failing = new SimpleMeterRegistry() {
            @Override
            protected Counter newCounter(Meter.Id id) {
                if (id.getName().equals("repair.backpressure")) {
                    throw new IllegalStateException("registry unavailable");
                }
                return super.newCounter(id);
            }
        };
        MicrometerFanOutMetrics softDrop = new MicrometerFanOutMetrics(failing);

        assertThatNoException().isThrownBy(
                () -> softDrop.increment("repair.backpressure", 1, "firm_id", "firm-1"));
        assertThat(failing.counter("fanout.metrics.dropped").count()).isEqualTo(1.0);
        assertThat(failing.find("repair.backpressure").counter()).isNull();
    }

    // ---- helpers ----

    private void exerciseEveryPath() {
        metrics.increment("fanout.chunks.started", 1, "product_id", "prod-1");
        metrics.increment("fanout.downstream.completed", 1, "product_id", "prod-1");
        metrics.increment("ingest.duplicate", 1, "product_id", "prod-1");
        metrics.recordTime("ingest.lag", Duration.ofMillis(3), "product_id", "prod-1");
        metrics.recordTime("fanout.publish_to_pending", Duration.ofSeconds(4),
                "product_id", "prod-1", "publish_id", "pub-1");
        metrics.gauge("fanout.downstream.inflight", 2.0);
        metrics.gauge("fanout.checkpoint.lag.chunks", 5.0,
                "product_id", "prod-1", "publish_id", "pub-1");
        metrics.gauge("fanout.dlq.depth", 0.0);
    }

    /** 99 fast samples + 1 slow: nearest-rank p99 lands on the fast sample. */
    private static List<Duration> knownSamples() {
        List<Duration> samples = new ArrayList<>();
        for (int i = 0; i < 99; i++) {
            samples.add(Duration.ofSeconds(10));
        }
        samples.add(Duration.ofSeconds(90));
        return samples;
    }

    /** Nearest-rank p99 in nanoseconds: sorted[ceil(0.99 * n) - 1]. */
    private static double nearestRankP99Nanos(List<Duration> samples) {
        List<Long> sorted = samples.stream().map(Duration::toNanos).sorted().toList();
        int index = (int) Math.ceil(0.99 * sorted.size()) - 1;
        return sorted.get(Math.max(index, 0));
    }
}
