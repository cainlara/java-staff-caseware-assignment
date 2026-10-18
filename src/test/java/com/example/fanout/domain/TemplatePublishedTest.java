package com.example.fanout.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class TemplatePublishedTest {

    private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");

    @Test
    void validInstanceConstructs() {
        var event = new TemplatePublished("pub-1", "prod-1", "v1", "v2", NOW);
        assertThat(event.publishId()).isEqualTo("pub-1");
        assertThat(event.publishedAt()).isEqualTo(NOW);
    }

    @Test
    void nullPublishIdRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TemplatePublished(null, "prod-1", "v1", "v2", NOW))
                .withMessageContaining("publishId");
    }

    @Test
    void blankPublishIdRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TemplatePublished("   ", "prod-1", "v1", "v2", NOW))
                .withMessageContaining("publishId");
    }

    @Test
    void nullProductIdRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TemplatePublished("pub-1", null, "v1", "v2", NOW))
                .withMessageContaining("productId");
    }

    @Test
    void blankProductIdRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TemplatePublished("pub-1", "", "v1", "v2", NOW))
                .withMessageContaining("productId");
    }

    @Test
    void nullFromVersionRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TemplatePublished("pub-1", "prod-1", null, "v2", NOW))
                .withMessageContaining("fromVersion");
    }

    @Test
    void blankToVersionRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TemplatePublished("pub-1", "prod-1", "v1", "  ", NOW))
                .withMessageContaining("toVersion");
    }

    @Test
    void nullPublishedAtRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new TemplatePublished("pub-1", "prod-1", "v1", "v2", null))
                .withMessageContaining("publishedAt");
    }
}
