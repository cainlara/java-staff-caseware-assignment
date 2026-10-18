package com.example.fanout.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class FanOutTaskTest {

    @Test
    void validInstanceConstructs() {
        var task = new FanOutTask("pub-1", "batch-1", 0, 500);
        assertThat(task.batchIndex()).isZero();
        assertThat(task.batchSize()).isEqualTo(500);
    }

    @Test
    void nullPublishIdRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new FanOutTask(null, "batch-1", 0, 500))
                .withMessageContaining("publishId");
    }

    @Test
    void blankBatchIdRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new FanOutTask("pub-1", " ", 0, 500))
                .withMessageContaining("batchId");
    }

    @Test
    void negativeBatchIndexRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new FanOutTask("pub-1", "batch-1", -1, 500))
                .withMessageContaining("batchIndex");
    }

    @Test
    void zeroBatchSizeRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new FanOutTask("pub-1", "batch-1", 0, 0))
                .withMessageContaining("batchSize");
    }
}
