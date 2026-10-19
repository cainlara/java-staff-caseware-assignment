package com.example.fanout.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class DeadLetterRecordTest {

    private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");

    private static DeadLetterRecord record(String eventId, int chunkIndex, DeadLetterUnitKind kind,
                                           int attemptCount, String failureType, String failureMessage) {
        return new DeadLetterRecord(eventId, "prod-1", chunkIndex, kind, attemptCount,
                failureType, failureMessage, NOW);
    }

    @Test
    void acceptsValidRecord() {
        var r = record("pub-1", 3, DeadLetterUnitKind.CHUNK_WRITE, 5,
                IllegalStateException.class.getName(), "write failed");
        assertThat(r.eventId()).isEqualTo("pub-1");
        assertThat(r.chunkIndex()).isEqualTo(3);
        assertThat(r.lastFailureMessage()).isEqualTo("write failed");
    }

    @Test
    void acceptsNonChunkUnitIndexMinusOne() {
        assertThat(record("pub-1", -1, DeadLetterUnitKind.ELIGIBILITY_STREAM, 1, "T", "m").chunkIndex())
                .isEqualTo(-1);
    }

    @Test
    void acceptsEmptyFailureMessage() {
        assertThat(record("pub-1", 0, DeadLetterUnitKind.CURSOR_WRITE, 1, "T", "").lastFailureMessage())
                .isEmpty();
    }

    @Test
    void rejectsBlankEventId() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> record(" ", 0, DeadLetterUnitKind.CHUNK_WRITE, 1, "T", "m"))
                .withMessageContaining("eventId");
    }

    @Test
    void rejectsChunkIndexBelowMinusOne() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> record("pub-1", -2, DeadLetterUnitKind.CHUNK_WRITE, 1, "T", "m"))
                .withMessageContaining("chunkIndex");
    }

    @Test
    void rejectsAttemptCountBelowOne() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> record("pub-1", 0, DeadLetterUnitKind.CHUNK_WRITE, 0, "T", "m"))
                .withMessageContaining("attemptCount");
    }

    @Test
    void rejectsNullUnitKind() {
        assertThatNullPointerException().isThrownBy(
                () -> record("pub-1", 0, null, 1, "T", "m"));
    }

    @Test
    void rejectsBlankFailureType() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> record("pub-1", 0, DeadLetterUnitKind.CHUNK_WRITE, 1, " ", "m"))
                .withMessageContaining("lastFailureType");
    }

    @Test
    void rejectsNullFailureMessage() {
        assertThatNullPointerException().isThrownBy(
                () -> record("pub-1", 0, DeadLetterUnitKind.CHUNK_WRITE, 1, "T", null));
    }

    @Test
    void rejectsNullRecordedAt() {
        assertThatNullPointerException().isThrownBy(
                () -> new DeadLetterRecord("pub-1", "prod-1", 0, DeadLetterUnitKind.CHUNK_WRITE,
                        1, "T", "m", null));
    }
}
