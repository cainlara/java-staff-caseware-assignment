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
import org.junit.jupiter.api.Test;

/** US3: combined idempotency across two service instances sharing one store (FR-023). */
class IngestServiceMultiInstanceTest {

    private static final TemplatePublished PUB =
            new TemplatePublished("pub-1", "prod-a", "v1", "v2", Instant.parse("2026-10-01T10:00:00Z"));
    private static final TemplateWithdrawn WD =
            new TemplateWithdrawn("wd-1", "prod-a", "v2", Instant.parse("2026-10-02T10:00:00Z"));

    @Test
    void samePublishToTwoInstancesProducesOneIngestEffect() {
        var sharedStore = new InMemoryCheckpointStore();
        var sinkA = new RecordingFanOutTriggerSink();
        var sinkB = new RecordingFanOutTriggerSink();
        var downstream = new FakeDownstreamLoadClient(Duration.ZERO);
        var instanceA = new IngestService(
                new IngestProgressRepository(sharedStore), sinkA, new RecordingFanOutMetrics());
        var instanceB = new IngestService(
                new IngestProgressRepository(sharedStore), sinkB, new RecordingFanOutMetrics());

        IngestOutcome first = instanceA.ingest(PUB);
        IngestOutcome second = instanceB.ingest(PUB);

        assertThat(first.status()).isEqualTo(IngestStatus.INGESTED);
        assertThat(second.status()).isEqualTo(IngestStatus.DUPLICATE);
        assertThat(sinkA.emitted()).hasSize(1);
        assertThat(sinkB.emitted()).isEmpty();
        assertThat(new IngestProgressRepository(sharedStore).productPublishes("prod-a"))
                .containsExactly("pub-1");
        assertThat(downstream.getCallCount()).isZero();
    }

    @Test
    void sameWithdrawToTwoInstancesProducesOneIngestEffect() {
        var sharedStore = new InMemoryCheckpointStore();
        var sinkA = new RecordingFanOutTriggerSink();
        var sinkB = new RecordingFanOutTriggerSink();
        var instanceA = new IngestService(
                new IngestProgressRepository(sharedStore), sinkA, new RecordingFanOutMetrics());
        var instanceB = new IngestService(
                new IngestProgressRepository(sharedStore), sinkB, new RecordingFanOutMetrics());

        IngestOutcome first = instanceA.ingest(WD);
        IngestOutcome second = instanceB.ingest(WD);

        assertThat(first.status()).isEqualTo(IngestStatus.DEFERRED);
        assertThat(second.status()).isEqualTo(IngestStatus.DUPLICATE);
        assertThat(sinkA.emitted()).isEmpty();
        assertThat(sinkB.emitted()).isEmpty();
        assertThat(new IngestProgressRepository(sharedStore).pendingWithdraws("prod-a", "v2"))
                .containsExactly("wd-1");
    }
}
