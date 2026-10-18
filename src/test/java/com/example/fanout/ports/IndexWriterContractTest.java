package com.example.fanout.ports;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.fanout.domain.PendingIndexRow;
import com.example.fanout.domain.PendingIndexState;
import com.example.fanout.fakes.InMemoryIndexWriter;
import java.time.Instant;
import java.util.List;
import java.util.Set;
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
}
