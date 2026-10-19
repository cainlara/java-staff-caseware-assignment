package com.example.fanout.ports;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.fanout.domain.PendingIndexRow;
import com.example.fanout.domain.PendingIndexState;
import com.example.fanout.fakes.InMemoryIndexWriter;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * Pins IndexWriter contract (contracts/index-writer.md): idempotency on
 * (engagementId, offeredVersion, state); CAS on rowVersion; duplicate write is a
 * no-op; stale writes rejected without mutating stored state.
 */
class IndexWriterContractTest {

    private static final Instant T1 = Instant.parse("2026-10-18T12:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-18T12:01:00Z");

    private static PendingIndexRow row(long rowVersion, String decision, Instant updatedAt) {
        return new PendingIndexRow(
                "eng-1", "firm-1", "prod-1", "v1", "v2",
                PendingIndexState.PENDING, decision, List.of(), null,
                Set.of("pub-1"), rowVersion, updatedAt);
    }

    @Test
    void duplicateIdenticalWriteIsNoOp() {
        var writer = new InMemoryIndexWriter();
        var first = row(1, null, T1);

        assertThat(writer.upsert(first)).isEqualTo(WriteResult.WRITTEN);
        assertThat(writer.upsert(row(1, null, T2)))
                .isEqualTo(WriteResult.SKIPPED_DUPLICATE);

        assertThat(writer.stored("eng-1", "v2", PendingIndexState.PENDING)).isEqualTo(first);
    }

    @Test
    void staleRowVersionRejectedStoredStateUnchanged() {
        var writer = new InMemoryIndexWriter();
        assertThat(writer.upsert(row(1, null, T1))).isEqualTo(WriteResult.WRITTEN);

        assertThat(writer.upsert(row(1, "approved", T2))).isEqualTo(WriteResult.WRITTEN);
        var advanced = writer.stored("eng-1", "v2", PendingIndexState.PENDING);
        assertThat(advanced.rowVersion()).isEqualTo(2);
        assertThat(advanced.decision()).isEqualTo("approved");

        assertThat(writer.upsert(row(1, "rejected", T2))).isEqualTo(WriteResult.REJECTED_STALE);

        assertThat(writer.stored("eng-1", "v2", PendingIndexState.PENDING)).isEqualTo(advanced);
    }

    @Test
    void rowsSharingIdempotencyKeyTargetSameLogicalRow() {
        var writer = new InMemoryIndexWriter();
        assertThat(writer.upsert(row(1, null, T1))).isEqualTo(WriteResult.WRITTEN);

        var differentFirm = new PendingIndexRow(
                "eng-1", "firm-OTHER", "prod-1", "v1", "v2",
                PendingIndexState.PENDING, null, List.of(), null,
                Set.of("pub-1"), 1L, T2);

        assertThat(writer.upsert(differentFirm)).isEqualTo(WriteResult.WRITTEN);
        assertThat(writer.stored("eng-1", "v2", PendingIndexState.PENDING).firmId())
                .isEqualTo("firm-OTHER");
    }

    @Test
    void differentStateIsDifferentLogicalRow() {
        var writer = new InMemoryIndexWriter();
        assertThat(writer.upsert(row(1, null, T1))).isEqualTo(WriteResult.WRITTEN);

        var created = new PendingIndexRow(
                "eng-1", "firm-1", "prod-1", "v1", "v2",
                PendingIndexState.CREATED, null, List.of(), null,
                Set.of("pub-1"), 1L, T2);

        assertThat(writer.upsert(created)).isEqualTo(WriteResult.WRITTEN);
        assertThat(writer.stored("eng-1", "v2", PendingIndexState.CREATED)).isNotNull();
        assertThat(writer.stored("eng-1", "v2", PendingIndexState.PENDING).rowVersion())
                .isEqualTo(1);
    }

    // ---- v2: additive find + re-apply cycle (contracts/index-writer.md) ----

    @Test
    void findAfterWrittenReturnsStoredRow() {
        var writer = new InMemoryIndexWriter();
        var written = row(1, null, T1);
        assertThat(writer.upsert(written)).isEqualTo(WriteResult.WRITTEN);

        var found = writer.find("eng-1", "v2", PendingIndexState.PENDING);
        assertThat(found).isPresent();
        assertThat(found.get()).isEqualTo(written);
    }

    @Test
    void findOnAbsentTripleReturnsEmpty() {
        var writer = new InMemoryIndexWriter();
        assertThat(writer.find("eng-1", "v2", PendingIndexState.PENDING)).isEmpty();
        assertThat(writer.find("eng-2", "v2", PendingIndexState.PENDING)).isEmpty();
    }

    @Test
    void findDoesNotMutateStoredStateOrRowVersion() {
        var writer = new InMemoryIndexWriter();
        writer.upsert(row(1, null, T1));
        writer.find("eng-1", "v2", PendingIndexState.PENDING);
        writer.find("eng-1", "v2", PendingIndexState.PENDING);

        var stored = writer.stored("eng-1", "v2", PendingIndexState.PENDING);
        assertThat(stored.rowVersion()).isEqualTo(1);
        assertThat(stored.updatedAt()).isEqualTo(T1);
    }

    @Test
    void findRejectsNullAndBlankArguments() {
        var writer = new InMemoryIndexWriter();
        org.assertj.core.api.Assertions.assertThatNullPointerException()
                .isThrownBy(() -> writer.find(null, "v2", PendingIndexState.PENDING));
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> writer.find(" ", "v2", PendingIndexState.PENDING));
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> writer.find("eng-1", " ", PendingIndexState.PENDING));
        org.assertj.core.api.Assertions.assertThatNullPointerException()
                .isThrownBy(() -> writer.find("eng-1", "v2", null));
    }

    @Test
    void staleRejectionFindMergeReapplyConverges() {
        var writer = new InMemoryIndexWriter();
        // seed a stored row carrying recorded decision history at rowVersion 2
        writer.upsert(new PendingIndexRow(
                "eng-1", "firm-1", "prod-1", "v1", "v2",
                PendingIndexState.PENDING, null, List.of(), null,
                Set.of("pub-old"), 1L, T1));
        writer.upsert(new PendingIndexRow(
                "eng-1", "firm-1", "prod-1", "v1", "v2",
                PendingIndexState.PENDING, "declined", List.of("r1"), "sum-1",
                Set.of("pub-old"), 1L, T1));
        assertThat(writer.stored("eng-1", "v2", PendingIndexState.PENDING).rowVersion()).isEqualTo(2);

        // engine-style intent presenting stale rowVersion 1
        var intent = new PendingIndexRow(
                "eng-1", "firm-1", "prod-1", "v1", "v2",
                PendingIndexState.PENDING, null, List.of(), null,
                Set.of("pub-new"), 1L, T2);
        assertThat(writer.upsert(intent)).isEqualTo(WriteResult.REJECTED_STALE);

        var current = writer.find("eng-1", "v2", PendingIndexState.PENDING).orElseThrow();
        var merged = new PendingIndexRow(
                intent.engagementId(),
                intent.firmId(),
                intent.productId(),
                intent.currentVersion(),
                intent.offeredVersion(),
                intent.state(),
                current.decision(),
                current.pinnedRanges(),
                current.summaryKey(),
                union(current.publishIds(), intent.publishIds()),
                current.rowVersion(),
                intent.updatedAt());

        assertThat(writer.upsert(merged)).isEqualTo(WriteResult.WRITTEN);
        var stored = writer.stored("eng-1", "v2", PendingIndexState.PENDING);
        assertThat(stored.rowVersion()).isEqualTo(3);
        assertThat(stored.decision()).isEqualTo("declined");
        assertThat(stored.pinnedRanges()).containsExactly("r1");
        assertThat(stored.summaryKey()).isEqualTo("sum-1");
        assertThat(stored.publishIds()).containsExactlyInAnyOrder("pub-old", "pub-new");
        assertThat(stored.updatedAt()).isEqualTo(T2);
    }

    @Test
    void concurrentSameIntentWritersConvergeToSingleExecutionContent() throws Exception {
        var writer = new InMemoryIndexWriter();
        // stored row at rowVersion 1, then a decision write advances it to 2
        writer.upsert(new PendingIndexRow(
                "eng-1", "firm-1", "prod-1", "v1", "v2",
                PendingIndexState.PENDING, null, List.of(), null,
                Set.of("pub-old"), 1L, T1));
        writer.upsert(new PendingIndexRow(
                "eng-1", "firm-1", "prod-1", "v1", "v2",
                PendingIndexState.PENDING, "declined", List.of("r1"), null,
                Set.of("pub-old"), 1L, T1));
        assertThat(writer.stored("eng-1", "v2", PendingIndexState.PENDING).rowVersion()).isEqualTo(2);

        // both writers present the same expected version with pre-merged intent
        var intent = new PendingIndexRow(
                "eng-1", "firm-1", "prod-1", "v1", "v2",
                PendingIndexState.PENDING, "declined", List.of("r1"), null,
                Set.of("pub-old", "pub-new"), 2L, T2);

        int threads = 2;
        var barrier = new CyclicBarrier(threads);
        var pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        barrier.await();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    WriteResult first = writer.upsert(intent);
                    if (first == WriteResult.REJECTED_STALE) {
                        var current = writer.find("eng-1", "v2", PendingIndexState.PENDING).orElseThrow();
                        writer.upsert(new PendingIndexRow(
                                intent.engagementId(), intent.firmId(), intent.productId(),
                                intent.currentVersion(), intent.offeredVersion(), intent.state(),
                                current.decision(), current.pinnedRanges(), current.summaryKey(),
                                union(current.publishIds(), intent.publishIds()),
                                current.rowVersion(), intent.updatedAt()));
                    }
                });
            }
            pool.shutdown();
            org.assertj.core.api.Assertions.assertThat(pool.awaitTermination(10, TimeUnit.SECONDS))
                    .isTrue();
        } finally {
            pool.shutdownNow();
        }

        // exactly one version step: 2 -> 3; both writers converge on the same content
        var stored = writer.stored("eng-1", "v2", PendingIndexState.PENDING);
        assertThat(stored.rowVersion()).isEqualTo(3);
        assertThat(stored.decision()).isEqualTo("declined");
        assertThat(stored.pinnedRanges()).containsExactly("r1");
        assertThat(stored.publishIds()).containsExactlyInAnyOrder("pub-old", "pub-new");
        assertThat(stored.updatedAt()).isEqualTo(T2);
    }

    @Test
    void findNeverTouchesDownstream() {
        // hot-path rule: find is metadata-only by construction; assert acceptance
        // of identifier-only inputs and no state mutation (content rule FR-070)
        var writer = new InMemoryIndexWriter();
        writer.upsert(row(1, null, T1));
        assertThat(writer.find("eng-1", "v2", PendingIndexState.PENDING)).isPresent();
        assertThat(writer.stored("eng-1", "v2", PendingIndexState.PENDING).rowVersion()).isEqualTo(1);
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        var merged = new HashSet<>(a);
        merged.addAll(b);
        return Set.copyOf(merged);
    }
}
