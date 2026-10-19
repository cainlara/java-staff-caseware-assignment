package com.example.fanout.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.fanout.adapters.inmemory.InMemoryCheckpointStore;
import com.example.fanout.adapters.inmemory.RecordingFanOutTriggerSink;
import com.example.fanout.backfill.BackfillConfig;
import com.example.fanout.backfill.BackfillCursorRepository;
import com.example.fanout.backfill.BackfillWorker;
import com.example.fanout.domain.EngagementRef;
import com.example.fanout.domain.FanOutConfig;
import com.example.fanout.domain.FanOutOutcomeStatus;
import com.example.fanout.domain.FanOutTrigger;
import com.example.fanout.domain.FanOutTriggerKind;
import com.example.fanout.domain.TemplatePublished;
import com.example.fanout.domain.TemplateWithdrawn;
import com.example.fanout.fakes.FakeCapacityBudget;
import com.example.fanout.fakes.FakeClock;
import com.example.fanout.fakes.FakeDeadLetterStore;
import com.example.fanout.fakes.FakeEligibilitySource;
import com.example.fanout.fakes.FailingIndexWriter;
import com.example.fanout.fakes.InMemoryIndexWriter;
import com.example.fanout.fakes.InMemoryStructuredLogSink;
import com.example.fanout.fakes.RecordingFanOutMetrics;
import com.example.fanout.fakes.SeededEngagementEnumerator;
import com.example.fanout.fakes.SeededJitter;
import com.example.fanout.fakes.TestTaskRunner;
import com.example.fanout.fanout.AdmissionGate;
import com.example.fanout.fanout.FanOutCursorRepository;
import com.example.fanout.fanout.FanOutEngine;
import com.example.fanout.fanout.PermitGuardedDownstream;
import com.example.fanout.fanout.RetryExecutor;
import com.example.fanout.fanout.RetryPolicy;
import com.example.fanout.ingest.IngestProgressRepository;
import com.example.fanout.ingest.IngestService;
import com.example.fanout.ports.DownstreamLoadClient;
import com.example.fanout.ports.StructuredLogSink;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Suite-level confidentiality + volume audit (spec FR-005/FR-013,
 * contracts/structured-logging.md test vectors 3-4): every captured event is
 * whitelist-validated, carries no content-shaped values, never leaks
 * engagement-content markers, keeps engagement_id on error paths only, and a
 * hot-path publish of 2,000 rows logs at chunk cadence only.
 */
class LogConfidentialityTest {

    private static final String PRODUCT = "prod-1";

    private static final List<String> CONTENT_MARKERS = List.of(
            "workpaper", "confidential", "trial balance", "free text", "wp-");

    private final InMemoryCheckpointStore store = new InMemoryCheckpointStore();
    private final InMemoryStructuredLogSink memory = new InMemoryStructuredLogSink();
    private final StructuredLogSink sink = new WhitelistValidatingLogSink(memory);
    private final RecordingFanOutMetrics metrics = new RecordingFanOutMetrics();
    private final FakeClock clock = FakeClock.startingAt("2026-10-19T09:00:00Z");
    private final FakeDeadLetterStore deadLetters = new FakeDeadLetterStore();
    private final InMemoryIndexWriter index = new InMemoryIndexWriter();
    private final FailingIndexWriter writer = new FailingIndexWriter(index);
    private final FakeEligibilitySource eligibility = new FakeEligibilitySource();
    private final FanOutCursorRepository cursors = new FanOutCursorRepository(store);
    private final RecordingFanOutTriggerSink triggers = new RecordingFanOutTriggerSink();
    private final TestTaskRunner runner = new TestTaskRunner(4);

    @Test
    void hotPathPublishOfTwoThousandRowsLogsAtChunkCadenceOnly() {
        ingest().ingest(new TemplatePublished("pub-1", PRODUCT, "v1", "v2", clock.instant()));
        eligibility.seedEligible(PRODUCT, "pub-1", refs(4, 500));

        var outcome = engine().runPublish(triggers.emitted().getFirst());

        assertThat(outcome.status()).isEqualTo(FanOutOutcomeStatus.COMPLETED);
        // 1 claimed + 1 run.started + 4 chunk pairs + 1 run.completed = 11
        // events: bound 4 * chunkCount + constant
        assertThat(memory.events()).hasSizeLessThanOrEqualTo(4 * 4 + 8);
        assertThat(memory.eventsFor("fanout.chunk.started")).hasSize(4);
        assertThat(memory.eventsFor("fanout.chunk.completed")).hasSize(4);
    }

    @Test
    void everyCapturedEventStaysWhitelistedAndContentFree() {
        IngestService ingest = ingest();
        ingest.ingest(new TemplatePublished("pub-1", PRODUCT, "v1", "v2", clock.instant()));
        ingest.ingest(new TemplatePublished("pub-1", PRODUCT, "v1", "v2", clock.instant()));
        ingest.ingest(new TemplateWithdrawn("wd-late", PRODUCT, "v9", clock.instant()));

        List<EngagementRef> refs = refs(2, 3);
        eligibility.seedEligible(PRODUCT, "pub-1", refs);
        engine().runPublish(publishTrigger("pub-1"));

        writer.failNext(1);
        ingest.ingest(new TemplatePublished("pub-2", PRODUCT, "v2", "v3", clock.instant()));
        eligibility.seedEligible(PRODUCT, "pub-2", refs);
        engine().runPublish(publishTrigger("pub-2"));

        writer.failNext(999);
        ingest.ingest(new TemplatePublished("pub-3", PRODUCT, "v3", "v4", clock.instant()));
        eligibility.seedEligible(PRODUCT, "pub-3", refs);
        engine().runPublish(publishTrigger("pub-3"));

        eligibility.seedOffering(PRODUCT, "v2", refs);
        ingest.ingest(new TemplateWithdrawn("wd-1", PRODUCT, "v2", clock.instant()));
        engine().runWithdrawSupersede(
                new FanOutTrigger(FanOutTriggerKind.WITHDRAW, "wd-1", PRODUCT, 1L, "pub-1"));

        runBackfill();

        assertEveryEventContentFree();
        assertEngagementIdOnlyOnErrorPaths();

        assertThat(memory.eventsFor("ingest.claimed")).hasSize(4);
        assertThat(memory.eventsFor("ingest.duplicate")).hasSize(1);
        assertThat(memory.eventsFor("ingest.deferred")).hasSize(1);
        assertThat(memory.eventsFor("fanout.run.started")).hasSize(4);
        assertThat(memory.eventsFor("fanout.run.completed")).hasSize(4);
        assertThat(memory.eventsFor("fanout.retry")).isNotEmpty();
        assertThat(memory.eventsFor("fanout.dlq")).isNotEmpty();
        assertThat(memory.eventsFor("fanout.supersede.marked")).hasSize(1);
        assertThat(memory.eventsFor("backfill.batch.admitted")).isNotEmpty();
        assertThat(memory.eventsFor("backfill.batch.completed")).isNotEmpty();
    }

    // ---- wiring ----

    private IngestService ingest() {
        return new IngestService(new IngestProgressRepository(store), triggers, metrics, clock,
                sink);
    }

    private FanOutEngine engine() {
        return new FanOutEngine(eligibility, writer, cursors, deadLetters, metrics,
                FanOutConfig.defaults(), runner, clock::advance, clock, new SeededJitter(7L),
                AdmissionGate.admitAll(metrics), sink);
    }

    private void runBackfill() {
        SeededEngagementEnumerator enumerator = new SeededEngagementEnumerator(7L, 2, 3);
        DownstreamLoadClient broken = new DownstreamLoadClient() {
            @Override
            public String loadEngagement(EngagementRef ref) {
                throw new IllegalStateException("downstream unavailable");
            }

            @Override
            public String loadEngagement(String engagementId, String firmId, String productId) {
                throw new IllegalStateException("downstream unavailable");
            }
        };
        PermitGuardedDownstream downstream = new PermitGuardedDownstream(
                broken, new FakeCapacityBudget(4), clock::advance, clock, metrics,
                FanOutConfig.defaults(), new SeededJitter(7L));
        RetryExecutor retry = new RetryExecutor(RetryPolicy.from(FanOutConfig.defaults()),
                clock::advance, clock, new SeededJitter(7L), metrics, deadLetters, sink);
        BackfillWorker worker = new BackfillWorker(
                enumerator, enumerator.firmIds(), new BackfillCursorRepository(store), cursors,
                index, downstream, retry, AdmissionGate.admitAll(metrics), metrics, deadLetters,
                runner, BackfillConfig.defaults(), clock, sink);
        worker.run();
    }

    // ---- assertions ----

    private void assertEveryEventContentFree() {
        for (LogEvent event : memory.events()) {
            for (Map.Entry<IdentifierField, String> entry : event.identifiers().entrySet()) {
                assertThat(IdentifierField.values()).contains(entry.getKey());
                String value = entry.getValue();
                for (String marker : CONTENT_MARKERS) {
                    assertThat(value.toLowerCase())
                            .as("%s must not carry content marker %s", event.event(), marker)
                            .doesNotContain(marker);
                }
                assertThat(value)
                        .as("%s field %s must not look free-text", event.event(), entry.getKey())
                        .doesNotContainPattern("\\s{3,}");
            }
        }
    }

    private void assertEngagementIdOnlyOnErrorPaths() {
        for (LogEvent event : memory.events()) {
            if (event.identifiers().containsKey(IdentifierField.engagement_id)) {
                assertThat(event.event())
                        .as("engagement_id is error-path only (FR-013)")
                        .isIn("fanout.retry", "fanout.dlq");
            }
        }
    }

    // ---- helpers ----

    private static FanOutTrigger publishTrigger(String publishId) {
        return new FanOutTrigger(FanOutTriggerKind.PUBLISH, publishId, PRODUCT, 1L, null);
    }

    private static List<EngagementRef> refs(int firms, int perFirm) {
        List<EngagementRef> refs = new ArrayList<>(firms * perFirm);
        for (int f = 0; f < firms; f++) {
            for (int e = 0; e < perFirm; e++) {
                refs.add(new EngagementRef(
                        String.format("firm-%04d-eng-%06d", f, e),
                        String.format("firm-%04d", f),
                        PRODUCT, "v1", "EU"));
            }
        }
        return refs;
    }
}
