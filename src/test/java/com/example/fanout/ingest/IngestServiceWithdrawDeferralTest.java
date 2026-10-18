package com.example.fanout.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.fanout.adapters.inmemory.InMemoryCheckpointStore;
import com.example.fanout.adapters.inmemory.RecordingFanOutTriggerSink;
import com.example.fanout.domain.FanOutTriggerKind;
import com.example.fanout.domain.TemplatePublished;
import com.example.fanout.domain.TemplateWithdrawn;
import com.example.fanout.fakes.FakeDownstreamLoadClient;
import com.example.fanout.fakes.RecordingFanOutMetrics;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * US2: withdraw deferral and resolution (contracts/ingest-service.md vectors
 * 3-6). Every test asserts zero engagement-load calls.
 */
class IngestServiceWithdrawDeferralTest {

    private static final TemplatePublished PUB_V2 = new TemplatePublished(
            "pub-1", "prod-a", "v1", "v2", Instant.parse("2026-10-01T10:00:00Z"));
    private static final TemplateWithdrawn WD_V2 = new TemplateWithdrawn(
            "wd-1", "prod-a", "v2", Instant.parse("2026-10-02T10:00:00Z"));

    private IngestProgressRepository progress;
    private RecordingFanOutTriggerSink sink;
    private RecordingFanOutMetrics metrics;
    private FakeDownstreamLoadClient downstream;
    private IngestService service;

    @BeforeEach
    void setUp() {
        progress = new IngestProgressRepository(new InMemoryCheckpointStore());
        sink = new RecordingFanOutTriggerSink();
        metrics = new RecordingFanOutMetrics();
        downstream = new FakeDownstreamLoadClient(Duration.ZERO);
        service = new IngestService(progress, sink, metrics);
    }

    @Test
    void withdrawBeforePublishDeferredNotDropped() {
        IngestOutcome outcome = service.ingest(WD_V2);

        assertThat(outcome.status()).isEqualTo(IngestStatus.DEFERRED);
        assertThat(outcome.eventId()).isEqualTo("wd-1");
        assertThat(sink.emitted()).isEmpty();

        var record = progress.withdraw("wd-1");
        assertThat(record).isPresent();
        assertThat(record.get().status()).isEqualTo(WithdrawStatus.DEFERRED);
        assertThat(progress.pendingWithdraws("prod-a", "v2")).containsExactly("wd-1");
        assertThat(metrics.getCounters())
                .anySatisfy(c -> assertThat(c.name()).isEqualTo("ingest.deferred"));
        assertThat(downstream.getCallCount()).isZero();
    }

    @Test
    void deferredWithdrawResolvesWhenMatchingPublishArrives() {
        service.ingest(WD_V2);
        sink.clear();

        IngestOutcome publishOutcome = service.ingest(PUB_V2);

        assertThat(publishOutcome.status()).isEqualTo(IngestStatus.INGESTED);
        assertThat(publishOutcome.resolvedWithdrawIds()).containsExactly("wd-1");

        var record = progress.withdraw("wd-1");
        assertThat(record.get().status()).isEqualTo(WithdrawStatus.RESOLVED);
        assertThat(record.get().matchedPublishId()).isEqualTo("pub-1");

        assertThat(sink.emitted()).hasSize(2);
        assertThat(sink.emitted().get(0).kind()).isEqualTo(FanOutTriggerKind.PUBLISH);
        assertThat(sink.emitted().get(1).kind()).isEqualTo(FanOutTriggerKind.WITHDRAW);
        assertThat(sink.emitted().get(1).eventId()).isEqualTo("wd-1");
        assertThat(sink.emitted().get(1).matchedPublishId()).isEqualTo("pub-1");

        assertThat(progress.pendingWithdraws("prod-a", "v2")).isEmpty();
        assertThat(downstream.getCallCount()).isZero();
    }

    @Test
    void withdrawAfterItsPublishResolvesImmediately() {
        service.ingest(PUB_V2);
        sink.clear();

        IngestOutcome outcome = service.ingest(WD_V2);

        assertThat(outcome.status()).isEqualTo(IngestStatus.INGESTED);
        assertThat(sink.emitted()).hasSize(1);
        assertThat(sink.emitted().get(0).kind()).isEqualTo(FanOutTriggerKind.WITHDRAW);
        assertThat(sink.emitted().get(0).matchedPublishId()).isEqualTo("pub-1");
        assertThat(progress.withdraw("wd-1").get().status()).isEqualTo(WithdrawStatus.RESOLVED);
        assertThat(downstream.getCallCount()).isZero();
    }

    @Test
    void duplicateWithdrawAfterDeferralIsDuplicatePath() {
        service.ingest(WD_V2);
        sink.clear();

        IngestOutcome outcome = service.ingest(WD_V2);

        assertThat(outcome.status()).isEqualTo(IngestStatus.DUPLICATE);
        assertThat(sink.emitted()).isEmpty();
        assertThat(metrics.getCounters())
                .anySatisfy(c -> assertThat(c.name()).isEqualTo("ingest.duplicate"));
        assertThat(progress.pendingWithdraws("prod-a", "v2")).containsExactly("wd-1");
        assertThat(downstream.getCallCount()).isZero();
    }

    @Test
    void duplicateWithdrawAfterResolutionIsDuplicatePath() {
        service.ingest(PUB_V2);
        service.ingest(WD_V2);
        sink.clear();

        IngestOutcome outcome = service.ingest(WD_V2);

        assertThat(outcome.status()).isEqualTo(IngestStatus.DUPLICATE);
        assertThat(sink.emitted()).isEmpty();
        assertThat(progress.withdraw("wd-1").get().status()).isEqualTo(WithdrawStatus.RESOLVED);
        assertThat(downstream.getCallCount()).isZero();
    }

    @Test
    void withdrawWithNoMatchingPublishEverStaysDeferred() {
        var otherVersion = new TemplateWithdrawn(
                "wd-x", "prod-a", "v9", Instant.parse("2026-10-03T10:00:00Z"));

        IngestOutcome outcome = service.ingest(otherVersion);

        assertThat(outcome.status()).isEqualTo(IngestStatus.DEFERRED);
        assertThat(progress.withdraw("wd-x").get().status()).isEqualTo(WithdrawStatus.DEFERRED);
        assertThat(progress.pendingWithdraws("prod-a", "v9")).containsExactly("wd-x");
        assertThat(downstream.getCallCount()).isZero();
    }

    @Test
    void duplicateDeliveredMatchingPublishStillSatisfiesResolution() {
        service.ingest(WD_V2);
        service.ingest(PUB_V2);
        sink.clear();

        IngestOutcome duplicatePublish = service.ingest(PUB_V2);

        assertThat(duplicatePublish.status()).isEqualTo(IngestStatus.DUPLICATE);
        assertThat(duplicatePublish.resolvedWithdrawIds()).isEmpty();
        assertThat(sink.emitted()).isEmpty();
        assertThat(progress.withdraw("wd-1").get().status()).isEqualTo(WithdrawStatus.RESOLVED);
        assertThat(downstream.getCallCount()).isZero();
    }

    @Test
    void twoDistinctWithdrawsSameVersionEachResolveOnce() {
        var second = new TemplateWithdrawn("wd-2", "prod-a", "v2", Instant.parse("2026-10-02T11:00:00Z"));
        service.ingest(WD_V2);
        service.ingest(second);
        sink.clear();

        IngestOutcome publishOutcome = service.ingest(PUB_V2);

        assertThat(publishOutcome.resolvedWithdrawIds())
                .containsExactlyInAnyOrder("wd-1", "wd-2");
        assertThat(sink.emitted())
                .filteredOn(t -> t.kind() == FanOutTriggerKind.WITHDRAW)
                .hasSize(2);
        assertThat(progress.pendingWithdraws("prod-a", "v2")).isEmpty();
        assertThat(downstream.getCallCount()).isZero();
    }
}
