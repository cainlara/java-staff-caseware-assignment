package com.example.fanout.ports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.fanout.domain.EnumeratedEngagement;
import com.example.fanout.domain.EnumerationBatch;
import com.example.fanout.fakes.SeededEngagementEnumerator;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Port contract pin for {@link EngagementEnumerator}
 * (contracts/engagement-enumerator.md): order, stability, resumability,
 * cursor, size, content prohibition, and error handling — invariants 1-7 —
 * exercised against the seeded reference fake.
 */
class EngagementEnumeratorContractTest {

    private static final int FIRM_COUNT = 4;
    private static final int PER_FIRM = 25;
    private static final int LIMIT = 7;

    private SeededEngagementEnumerator enumerator() {
        return new SeededEngagementEnumerator(42L, FIRM_COUNT, PER_FIRM);
    }

    /** First firm id of the reference fake — the id scheme is fake-owned. */
    private static String firmId(SeededEngagementEnumerator enumerator) {
        return enumerator.firmIds().iterator().next();
    }

    @Test
    void invariant1_itemsAreStrictlyAscendingWithoutDuplicates() {
        SeededEngagementEnumerator enumerator = enumerator();
        String firm = firmId(enumerator);

        EnumerationBatch batch = enumerator.nextBatch(firm, Optional.empty(), LIMIT);

        assertThat(batch.items()).isNotEmpty();
        List<String> ids = batch.items().stream().map(EnumeratedEngagement::engagementId).toList();
        assertThat(ids).isSorted();
        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    void invariant2_sameArgumentsYieldTheSameItems() {
        SeededEngagementEnumerator first = enumerator();
        SeededEngagementEnumerator second = enumerator();
        String firm = firmId(first);
        Optional<String> cursor = Optional.of(first.nextBatch(firm, Optional.empty(), LIMIT)
                .nextCursor().orElseThrow());

        EnumerationBatch a = first.nextBatch(firm, cursor, LIMIT);
        EnumerationBatch b = second.nextBatch(firm, cursor, LIMIT);

        assertThat(a.items()).isEqualTo(b.items());
        assertThat(a.nextCursor()).isEqualTo(b.nextCursor());
        assertThat(a.done()).isEqualTo(b.done());
    }

    @Test
    void invariant3_concatenationOfBatchesIsTheWholeSetExactlyOnce() {
        SeededEngagementEnumerator enumerator = enumerator();
        String firm = firmId(enumerator);
        List<String> seen = new ArrayList<>();
        Optional<String> cursor = Optional.empty();
        int guard = 0;
        while (guard++ < 100) {
            EnumerationBatch batch = enumerator.nextBatch(firm, cursor, LIMIT);
            batch.items().forEach(item -> seen.add(item.engagementId()));
            if (batch.done()) {
                break;
            }
            cursor = batch.nextCursor();
        }

        List<String> expected = enumerator.dataset(firm).stream()
                .map(EnumeratedEngagement::engagementId)
                .toList();
        assertThat(seen).isEqualTo(expected);
        assertThat(seen).doesNotHaveDuplicates();
    }

    @Test
    void invariant4_cursorEqualsLastItemAndDoneImpliesEmpty() {
        SeededEngagementEnumerator enumerator = enumerator();
        String firm = firmId(enumerator);
        Optional<String> cursor = Optional.empty();
        int guard = 0;
        while (guard++ < 100) {
            EnumerationBatch batch = enumerator.nextBatch(firm, cursor, LIMIT);
            if (batch.done()) {
                assertThat(batch.items()).isEmpty();
                assertThat(batch.nextCursor()).isEmpty();
                return;
            }
            assertThat(batch.nextCursor())
                    .contains(batch.items().get(batch.items().size() - 1).engagementId());
            cursor = batch.nextCursor();
        }
        throw new AssertionError("enumeration never reached done");
    }

    @Test
    void invariant5_batchSizeNeverExceedsLimit() {
        SeededEngagementEnumerator enumerator = enumerator();
        String firm = firmId(enumerator);
        Optional<String> cursor = Optional.empty();
        int guard = 0;
        while (guard++ < 100) {
            EnumerationBatch batch = enumerator.nextBatch(firm, cursor, LIMIT);
            assertThat(batch.items()).hasSizeLessThanOrEqualTo(LIMIT);
            if (batch.done()) {
                return;
            }
            cursor = batch.nextCursor();
        }
        throw new AssertionError("enumeration never reached done");
    }

    @Test
    void invariant6_payloadsCarryIdentifiersOnly() {
        SeededEngagementEnumerator enumerator = enumerator();

        String firm = firmId(enumerator);
        EnumerationBatch batch = enumerator.nextBatch(firm, Optional.empty(), PER_FIRM);

        assertThat(batch.items()).isNotEmpty();
        batch.items().forEach(item -> {
            assertThat(item.engagementId()).isNotBlank();
            assertThat(item.firmId()).isNotBlank();
            assertThat(item.productId()).isNotBlank();
            assertThat(EnumeratedEngagement.class.getRecordComponents())
                    .extracting(java.lang.reflect.RecordComponent::getName)
                    .containsExactlyInAnyOrder("engagementId", "firmId", "productId");
        });
    }

    @Test
    void invariant7_badArgumentsAreIllegalArgumentAndFailuresSurfaceUnchecked() {
        SeededEngagementEnumerator enumerator = enumerator();

        String firm = firmId(enumerator);
        assertThatThrownBy(() -> enumerator.nextBatch(null, Optional.empty(), LIMIT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> enumerator.nextBatch(" ", Optional.empty(), LIMIT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> enumerator.nextBatch(firm, Optional.empty(), 0))
                .isInstanceOf(IllegalArgumentException.class);

        enumerator.failAlways();
        assertThatThrownBy(() -> enumerator.nextBatch(firm, Optional.empty(), LIMIT))
                .isInstanceOf(RuntimeException.class)
                .isNotInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fakeSupportsFailureProgrammingAndCallLogging() {
        SeededEngagementEnumerator enumerator = enumerator();
        String firm = firmId(enumerator);
        enumerator.failAfterBatches(2);

        enumerator.nextBatch(firm, Optional.empty(), LIMIT);
        enumerator.nextBatch(firm, Optional.empty(), LIMIT);
        assertThatThrownBy(() -> enumerator.nextBatch(firm, Optional.empty(), LIMIT))
                .isInstanceOf(RuntimeException.class);

        enumerator.clearFailures();
        EnumerationBatch recovered = enumerator.nextBatch(firm, Optional.empty(), LIMIT);
        assertThat(recovered.items()).isNotEmpty();

        assertThat(enumerator.callLog()).hasSize(4);
        assertThat(enumerator.callLog().getFirst().databaseId()).isEqualTo(firm);
        assertThat(enumerator.callLog().getFirst().afterCursor()).isEmpty();
        assertThat(enumerator.callLog().getFirst().limit()).isEqualTo(LIMIT);
    }
}
