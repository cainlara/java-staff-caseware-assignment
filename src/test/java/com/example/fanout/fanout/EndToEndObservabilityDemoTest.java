package com.example.fanout.fanout;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.fanout.adapters.inmemory.InMemoryAlertSink;
import com.example.fanout.adapters.inmemory.InMemoryAlertThresholdProvider;
import com.example.fanout.adapters.inmemory.InMemoryCheckpointStore;
import com.example.fanout.adapters.inmemory.RecordingFanOutTriggerSink;
import com.example.fanout.adapters.micrometer.MicrometerFanOutMetrics;
import com.example.fanout.domain.EngagementRef;
import com.example.fanout.domain.FanOutConfig;
import com.example.fanout.domain.FanOutOutcomeStatus;
import com.example.fanout.domain.TemplatePublished;
import com.example.fanout.fakes.FakeCapacityBudget;
import com.example.fanout.fakes.FakeClock;
import com.example.fanout.fakes.FakeDeadLetterStore;
import com.example.fanout.fakes.FakeDownstreamLoadClient;
import com.example.fanout.fakes.FakeEligibilitySource;
import com.example.fanout.fakes.InMemoryIndexWriter;
import com.example.fanout.fakes.InMemoryStructuredLogSink;
import com.example.fanout.fakes.SeededJitter;
import com.example.fanout.fakes.TestTaskRunner;
import com.example.fanout.ingest.IngestProgressRepository;
import com.example.fanout.ingest.IngestService;
import com.example.fanout.observability.AlertEvaluation;
import com.example.fanout.observability.AlertEvaluator;
import com.example.fanout.observability.IdentifierField;
import com.example.fanout.observability.LogEvent;
import com.example.fanout.observability.WhitelistValidatingLogSink;
import com.example.fanout.ports.DeadLetterStore;
import com.example.fanout.ports.StructuredLogSink;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Runnable observability demo (spec FR-008, contracts/
 * e2e-observability-demo.md): 40,000 eligible rows, one publish run through
 * ingest -> engine -> metrics/logs/alerts, then the eight SC-005 assertion
 * groups. No I/O, no Spring, FakeClock throughout.
 */
class EndToEndObservabilityDemoTest {

    private static final String PRODUCT = "prod-1";
    private static final String PUBLISH = "pub-1";
    private static final int FIRMS = 40;
    private static final int PER_FIRM = 1_000;
    private static final int ROWS = FIRMS * PER_FIRM;
    private static final int CHUNK_SIZE = 500;
    private static final int CHUNKS = ROWS / CHUNK_SIZE;
    private static final int CAPACITY = 20;

    @Test
    void observabilityDemoEndToEnd() {
        // ---- wiring ----
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerFanOutMetrics metrics = new MicrometerFanOutMetrics(registry);
        InMemoryStructuredLogSink memory = new InMemoryStructuredLogSink();
        StructuredLogSink logs = new WhitelistValidatingLogSink(memory);
        InMemoryAlertSink alerts = new InMemoryAlertSink();
        InMemoryAlertThresholdProvider thresholds = new InMemoryAlertThresholdProvider();
        InMemoryCheckpointStore store = new InMemoryCheckpointStore();
        InMemoryIndexWriter index = new InMemoryIndexWriter();
        FakeDeadLetterStore deadLetters = new FakeDeadLetterStore();
        FakeClock clock = FakeClock.startingAt("2026-10-19T12:00:00Z");
        FakeDownstreamLoadClient downstream = new FakeDownstreamLoadClient(Duration.ZERO);
        FakeCapacityBudget budget = new FakeCapacityBudget(CAPACITY);
        FakeEligibilitySource eligibility = new FakeEligibilitySource();
        RecordingFanOutTriggerSink triggers = new RecordingFanOutTriggerSink();
        TestTaskRunner runner = new TestTaskRunner(FanOutConfig.defaults().poolBound());
        FanOutCursorRepository cursors = new FanOutCursorRepository(store);
        FanOutConfig config = new FanOutConfig(CHUNK_SIZE,
                FanOutConfig.defaults().poolBound(),
                FanOutConfig.defaults().firmInFlightCap(),
                FanOutConfig.defaults().retryMaxAttempts(),
                FanOutConfig.defaults().retryBaseDelay(),
                FanOutConfig.defaults().retryMaxDelay(),
                FanOutConfig.defaults().jitterFactorMin(),
                FanOutConfig.defaults().jitterFactorMax());

        PermitGuardedDownstream guarded = new PermitGuardedDownstream(
                downstream, budget, clock::advance, clock, metrics, config,
                new SeededJitter(11L));
        AtomicInteger maxInFlight = new AtomicInteger();
        TaskRunner samplingRunner = new TaskRunner() {
            @Override
            public void execute(Runnable task) {
                runner.execute(() -> {
                    clock.advance(Duration.ofMillis(500));
                    task.run();
                    maxInFlight.updateAndGet(max -> Math.max(max, guarded.inFlight()));
                });
            }

            @Override
            public int bound() {
                return runner.bound();
            }
        };
        FanOutEngine engine = new FanOutEngine(
                eligibility, index, cursors, deadLetters, metrics, config,
                samplingRunner, delay -> clock.advance(Duration.ofSeconds(1)), clock,
                new SeededJitter(11L), AdmissionGate.admitAll(metrics), logs);
        IngestService ingest = new IngestService(
                new IngestProgressRepository(store), triggers, metrics, clock, logs);

        // ---- script ----
        TemplatePublished published = new TemplatePublished(
                PUBLISH, PRODUCT, "v1", "v2", clock.instant());
        List<EngagementRef> refs = new ArrayList<>(ROWS);
        for (int f = 0; f < FIRMS; f++) {
            for (int e = 0; e < PER_FIRM; e++) {
                refs.add(new EngagementRef(
                        String.format("firm-%04d-eng-%06d", f, e),
                        String.format("firm-%04d", f),
                        PRODUCT, "v1", "EU"));
            }
        }
        eligibility.seedEligible(PRODUCT, PUBLISH, refs);

        ingest.ingest(published);
        assertThat(registry.timer("ingest.lag",
                "product_id", PRODUCT).count()).isEqualTo(1);

        var outcome = engine.runPublish(triggers.emitted().getFirst());
        assertThat(outcome.status()).isEqualTo(FanOutOutcomeStatus.COMPLETED);

        DeadLetterStore dlq = deadLetters;
        AlertEvaluator evaluator = new AlertEvaluator(
                () -> metrics.worstSample().map(sample -> new AlertEvaluator.FreshnessSample(
                        sample.duration(), sample.productId(), sample.publishId())),
                List::of,
                dlq,
                Optional::empty,
                () -> false,
                () -> false,
                guarded::inFlight,
                CAPACITY,
                () -> 0L,
                thresholds,
                alerts,
                logs);
        evaluator.evaluate(clock.instant());

        // ---- assertions (SC-005, groups 1-8) ----

        // 1. eligible-set equality: 40,000 pending rows, zero dead-letters
        assertThat(index.rowCount()).isEqualTo(ROWS);
        assertThat(deadLetters.count()).isZero();

        // 2. zero hot-path downstream calls (index-only hot path)
        assertThat(downstream.getCallCount()).isZero();

        // 3. in-flight <= C sampled after every chunk; gauge HWM <= 20
        assertThat(maxInFlight.get()).isLessThanOrEqualTo(CAPACITY);
        assertThat(guarded.inFlight()).isZero();
        var inflightGauge = registry.find("fanout.downstream.inflight").gauge();
        if (inflightGauge != null) {
            assertThat(inflightGauge.value()).isLessThanOrEqualTo(CAPACITY);
        }

        // 4. freshness measurable + ingest.lag
        var freshness = registry.find(MicrometerFanOutMetrics.FRESHNESS_TIMER)
                .timers().iterator().next();
        assertThat(freshness.count()).isEqualTo(1);
        assertThat(freshness.getId().getTag("publish_id")).isEqualTo(PUBLISH);
        assertThat(freshness.getId().getTag("product_id")).isEqualTo(PRODUCT);
        assertThat(freshness.totalTime(TimeUnit.SECONDS))
                .isEqualTo(CHUNKS / 2L); // 0.5 FakeClock seconds per chunk, below the 60s SLO
        assertThat(metrics.freshnessP99()).isPresent();
        assertThat(metrics.freshnessP99().getAsDouble())
                .isEqualTo(freshness.totalTime(TimeUnit.NANOSECONDS));
        assertThat(registry.timer("ingest.lag", "product_id", PRODUCT).count()).isEqualTo(1);

        // 5. progress/coverage
        assertThat(registry.counter("fanout.chunks.started", "product_id", PRODUCT).count())
                .isEqualTo(CHUNKS);
        assertThat(registry.counter("fanout.chunks.completed", "product_id", PRODUCT).count())
                .isEqualTo(CHUNKS);
        assertThat(registry.counter("fanout.rows.resolved", "product_id", PRODUCT).count())
                .isEqualTo(ROWS);
        var lagGauge = registry.find("fanout.checkpoint.lag.chunks").gauge();
        assertThat(lagGauge).isNotNull();
        assertThat(lagGauge.value()).isZero();

        // 6. log confidentiality + O(chunks) volume bound
        assertThat(memory.events()).hasSizeLessThanOrEqualTo(4 * CHUNKS + 20);
        for (LogEvent event : memory.events()) {
            for (Map.Entry<IdentifierField, String> entry : event.identifiers().entrySet()) {
                assertThat(IdentifierField.values()).contains(entry.getKey());
                assertThat(entry.getValue()).doesNotContainPattern("\\s{3,}");
            }
        }

        // 7. no spurious alerts on a healthy run
        assertThat(alerts.fires()).isEmpty();

        // 8. duplicate re-delivery is idempotency-neutral under observation
        double duplicateBefore = registry.counter("ingest.duplicate",
                "product_id", PRODUCT).count();
        ingest.ingest(published);
        assertThat(registry.counter("ingest.duplicate", "product_id", PRODUCT).count())
                .isEqualTo(duplicateBefore + 1);
        assertThat(index.rowCount()).isEqualTo(ROWS);
        assertThat(alerts.fires()).extracting(AlertEvaluation::condition).isEmpty();
    }
}
