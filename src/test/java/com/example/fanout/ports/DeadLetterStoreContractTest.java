package com.example.fanout.ports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.example.fanout.domain.DeadLetterRecord;
import com.example.fanout.domain.DeadLetterUnitKind;
import com.example.fanout.fakes.FakeDeadLetterStore;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins DeadLetterStore contract (contracts/dead-letter-store.md): append-only,
 * store-order reads per event, full-identity round-trip (spec SC-007).
 */
class DeadLetterStoreContractTest {

    private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");

    private static DeadLetterRecord record(String eventId, int chunkIndex, int attempt) {
        return new DeadLetterRecord(eventId, "prod-1", chunkIndex, DeadLetterUnitKind.CHUNK_WRITE,
                attempt, IllegalStateException.class.getName(), "failure-" + attempt, NOW);
    }

    @Test
    void storeTwoRecordsForOneEventByEventReturnsBothInOrder() {
        var store = new FakeDeadLetterStore();
        store.store(record("pub-1", 0, 1));
        store.store(record("pub-1", 1, 1));

        List<DeadLetterRecord> records = store.byEvent("pub-1");
        assertThat(records).hasSize(2);
        assertThat(records.get(0).chunkIndex()).isEqualTo(0);
        assertThat(records.get(1).chunkIndex()).isEqualTo(1);
    }

    @Test
    void byEventForUnknownIdReturnsEmptyList() {
        var store = new FakeDeadLetterStore();
        store.store(record("pub-1", 0, 1));
        assertThat(store.byEvent("unknown")).isEmpty();
        assertThat(store.byEvent("pub-2")).isEmpty();
    }

    @Test
    void neverOverwritesOrDeduplicates() {
        var store = new FakeDeadLetterStore();
        store.store(record("pub-1", 0, 1));
        store.store(record("pub-1", 0, 1));
        assertThat(store.count()).isEqualTo(2);
    }

    @Test
    void blankEventIdRejectedOnRead() {
        var store = new FakeDeadLetterStore();
        assertThatIllegalArgumentException().isThrownBy(() -> store.byEvent(" "))
                .withMessageContaining("eventId");
    }

    @Test
    void emptyStoreReportsZeroDepthForAlerts() {
        DeadLetterStore store = new FakeDeadLetterStore();
        assertThat(store.count()).isZero();
    }

    @Test
    void countTracksStoredRecordDepth() {
        DeadLetterStore store = new FakeDeadLetterStore();
        store.store(record("pub-1", 0, 1));
        store.store(record("pub-2", 0, 1));
        store.store(record("pub-2", 1, 1));

        assertThat(store.count()).isEqualTo(3);
    }

    @Test
    void nullRecordRejectedOnStore() {
        var store = new FakeDeadLetterStore();
        org.assertj.core.api.Assertions.assertThatNullPointerException()
                .isThrownBy(() -> store.store(null));
    }

    @Test
    void storedRecordContentRoundTripsUnchanged() {
        var store = new FakeDeadLetterStore();
        DeadLetterRecord original = new DeadLetterRecord("pub-7", "prod-3", 12,
                DeadLetterUnitKind.DOWNSTREAM_CALL, 5, "java.lang.IllegalStateException",
                "downstream timeout", NOW);
        store.store(original);

        DeadLetterRecord read = store.byEvent("pub-7").get(0);
        assertThat(read).isEqualTo(original);
        assertThat(read.eventId()).isEqualTo("pub-7");
        assertThat(read.productId()).isEqualTo("prod-3");
        assertThat(read.chunkIndex()).isEqualTo(12);
        assertThat(read.unitKind()).isEqualTo(DeadLetterUnitKind.DOWNSTREAM_CALL);
        assertThat(read.attemptCount()).isEqualTo(5);
        assertThat(read.lastFailureType()).isEqualTo("java.lang.IllegalStateException");
        assertThat(read.lastFailureMessage()).isEqualTo("downstream timeout");
        assertThat(read.recordedAt()).isEqualTo(NOW);
    }
}
