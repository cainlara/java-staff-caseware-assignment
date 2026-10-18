package com.example.fanout.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.example.fanout.adapters.inmemory.InMemoryCheckpointStore;
import com.example.fanout.adapters.inmemory.RecordingFanOutTriggerSink;
import com.example.fanout.domain.FanOutTriggerKind;
import com.example.fanout.domain.TemplatePublished;
import com.example.fanout.fakes.FakeDownstreamLoadClient;
import com.example.fanout.fakes.RecordingFanOutMetrics;
import com.example.fanout.ports.CheckpointKind;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * US1: idempotent publish ingestion (contracts/ingest-service.md vectors
 * 1-2, 13). Every test asserts zero engagement-load calls (FR-030/FR-041).
 */
class IngestServiceIdempotencyTest {

    private static final TemplatePublished PUB =
            new TemplatePublished("pub-1", "prod-a", "v1", "v2", Instant.parse("2026-10-01T10:00:00Z"));

    private InMemoryCheckpointStore store;
    private IngestProgressRepository progress;
    private RecordingFanOutTriggerSink sink;
    private RecordingFanOutMetrics metrics;
    private FakeDownstreamLoadClient downstream;
    private IngestService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryCheckpointStore();
        progress = new IngestProgressRepository(store);
        sink = new RecordingFanOutTriggerSink();
        metrics = new RecordingFanOutMetrics();
        downstream = new FakeDownstreamLoadClient(java.time.Duration.ZERO);
        service = new IngestService(progress, sink, metrics);
    }

    @Test
    void freshPublishIngestedOnceWithSingleTrigger() {
        IngestOutcome outcome = service.ingest(PUB);

        assertThat(outcome.status()).isEqualTo(IngestStatus.INGESTED);
        assertThat(outcome.eventId()).isEqualTo("pub-1");
        assertThat(outcome.resolvedWithdrawIds()).isEmpty();

        assertThat(sink.emitted()).hasSize(1);
        var trigger = sink.emitted().get(0);
        assertThat(trigger.kind()).isEqualTo(FanOutTriggerKind.PUBLISH);
        assertThat(trigger.eventId()).isEqualTo("pub-1");
        assertThat(trigger.productId()).isEqualTo("prod-a");
        assertThat(trigger.ingestSequence()).isEqualTo(1L);
        assertThat(trigger.matchedPublishId()).isNull();

        assertThat(progress.publish("pub-1")).isPresent();
        assertThat(progress.publish("pub-1").get().toVersion()).isEqualTo("v2");
        assertThat(progress.productPublishes("prod-a")).containsExactly("pub-1");

        assertThat(metrics.getCounters())
                .anySatisfy(c -> assertThat(c.name()).isEqualTo("ingest.published"));
        assertThat(downstream.getCallCount()).isZero();
    }

    @Test
    void duplicatePublishAckedWithNoNewTriggerAndDuplicateCounter() {
        service.ingest(PUB);
        sink.clear();

        IngestOutcome outcome = service.ingest(PUB);

        assertThat(outcome.status()).isEqualTo(IngestStatus.DUPLICATE);
        assertThat(outcome.eventId()).isEqualTo("pub-1");
        assertThat(sink.emitted()).isEmpty();
        assertThat(metrics.getCounters())
                .anySatisfy(c -> assertThat(c.name()).isEqualTo("ingest.duplicate"));
        assertThat(progress.productPublishes("prod-a")).containsExactly("pub-1");
        assertThat(downstream.getCallCount()).isZero();
    }

    @Test
    void tripleDeliveryStillOneTriggerOneRecord() {
        service.ingest(PUB);
        service.ingest(PUB);
        IngestOutcome third = service.ingest(PUB);

        assertThat(third.status()).isEqualTo(IngestStatus.DUPLICATE);
        assertThat(sink.emitted()).hasSize(1);
        assertThat(progress.productPublishes("prod-a")).containsExactly("pub-1");
        assertThat(downstream.getCallCount()).isZero();
    }

    @Test
    void nullEventRejectedWithNoSideEffects() {
        assertThatNullPointerException().isThrownBy(() -> service.ingest((TemplatePublished) null));
        assertThat(sink.emitted()).isEmpty();
        assertThat(store.read(new com.example.fanout.ports.CheckpointKey(CheckpointKind.PUBLISH, "pub-1")))
                .isEmpty();
        assertThat(downstream.getCallCount()).isZero();
    }
}
