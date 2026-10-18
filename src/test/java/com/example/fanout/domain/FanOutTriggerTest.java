package com.example.fanout.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

/** Pins FanOutTrigger validation per data-model.md. */
class FanOutTriggerTest {

    @Test
    void validPublishTriggerConstructs() {
        var t = new FanOutTrigger(FanOutTriggerKind.PUBLISH, "pub-1", "prod-a", 1L, null);
        assertThat(t.kind()).isEqualTo(FanOutTriggerKind.PUBLISH);
        assertThat(t.eventId()).isEqualTo("pub-1");
        assertThat(t.productId()).isEqualTo("prod-a");
        assertThat(t.ingestSequence()).isEqualTo(1L);
        assertThat(t.matchedPublishId()).isNull();
    }

    @Test
    void validWithdrawTriggerConstructs() {
        var t = new FanOutTrigger(FanOutTriggerKind.WITHDRAW, "wd-1", "prod-a", 3L, "pub-9");
        assertThat(t.matchedPublishId()).isEqualTo("pub-9");
    }

    @Test
    void nullKindRejected() {
        assertThatNullPointerException().isThrownBy(
                () -> new FanOutTrigger(null, "pub-1", "prod-a", 1L, null));
    }

    @Test
    void blankEventIdRejected() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new FanOutTrigger(FanOutTriggerKind.PUBLISH, "  ", "prod-a", 1L, null));
    }

    @Test
    void blankProductIdRejected() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new FanOutTrigger(FanOutTriggerKind.PUBLISH, "pub-1", "", 1L, null));
    }

    @Test
    void ingestSequenceBelowOneRejected() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new FanOutTrigger(FanOutTriggerKind.PUBLISH, "pub-1", "prod-a", 0L, null));
    }

    @Test
    void withdrawWithoutMatchedPublishRejected() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new FanOutTrigger(FanOutTriggerKind.WITHDRAW, "wd-1", "prod-a", 1L, null));
    }

    @Test
    void publishWithMatchedPublishRejected() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new FanOutTrigger(FanOutTriggerKind.PUBLISH, "pub-1", "prod-a", 1L, "pub-9"));
    }
}
