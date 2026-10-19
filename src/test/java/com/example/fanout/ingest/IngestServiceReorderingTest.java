package com.example.fanout.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.fanout.adapters.inmemory.InMemoryCheckpointStore;
import com.example.fanout.adapters.inmemory.RecordingFanOutTriggerSink;
import com.example.fanout.domain.TemplatePublished;
import com.example.fanout.domain.TemplateWithdrawn;
import com.example.fanout.fakes.FakeDownstreamLoadClient;
import com.example.fanout.fakes.RecordingFanOutMetrics;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** US4: out-of-order publishes retained; effective version independent of arrival order (R4, FR-013). */
class IngestServiceReorderingTest {

    private static final TemplatePublished PUB_A = new TemplatePublished(
            "pub-a", "prod-a", "v0", "v1", Instant.parse("2026-10-01T09:00:00Z"));
    private static final TemplatePublished PUB_B = new TemplatePublished(
            "pub-b", "prod-a", "v1", "v2", Instant.parse("2026-10-01T10:00:00Z"));

    private IngestProgressRepository progress;
    private RecordingFanOutTriggerSink sink;
    private IngestService service;
    private FakeDownstreamLoadClient downstream;

    @BeforeEach
    void setUp() {
        progress = new IngestProgressRepository(new InMemoryCheckpointStore());
        sink = new RecordingFanOutTriggerSink();
        service = new IngestService(progress, sink, new RecordingFanOutMetrics());
        downstream = new FakeDownstreamLoadClient(Duration.ZERO);
    }

    @Test
    void orderABBothRetainedArrivalOrderRecorded() {
        assertThat(service.ingest(PUB_A).status()).isEqualTo(IngestStatus.INGESTED);
        assertThat(service.ingest(PUB_B).status()).isEqualTo(IngestStatus.INGESTED);

        assertThat(progress.productPublishes("prod-a")).containsExactly("pub-a", "pub-b");
        assertThat(sink.emitted()).hasSize(2);
        assertThat(sink.emitted().get(0).ingestSequence()).isEqualTo(1L);
        assertThat(sink.emitted().get(1).ingestSequence()).isEqualTo(2L);
        assertThat(downstream.getCallCount()).isZero();
    }

    @Test
    void orderBABothRetainedArrivalOrderRecorded() {
        assertThat(service.ingest(PUB_B).status()).isEqualTo(IngestStatus.INGESTED);
        assertThat(service.ingest(PUB_A).status()).isEqualTo(IngestStatus.INGESTED);

        assertThat(progress.productPublishes("prod-a")).containsExactly("pub-b", "pub-a");
        assertThat(sink.emitted().get(0).eventId()).isEqualTo("pub-b");
        assertThat(sink.emitted().get(1).eventId()).isEqualTo("pub-a");
        assertThat(downstream.getCallCount()).isZero();
    }

    @Test
    void effectiveVersionIdenticalForBothArrivalOrders() {
        var storeAb = new InMemoryCheckpointStore();
        var svcAb = new IngestService(
                new IngestProgressRepository(storeAb), new RecordingFanOutTriggerSink(),
                new RecordingFanOutMetrics());
        svcAb.ingest(PUB_A);
        svcAb.ingest(PUB_B);

        var storeBa = new InMemoryCheckpointStore();
        var svcBa = new IngestService(
                new IngestProgressRepository(storeBa), new RecordingFanOutTriggerSink(),
                new RecordingFanOutMetrics());
        svcBa.ingest(PUB_B);
        svcBa.ingest(PUB_A);

        var effectiveAb = effectiveFrom(storeAb);
        var effectiveBa = effectiveFrom(storeBa);
        assertThat(effectiveAb).isEqualTo(effectiveBa);
        assertThat(effectiveAb).hasValueSatisfying(p -> assertThat(p.publishId()).isEqualTo("pub-b"));
    }

    @Test
    void republishToSameVersionBothRetainedTieBreakByPublishId() {
        var older = new TemplatePublished(
                "pub-x", "prod-a", "v1", "v2", Instant.parse("2026-10-01T10:00:00Z"));
        var newerSameInstant = new TemplatePublished(
                "pub-y", "prod-a", "v1", "v2", Instant.parse("2026-10-01T10:00:00Z"));

        service.ingest(older);
        service.ingest(newerSameInstant);

        assertThat(progress.productPublishes("prod-a")).containsExactly("pub-x", "pub-y");
        var effective = EffectiveVersion.selectEffective(loadAll());
        assertThat(effective).hasValueSatisfying(p -> assertThat(p.publishId()).isEqualTo("pub-y"));
    }

    @Test
    void withdrawBindsToGreatestMatchAmongSameVersionPublishes() {
        var older = new TemplatePublished(
                "pub-x", "prod-a", "v1", "v2", Instant.parse("2026-10-01T10:00:00Z"));
        var newer = new TemplatePublished(
                "pub-y", "prod-a", "v1", "v2", Instant.parse("2026-10-02T10:00:00Z"));
        service.ingest(older);
        service.ingest(newer);

        var wd = new TemplateWithdrawn("wd-1", "prod-a", "v2", Instant.parse("2026-10-03T10:00:00Z"));
        IngestOutcome outcome = service.ingest(wd);

        assertThat(outcome.status()).isEqualTo(IngestStatus.INGESTED);
        assertThat(progress.withdraw("wd-1").get().matchedPublishId()).isEqualTo("pub-y");
        assertThat(downstream.getCallCount()).isZero();
    }

    private Optional<IngestedPublish> effectiveFrom(InMemoryCheckpointStore store) {
        var repo = new IngestProgressRepository(store);
        return EffectiveVersion.selectEffective(loadAll(repo));
    }

    private List<IngestedPublish> loadAll() {
        return loadAll(progress);
    }

    private static List<IngestedPublish> loadAll(IngestProgressRepository repo) {
        return repo.productPublishes("prod-a").stream()
                .map(id -> repo.publish(id).orElseThrow())
                .toList();
    }
}
