package com.example.fanout.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class EngagementRefTest {

    @Test
    void validInstanceConstructs() {
        var ref = new EngagementRef("eng-1", "firm-1", "prod-1", "v1", "EU");
        assertThat(ref.engagementId()).isEqualTo("eng-1");
        assertThat(ref.region()).isEqualTo("EU");
    }

    @Test
    void nullEngagementIdRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EngagementRef(null, "firm-1", "prod-1", "v1", "EU"))
                .withMessageContaining("engagementId");
    }

    @Test
    void blankEngagementIdRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EngagementRef("  ", "firm-1", "prod-1", "v1", "EU"))
                .withMessageContaining("engagementId");
    }

    @Test
    void nullFirmIdRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EngagementRef("eng-1", null, "prod-1", "v1", "EU"))
                .withMessageContaining("firmId");
    }

    @Test
    void blankFirmIdRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EngagementRef("eng-1", "", "prod-1", "v1", "EU"))
                .withMessageContaining("firmId");
    }

    @Test
    void nullProductIdRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EngagementRef("eng-1", "firm-1", null, "v1", "EU"))
                .withMessageContaining("productId");
    }

    @Test
    void blankCurrentVersionRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EngagementRef("eng-1", "firm-1", "prod-1", " ", "EU"))
                .withMessageContaining("currentVersion");
    }

    @Test
    void nullRegionRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EngagementRef("eng-1", "firm-1", "prod-1", "v1", null))
                .withMessageContaining("region");
    }

    @Test
    void blankRegionRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EngagementRef("eng-1", "firm-1", "prod-1", "v1", ""))
                .withMessageContaining("region");
    }
}
