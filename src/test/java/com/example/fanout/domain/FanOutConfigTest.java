package com.example.fanout.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class FanOutConfigTest {

    @Test
    void defaultsMatchSpecAssumptions() {
        var config = FanOutConfig.defaults();
        assertThat(config.chunkSize()).isEqualTo(500);
        assertThat(config.poolBound()).isEqualTo(16);
        assertThat(config.firmInFlightCap()).isEqualTo(2);
        assertThat(config.retryMaxAttempts()).isEqualTo(5);
        assertThat(config.retryBaseDelay()).isEqualTo(Duration.ofMillis(100));
        assertThat(config.retryMaxDelay()).isEqualTo(Duration.ofSeconds(30));
        assertThat(config.jitterFactorMin()).isEqualTo(0.5);
        assertThat(config.jitterFactorMax()).isEqualTo(1.5);
    }

    @Test
    void rejectsChunkSizeBelowOne() {
        assertThatIllegalArgumentException().isThrownBy(() -> new FanOutConfig(
                0, 16, 2, 5, Duration.ofMillis(100), Duration.ofSeconds(30), 0.5, 1.5))
                .withMessageContaining("chunkSize");
    }

    @Test
    void rejectsPoolBoundBelowOne() {
        assertThatIllegalArgumentException().isThrownBy(() -> new FanOutConfig(
                500, 0, 2, 5, Duration.ofMillis(100), Duration.ofSeconds(30), 0.5, 1.5))
                .withMessageContaining("poolBound");
    }

    @Test
    void rejectsFirmCapBelowOne() {
        assertThatIllegalArgumentException().isThrownBy(() -> new FanOutConfig(
                500, 16, 0, 5, Duration.ofMillis(100), Duration.ofSeconds(30), 0.5, 1.5))
                .withMessageContaining("firmInFlightCap");
    }

    @Test
    void rejectsNonPositiveBaseDelay() {
        assertThatIllegalArgumentException().isThrownBy(() -> new FanOutConfig(
                500, 16, 2, 5, Duration.ZERO, Duration.ofSeconds(30), 0.5, 1.5))
                .withMessageContaining("retryBaseDelay");
        assertThatNullPointerException().isThrownBy(() -> new FanOutConfig(
                500, 16, 2, 5, null, Duration.ofSeconds(30), 0.5, 1.5));
    }

    @Test
    void rejectsMaxDelayBelowBaseDelay() {
        assertThatIllegalArgumentException().isThrownBy(() -> new FanOutConfig(
                500, 16, 2, 5, Duration.ofSeconds(30), Duration.ofMillis(100), 0.5, 1.5))
                .withMessageContaining("retryMaxDelay");
    }

    @Test
    void rejectsInvalidJitterBounds() {
        assertThatIllegalArgumentException().isThrownBy(() -> new FanOutConfig(
                500, 16, 2, 5, Duration.ofMillis(100), Duration.ofSeconds(30), -0.1, 1.5))
                .withMessageContaining("jitterFactorMin");
        assertThatIllegalArgumentException().isThrownBy(() -> new FanOutConfig(
                500, 16, 2, 5, Duration.ofMillis(100), Duration.ofSeconds(30), 1.5, 1.5))
                .withMessageContaining("jitterFactorMax");
    }

    @Test
    void acceptsEqualJitterMinZeroAndRetryAttemptsOne() {
        var config = new FanOutConfig(1, 1, 1, 1, Duration.ofMillis(1), Duration.ofMillis(1), 0.0, 1.5);
        assertThat(config.retryMaxAttempts()).isEqualTo(1);
    }
}
