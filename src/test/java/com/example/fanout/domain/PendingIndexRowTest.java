package com.example.fanout.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PendingIndexRowTest {

    private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");

    private static PendingIndexRow valid() {
        return new PendingIndexRow(
                "eng-1", "firm-1", "prod-1", "v1", "v2",
                PendingIndexState.PENDING, null, List.of(), null,
                Set.of("pub-1"), 1L, NOW);
    }

    @Test
    void validInstanceConstructs() {
        var row = valid();
        assertThat(row.state()).isEqualTo(PendingIndexState.PENDING);
        assertThat(row.publishIds()).containsExactly("pub-1");
    }

    @Test
    void nullDecisionAndSummaryKeyAllowed() {
        var row = new PendingIndexRow(
                "eng-1", "firm-1", "prod-1", "v1", "v2",
                PendingIndexState.PENDING, null, List.of(), null,
                Set.of("pub-1"), 1L, NOW);
        assertThat(row.decision()).isNull();
        assertThat(row.summaryKey()).isNull();
    }

    @Test
    void nullEngagementIdRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PendingIndexRow(
                        null, "firm-1", "prod-1", "v1", "v2",
                        PendingIndexState.PENDING, null, List.of(), null,
                        Set.of("pub-1"), 1L, NOW))
                .withMessageContaining("engagementId");
    }

    @Test
    void blankFirmIdRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PendingIndexRow(
                        "eng-1", "  ", "prod-1", "v1", "v2",
                        PendingIndexState.PENDING, null, List.of(), null,
                        Set.of("pub-1"), 1L, NOW))
                .withMessageContaining("firmId");
    }

    @Test
    void blankProductIdRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PendingIndexRow(
                        "eng-1", "firm-1", "", "v1", "v2",
                        PendingIndexState.PENDING, null, List.of(), null,
                        Set.of("pub-1"), 1L, NOW))
                .withMessageContaining("productId");
    }

    @Test
    void nullCurrentVersionRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PendingIndexRow(
                        "eng-1", "firm-1", "prod-1", null, "v2",
                        PendingIndexState.PENDING, null, List.of(), null,
                        Set.of("pub-1"), 1L, NOW))
                .withMessageContaining("currentVersion");
    }

    @Test
    void blankOfferedVersionRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PendingIndexRow(
                        "eng-1", "firm-1", "prod-1", "v1", " ",
                        PendingIndexState.PENDING, null, List.of(), null,
                        Set.of("pub-1"), 1L, NOW))
                .withMessageContaining("offeredVersion");
    }

    @Test
    void nullStateRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new PendingIndexRow(
                        "eng-1", "firm-1", "prod-1", "v1", "v2",
                        null, null, List.of(), null,
                        Set.of("pub-1"), 1L, NOW))
                .withMessageContaining("state");
    }

    @Test
    void nullPinnedRangesRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new PendingIndexRow(
                        "eng-1", "firm-1", "prod-1", "v1", "v2",
                        PendingIndexState.PENDING, null, null, null,
                        Set.of("pub-1"), 1L, NOW))
                .withMessageContaining("pinnedRanges");
    }

    @Test
    void blankPinnedRangeElementRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PendingIndexRow(
                        "eng-1", "firm-1", "prod-1", "v1", "v2",
                        PendingIndexState.PENDING, null, List.of("  "), null,
                        Set.of("pub-1"), 1L, NOW))
                .withMessageContaining("pinnedRanges element");
    }

    @Test
    void nullPublishIdsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new PendingIndexRow(
                        "eng-1", "firm-1", "prod-1", "v1", "v2",
                        PendingIndexState.PENDING, null, List.of(), null,
                        null, 1L, NOW))
                .withMessageContaining("publishIds");
    }

    @Test
    void emptyPublishIdsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PendingIndexRow(
                        "eng-1", "firm-1", "prod-1", "v1", "v2",
                        PendingIndexState.PENDING, null, List.of(), null,
                        Set.of(), 1L, NOW))
                .withMessageContaining("publishIds");
    }

    @Test
    void blankPublishIdElementRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PendingIndexRow(
                        "eng-1", "firm-1", "prod-1", "v1", "v2",
                        PendingIndexState.PENDING, null, List.of(), null,
                        Set.of(" "), 1L, NOW))
                .withMessageContaining("publishIds element");
    }

    @Test
    void rowVersionBelowOneRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PendingIndexRow(
                        "eng-1", "firm-1", "prod-1", "v1", "v2",
                        PendingIndexState.PENDING, null, List.of(), null,
                        Set.of("pub-1"), 0L, NOW))
                .withMessageContaining("rowVersion");
    }

    @Test
    void nullUpdatedAtRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new PendingIndexRow(
                        "eng-1", "firm-1", "prod-1", "v1", "v2",
                        PendingIndexState.PENDING, null, List.of(), null,
                        Set.of("pub-1"), 1L, null))
                .withMessageContaining("updatedAt");
    }

    @Test
    void publishIdsDefensivelyCopiedFromMutableInput() {
        Set<String> mutable = new HashSet<>(List.of("pub-1"));
        var row = new PendingIndexRow(
                "eng-1", "firm-1", "prod-1", "v1", "v2",
                PendingIndexState.PENDING, null, List.of(), null,
                mutable, 1L, NOW);
        mutable.add("pub-2");
        assertThat(row.publishIds()).containsExactly("pub-1");
    }

    @Test
    void pinnedRangesDefensivelyCopiedFromMutableInput() {
        List<String> mutable = new ArrayList<>(List.of("range-1"));
        var row = new PendingIndexRow(
                "eng-1", "firm-1", "prod-1", "v1", "v2",
                PendingIndexState.PENDING, null, mutable, null,
                Set.of("pub-1"), 1L, NOW);
        mutable.add("range-2");
        assertThat(row.pinnedRanges()).containsExactly("range-1");
    }

    @Test
    void returnedCollectionsUnmodifiable() {
        var row = valid();
        assertThatThrownByPublishIdsMutate(row);
        assertThatThrownByPinnedRangesMutate(row);
    }

    private static void assertThatThrownByPublishIdsMutate(PendingIndexRow row) {
        org.assertj.core.api.Assertions
                .assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> row.publishIds().add("pub-2"));
    }

    private static void assertThatThrownByPinnedRangesMutate(PendingIndexRow row) {
        org.assertj.core.api.Assertions
                .assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> row.pinnedRanges().add("range"));
    }
}
