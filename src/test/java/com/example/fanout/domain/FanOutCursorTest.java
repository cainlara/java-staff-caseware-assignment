package com.example.fanout.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class FanOutCursorTest {

    private static FanOutCursor cursor(FanOutRunStatus status, int totalChunks, int lastCompleted) {
        return new FanOutCursor(FanOutRunKind.PUBLISH, "pub-1", "prod-1", status, totalChunks, lastCompleted);
    }

    @Test
    void acceptsRunningCursorWithNoCompletedChunk() {
        var c = cursor(FanOutRunStatus.RUNNING, 4, -1);
        assertThat(c.lastCompletedChunkIndex()).isEqualTo(-1);
    }

    @Test
    void acceptsRunningCursorWithPartialProgress() {
        assertThat(cursor(FanOutRunStatus.RUNNING, 4, 2).totalChunks()).isEqualTo(4);
    }

    @Test
    void acceptsCompleteCursorAtFinalChunk() {
        assertThat(cursor(FanOutRunStatus.COMPLETE, 4, 3).status()).isEqualTo(FanOutRunStatus.COMPLETE);
    }

    @Test
    void acceptsCompleteEmptyRun() {
        var c = cursor(FanOutRunStatus.COMPLETE, 0, -1);
        assertThat(c.totalChunks()).isZero();
    }

    @Test
    void rejectsNegativeTotalChunks() {
        assertThatIllegalArgumentException().isThrownBy(() -> cursor(FanOutRunStatus.RUNNING, -1, -1))
                .withMessageContaining("totalChunks");
    }

    @Test
    void rejectsLastCompletedBelowMinusOne() {
        assertThatIllegalArgumentException().isThrownBy(() -> cursor(FanOutRunStatus.RUNNING, 4, -2))
                .withMessageContaining("lastCompletedChunkIndex");
    }

    @Test
    void rejectsLastCompletedAtOrAboveTotalChunks() {
        assertThatIllegalArgumentException().isThrownBy(() -> cursor(FanOutRunStatus.RUNNING, 4, 4));
        assertThatIllegalArgumentException().isThrownBy(() -> cursor(FanOutRunStatus.RUNNING, 4, 5));
    }

    @Test
    void rejectsIncompleteCompleteCursor() {
        assertThatIllegalArgumentException().isThrownBy(() -> cursor(FanOutRunStatus.COMPLETE, 4, 2))
                .withMessageContaining("totalChunks - 1");
    }

    @Test
    void rejectsNullFields() {
        assertThatNullPointerException().isThrownBy(
                () -> new FanOutCursor(null, "pub-1", "prod-1", FanOutRunStatus.RUNNING, 1, -1));
        assertThatNullPointerException().isThrownBy(
                () -> new FanOutCursor(FanOutRunKind.PUBLISH, "pub-1", "prod-1", null, 1, -1));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new FanOutCursor(FanOutRunKind.PUBLISH, " ", "prod-1", FanOutRunStatus.RUNNING, 1, -1));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new FanOutCursor(FanOutRunKind.PUBLISH, "pub-1", "", FanOutRunStatus.RUNNING, 1, -1));
    }
}
