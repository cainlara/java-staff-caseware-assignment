package com.example.fanout.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class TemplateWithdrawnTest {

    private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");

    @Test
    void validInstanceConstructs() {
        var event = new TemplateWithdrawn("wd-1", "prod-1", "v2", NOW);
        assertThat(event.withdrawId()).isEqualTo("wd-1");
    }

    @Test
    void nullWithdrawIdRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TemplateWithdrawn(null, "prod-1", "v2", NOW))
                .withMessageContaining("withdrawId");
    }

    @Test
    void blankWithdrawIdRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TemplateWithdrawn(" ", "prod-1", "v2", NOW))
                .withMessageContaining("withdrawId");
    }

    @Test
    void nullProductIdRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TemplateWithdrawn("wd-1", null, "v2", NOW))
                .withMessageContaining("productId");
    }

    @Test
    void blankVersionRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TemplateWithdrawn("wd-1", "prod-1", "", NOW))
                .withMessageContaining("version");
    }

    @Test
    void nullWithdrawnAtRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new TemplateWithdrawn("wd-1", "prod-1", "v2", null))
                .withMessageContaining("withdrawnAt");
    }
}
