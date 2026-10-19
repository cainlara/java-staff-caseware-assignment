package com.example.fanout.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FanOutTaskTest {

    private static EngagementRef ref(String engagementId, String firmId) {
        return new EngagementRef(engagementId, firmId, "prod-1", "v1", "EU");
    }

    private static List<EngagementRef> refs() {
        return List.of(ref("eng-1", "firm-1"), ref("eng-2", "firm-1"));
    }

    @Test
    void constructsValidTask() {
        var task = new FanOutTask("pub-1", "prod-1", FanOutRunKind.PUBLISH, 0, "firm-1", refs());
        assertThat(task.eventId()).isEqualTo("pub-1");
        assertThat(task.productId()).isEqualTo("prod-1");
        assertThat(task.kind()).isEqualTo(FanOutRunKind.PUBLISH);
        assertThat(task.chunkIndex()).isEqualTo(0);
        assertThat(task.firmId()).isEqualTo("firm-1");
        assertThat(task.engagements()).hasSize(2);
    }

    @Test
    void rejectsNullEventId() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new FanOutTask(null, "prod-1", FanOutRunKind.PUBLISH, 0, "firm-1", refs()))
                .withMessageContaining("eventId");
    }

    @Test
    void rejectsBlankProductId() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new FanOutTask("pub-1", " ", FanOutRunKind.PUBLISH, 0, "firm-1", refs()));
    }

    @Test
    void rejectsNullKind() {
        assertThatNullPointerException().isThrownBy(
                () -> new FanOutTask("pub-1", "prod-1", null, 0, "firm-1", refs()));
    }

    @Test
    void rejectsNegativeChunkIndex() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new FanOutTask("pub-1", "prod-1", FanOutRunKind.PUBLISH, -1, "firm-1", refs()))
                .withMessageContaining("chunkIndex");
    }

    @Test
    void rejectsBlankFirmId() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new FanOutTask("pub-1", "prod-1", FanOutRunKind.PUBLISH, 0, "", refs()));
    }

    @Test
    void rejectsEmptyEngagementList() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new FanOutTask("pub-1", "prod-1", FanOutRunKind.PUBLISH, 0, "firm-1", List.of()))
                .withMessageContaining("engagements");
    }

    @Test
    void rejectsNullEngagementList() {
        assertThatNullPointerException().isThrownBy(
                () -> new FanOutTask("pub-1", "prod-1", FanOutRunKind.PUBLISH, 0, "firm-1", null));
    }

    @Test
    void engagementsDefensivelyCopiedUnmodifiable() {
        var mutable = new ArrayList<>(refs());
        var task = new FanOutTask("pub-1", "prod-1", FanOutRunKind.PUBLISH, 0, "firm-1", mutable);
        mutable.clear();
        assertThat(task.engagements()).hasSize(2);
        assertThatThrownByMutation(task);
    }

    private static void assertThatThrownByMutation(FanOutTask task) {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> task.engagements().add(ref("eng-9", "firm-1")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsNullEngagementElement() {
        var withNull = new ArrayList<EngagementRef>();
        withNull.add(ref("eng-1", "firm-1"));
        withNull.add(null);
        assertThatNullPointerException().isThrownBy(
                () -> new FanOutTask("pub-1", "prod-1", FanOutRunKind.PUBLISH, 0, "firm-1", withNull));
    }
}
