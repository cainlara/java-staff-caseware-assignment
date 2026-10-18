package com.example.fanout.ports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.example.fanout.adapters.inmemory.NoOpFanOutMetrics;
import com.example.fanout.fakes.RecordingFanOutMetrics;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Pins FanOutMetrics contract (contracts/fanout-metrics.md): no-op impl accepts
 * valid calls and records nothing; invalid args rejected; recording fake captures
 * identifier/measurement-shaped data only (no engagement content).
 */
class FanOutMetricsContractTest {

    @Test
    void noopAcceptsValidCalls() {
        FanOutMetrics metrics = new NoOpFanOutMetrics();
        metrics.increment("fanout.rows.written", 1, "publish_id", "pub-1");
        metrics.recordTime("downstream.load", Duration.ofMillis(5), "engagement_id", "eng-1");
    }

    @Test
    void noopRejectsInvalidArguments() {
        FanOutMetrics metrics = new NoOpFanOutMetrics();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> metrics.increment(null, 1))
                .withMessageContaining("name");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> metrics.increment(" ", 1))
                .withMessageContaining("name");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> metrics.increment("m", -1))
                .withMessageContaining("delta");
        assertThatNullPointerException()
                .isThrownBy(() -> metrics.recordTime("m", null))
                .withMessageContaining("duration");
    }

    @Test
    void recordingFakeCapturesCallsWithIdentifierShapedTags() {
        var metrics = new RecordingFanOutMetrics();
        metrics.increment("fanout.rows.written", 3, "publish_id", "pub-1");
        metrics.recordTime("downstream.load", Duration.ofSeconds(60), "engagement_id", "eng-1");

        assertThat(metrics.getCounters()).hasSize(1);
        assertThat(metrics.getCounters().getFirst().name()).isEqualTo("fanout.rows.written");
        assertThat(metrics.getCounters().getFirst().delta()).isEqualTo(3);
        assertThat(metrics.getCounters().getFirst().tags()).containsExactly("publish_id", "pub-1");

        assertThat(metrics.getTimers()).hasSize(1);
        assertThat(metrics.getTimers().getFirst().duration()).isEqualTo(Duration.ofSeconds(60));
        assertThat(metrics.getTimers().getFirst().tags())
                .containsExactly("engagement_id", "eng-1");
    }

    @Test
    void recordingFakeRejectsInvalidArguments() {
        var metrics = new RecordingFanOutMetrics();
        assertThatIllegalArgumentException().isThrownBy(() -> metrics.increment(" ", 1));
        assertThatIllegalArgumentException().isThrownBy(() -> metrics.increment("m", -1));
        assertThatNullPointerException().isThrownBy(() -> metrics.recordTime("m", null));
    }
}
