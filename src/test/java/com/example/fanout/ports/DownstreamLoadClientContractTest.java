package com.example.fanout.ports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.fanout.domain.EngagementRef;
import com.example.fanout.fakes.FakeDownstreamLoadClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Pins DownstreamLoadClient contract (contracts/downstream-load-client.md):
 * fake honors configured latency, default simulated latency ~60 s (asserted on
 * configuration, not by sleeping), failure injection throws then recovers,
 * null ref rejected.
 */
class DownstreamLoadClientContractTest {

    private static final EngagementRef REF =
            new EngagementRef("eng-1", "firm-1", "prod-1", "v1", "EU");

    @Test
    void configuredLatencyIsHonored() {
        var fake = new FakeDownstreamLoadClient(Duration.ofMillis(120));
        long start = System.nanoTime();
        String version = fake.loadEngagement(REF);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(version).isNotBlank();
        assertThat(elapsedMs).isGreaterThanOrEqualTo(100);
    }

    @Test
    void defaultSimulatedLatencyIsSixtySeconds() {
        var fake = new FakeDownstreamLoadClient();
        assertThat(fake.getSimulatedLatency()).isEqualTo(Duration.ofSeconds(60));
        assertThat(FakeDownstreamLoadClient.DEFAULT_SIMULATED_LATENCY)
                .isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void injectedFailureThrowsThenSucceedsAfterReset() {
        var fake = new FakeDownstreamLoadClient(Duration.ZERO);
        fake.failNextCalls(1);
        assertThatThrownBy(() -> fake.loadEngagement(REF))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("injected");
        assertThat(fake.loadEngagement(REF)).isNotBlank();
    }

    @Test
    void nullEngagementRefRejected() {
        var fake = new FakeDownstreamLoadClient(Duration.ZERO);
        assertThatNullPointerException().isThrownBy(() -> fake.loadEngagement(null));
    }
}
