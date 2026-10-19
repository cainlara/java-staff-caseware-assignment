package com.example.fanout.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class FanOutRunOutcomeTest {

    private static FanOutRunOutcome outcome(FanOutOutcomeStatus status, int chunkCount,
                                            long rowsResolved, int deadLetterCount) {
        return new FanOutRunOutcome(status, "pub-1", chunkCount, rowsResolved, deadLetterCount);
    }

    @Test
    void acceptsCompletedOutcome() {
        var o = outcome(FanOutOutcomeStatus.COMPLETED, 4, 2000, 0);
        assertThat(o.status()).isEqualTo(FanOutOutcomeStatus.COMPLETED);
        assertThat(o.rowsResolved()).isEqualTo(2000);
    }

    @Test
    void acceptsZeroCounts() {
        var o = outcome(FanOutOutcomeStatus.COMPLETED_EMPTY, 0, 0, 0);
        assertThat(o.chunkCount()).isZero();
    }

    @Test
    void acceptsDeadLetteredOutcome() {
        var o = outcome(FanOutOutcomeStatus.FAILED_DEAD_LETTERED, 4, 1500, 2);
        assertThat(o.deadLetterCount()).isEqualTo(2);
    }

    @Test
    void rejectsNullStatus() {
        assertThatNullPointerException().isThrownBy(() -> outcome(null, 1, 1, 0));
    }

    @Test
    void rejectsBlankEventId() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new FanOutRunOutcome(FanOutOutcomeStatus.COMPLETED, " ", 1, 1, 0))
                .withMessageContaining("eventId");
    }

    @Test
    void rejectsNegativeCounts() {
        assertThatIllegalArgumentException().isThrownBy(() -> outcome(FanOutOutcomeStatus.COMPLETED, -1, 0, 0))
                .withMessageContaining("chunkCount");
        assertThatIllegalArgumentException().isThrownBy(() -> outcome(FanOutOutcomeStatus.COMPLETED, 1, -1, 0))
                .withMessageContaining("rowsResolved");
        assertThatIllegalArgumentException().isThrownBy(() -> outcome(FanOutOutcomeStatus.COMPLETED, 1, 0, -1))
                .withMessageContaining("deadLetterCount");
    }
}
