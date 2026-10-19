package com.example.fanout.fanout;

import com.example.fanout.domain.EngagementRef;
import com.example.fanout.domain.FanOutConfig;
import com.example.fanout.domain.FanOutTrigger;
import com.example.fanout.domain.FanOutTriggerKind;
import com.example.fanout.fakes.FakeClock;
import com.example.fanout.fakes.FakeDeadLetterStore;
import com.example.fanout.fakes.FakeDownstreamLoadClient;
import com.example.fanout.fakes.FakeEligibilitySource;
import com.example.fanout.fakes.FailingIndexWriter;
import com.example.fanout.fakes.InMemoryIndexWriter;
import com.example.fanout.fakes.RecordingFanOutMetrics;
import com.example.fanout.fakes.SeededJitter;
import com.example.fanout.fakes.TestTaskRunner;
import com.example.fanout.adapters.inmemory.InMemoryCheckpointStore;
import com.example.fanout.ingest.IngestProgressRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared deterministic engine fixture: fakes + fake clock + seeded jitter +
 * test-controlled runner + immediate (clock-advancing) backoff waiter. No
 * sleeps anywhere (spec FR-072).
 */
final class FanOutFixture {

    static final String PRODUCT = "prod-1";
    static final String PUBLISH = "pub-1";
    static final String TO_VERSION = "v2";
    static final Instant PUBLISHED_AT = Instant.parse("2026-10-18T10:00:00Z");

    /**
     * Metric counter allowlist for clean hot-path runs (spec FR-070/FR-071):
     * engine/permit-gate/retry names only, no engagement content, and never
     * the off-path names (backpressure, inflight hwm) - those must not fire
     * on publish/withdraw (spec FR-032, SC-001).
     */
    static final List<String> HOT_PATH_COUNTER_ALLOWLIST = List.of(
            "fanout.chunks.started", "fanout.chunks.completed",
            "fanout.rows.resolved", "fanout.cas.reapply",
            "fanout.duplicate.run", "fanout.dlq", "fanout.retry");

    final FakeEligibilitySource eligibility = new FakeEligibilitySource();
    final InMemoryCheckpointStore store = new InMemoryCheckpointStore();
    final InMemoryIndexWriter index = new InMemoryIndexWriter();
    final FailingIndexWriter writer = new FailingIndexWriter(index);
    final RecordingFanOutMetrics metrics = new RecordingFanOutMetrics();
    final FakeDeadLetterStore deadLetters = new FakeDeadLetterStore();
    final FakeClock clock = FakeClock.startingAt("2026-10-18T12:00:00Z");
    final FakeDownstreamLoadClient downstream = new FakeDownstreamLoadClient(Duration.ZERO);
    final FanOutCursorRepository cursors = new FanOutCursorRepository(store);
    final FanOutConfig config;
    TestTaskRunner runner;

    FanOutFixture() {
        this(FanOutConfig.defaults());
    }

    FanOutFixture(FanOutConfig config) {
        this.config = config;
        this.runner = new TestTaskRunner(config.poolBound());
    }

    FanOutEngine newEngine() {
        return new FanOutEngine(
                eligibility,
                writer,
                cursors,
                deadLetters,
                metrics,
                config,
                runner,
                clock::advance,
                clock,
                new SeededJitter(42L));
    }

    /** Fresh engine over the same durable stores (simulates a second instance). */
    FanOutEngine newSecondInstanceEngine() {
        return newEngine();
    }

    void seedPublish(String publishId, String toVersion) {
        seedPublish(publishId, PRODUCT, toVersion, PUBLISHED_AT);
    }

    void seedPublish(String publishId, String productId, String toVersion, Instant publishedAt) {
        var ingest = new IngestProgressRepository(store);
        ingest.claimPublish(publishId, productId, toVersion, publishedAt);
        ingest.addProductPublish(productId, publishId);
    }

    /** Seeds firmCount x perFirm eligible refs for PUBLISH in fixture order. */
    List<EngagementRef> seedEligible(int firmCount, int perFirm) {
        List<EngagementRef> refs = new ArrayList<>(firmCount * perFirm);
        for (int f = 0; f < firmCount; f++) {
            for (int e = 0; e < perFirm; e++) {
                refs.add(ref(f, e));
            }
        }
        eligibility.seedEligible(PRODUCT, PUBLISH, refs);
        return refs;
    }

    static EngagementRef ref(int firmIndex, int engagementIndex) {
        return new EngagementRef(
                String.format("firm-%04d-eng-%06d", firmIndex, engagementIndex),
                String.format("firm-%04d", firmIndex),
                PRODUCT,
                "v1",
                "EU");
    }

    FanOutTrigger publishTrigger(String publishId) {
        return new FanOutTrigger(FanOutTriggerKind.PUBLISH, publishId, PRODUCT, 1L, null);
    }

    FanOutTrigger withdrawTrigger(String withdrawId, String matchedPublishId) {
        return new FanOutTrigger(FanOutTriggerKind.WITHDRAW, withdrawId, PRODUCT, 1L, matchedPublishId);
    }

    /**
     * Suite-wide hot-path invariants (spec SC-001/FR-032/FR-070/FR-071):
     * zero downstream calls, no backpressure or in-flight hwm metrics, and
     * every emitted counter within the allowlist. Wired via
     * {@code @AfterEach} in each engine test class.
     */
    void assertHotPathInvariants() {
        org.assertj.core.api.Assertions.assertThat(downstream.getCallCount())
                .as("downstream call log empty on the hot path (FR-032)")
                .isZero();
        org.assertj.core.api.Assertions.assertThat(metrics.saw("fanout.backpressure"))
                .as("no backpressure on the hot path (FR-031/FR-032)")
                .isFalse();
        org.assertj.core.api.Assertions.assertThat(metrics.saw("fanout.downstream.inflight.hwm"))
                .as("no downstream in-flight traffic on the hot path (FR-032)")
                .isFalse();
        org.assertj.core.api.Assertions.assertThat(metrics.counterNames())
                .as("metric names stay within the FR-071 allowlist")
                .isSubsetOf(HOT_PATH_COUNTER_ALLOWLIST);
    }
}
