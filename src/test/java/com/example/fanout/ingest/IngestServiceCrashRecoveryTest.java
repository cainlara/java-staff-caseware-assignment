package com.example.fanout.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.example.fanout.adapters.inmemory.InMemoryCheckpointStore;
import com.example.fanout.adapters.inmemory.RecordingFanOutTriggerSink;
import com.example.fanout.domain.TemplatePublished;
import com.example.fanout.fakes.FailingCheckpointStore;
import com.example.fanout.fakes.FailingTriggerSink;
import com.example.fanout.fakes.FakeDownstreamLoadClient;
import com.example.fanout.fakes.RecordingFanOutMetrics;
import com.example.fanout.ports.CheckpointKey;
import com.example.fanout.ports.CheckpointKind;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** US3: kill-before-claim and kill-after-claim crash recovery (FR-021/FR-022/FR-024). */
class IngestServiceCrashRecoveryTest {

    private static final TemplatePublished PUB =
            new TemplatePublished("pub-1", "prod-a", "v1", "v2", Instant.parse("2026-10-01T10:00:00Z"));

    @Test
    void killBeforeClaimRedeliveryProcessesOnce() {
        var backing = new InMemoryCheckpointStore();
        var failingStore = new FailingCheckpointStore(backing);
        var sink = new RecordingFanOutTriggerSink();
        var metrics = new RecordingFanOutMetrics();
        var downstream = new FakeDownstreamLoadClient(Duration.ZERO);
        var crashing = new IngestService(new IngestProgressRepository(failingStore), sink, metrics);

        failingStore.armFailures();
        assertThatIllegalStateException().isThrownBy(() -> crashing.ingest(PUB));
        assertThat(sink.emitted()).isEmpty();
        assertThat(backing.read(new CheckpointKey(CheckpointKind.PUBLISH, "pub-1"))).isEmpty();

        failingStore.disarm();
        var recovered = new IngestService(new IngestProgressRepository(backing), sink, metrics);
        IngestOutcome outcome = recovered.ingest(PUB);

        assertThat(outcome.status()).isEqualTo(IngestStatus.INGESTED);
        assertThat(sink.emitted()).hasSize(1);
        assertThat(downstream.getCallCount()).isZero();
    }

    @Test
    void killAfterClaimRedeliveryIsDuplicateWithNoSecondTrigger() {
        var backing = new InMemoryCheckpointStore();
        var sink = new RecordingFanOutTriggerSink();
        var failingSink = new FailingTriggerSink(sink);
        var metrics = new RecordingFanOutMetrics();
        var downstream = new FakeDownstreamLoadClient(Duration.ZERO);
        var crashing = new IngestService(
                new IngestProgressRepository(backing), failingSink, metrics);

        failingSink.armFailures();
        assertThatIllegalStateException().isThrownBy(() -> crashing.ingest(PUB));
        assertThat(sink.emitted()).isEmpty();

        // FR-024: claim value carries identity + ingest metadata for a reconciler.
        var claim = backing.read(new CheckpointKey(CheckpointKind.PUBLISH, "pub-1"));
        assertThat(claim).isPresent();
        var decoded = new IngestProgressRepository(backing).publish("pub-1");
        assertThat(decoded).isPresent();
        assertThat(decoded.get().productId()).isEqualTo("prod-a");
        assertThat(decoded.get().toVersion()).isEqualTo("v2");

        failingSink.disarm();
        IngestOutcome redelivery = crashing.ingest(PUB);

        assertThat(redelivery.status()).isEqualTo(IngestStatus.DUPLICATE);
        assertThat(sink.emitted()).isEmpty();
        assertThat(metrics.getCounters())
                .anySatisfy(c -> assertThat(c.name()).isEqualTo("ingest.duplicate"));
        assertThat(downstream.getCallCount()).isZero();
    }
}
