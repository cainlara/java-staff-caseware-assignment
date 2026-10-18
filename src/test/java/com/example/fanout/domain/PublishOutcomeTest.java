package com.example.fanout.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class PublishOutcomeTest {

    @Test
    void validInstanceConstructs() {
        var outcome = new PublishOutcome("pub-1", 10, 2, 1, PublishStatus.PARTIAL);
        assertThat(outcome.rowsWritten()).isEqualTo(10);
        assertThat(outcome.status()).isEqualTo(PublishStatus.PARTIAL);
    }

    @Test
    void nullPublishIdRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PublishOutcome(null, 0, 0, 0, PublishStatus.COMPLETED))
                .withMessageContaining("publishId");
    }

    @Test
    void blankPublishIdRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PublishOutcome("", 0, 0, 0, PublishStatus.COMPLETED))
                .withMessageContaining("publishId");
    }

    @Test
    void negativeRowsWrittenRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PublishOutcome("pub-1", -1, 0, 0, PublishStatus.COMPLETED))
                .withMessageContaining("rowsWritten");
    }

    @Test
    void negativeRowsSkippedRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PublishOutcome("pub-1", 0, -1, 0, PublishStatus.COMPLETED))
                .withMessageContaining("rowsSkipped");
    }

    @Test
    void negativeFailuresRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PublishOutcome("pub-1", 0, 0, -1, PublishStatus.FAILED))
                .withMessageContaining("failures");
    }

    @Test
    void nullStatusRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new PublishOutcome("pub-1", 0, 0, 0, null))
                .withMessageContaining("status");
    }
}
