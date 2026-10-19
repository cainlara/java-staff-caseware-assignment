package com.example.fanout.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Identity of an exhausted work unit — the replay handle (spec FR-041, R6).
 * Never carries engagement content; {@code lastFailureMessage} comes from
 * port-level exceptions (identifier-only by phase-1 contract).
 *
 * <p>{@code chunkIndex == -1} marks a non-chunk unit (stream, cursor, plan).
 * Validation: {@code attemptCount >= 1}; blank identities rejected; failure
 * type must be non-blank, message may be empty but never engagement content.
 */
public record DeadLetterRecord(
        String eventId,
        String productId,
        int chunkIndex,
        DeadLetterUnitKind unitKind,
        int attemptCount,
        String lastFailureType,
        String lastFailureMessage,
        Instant recordedAt) {

    public DeadLetterRecord {
        Checks.requireNonBlank(eventId, "eventId");
        Checks.requireNonBlank(productId, "productId");
        Checks.requireAtLeast(chunkIndex, -1, "chunkIndex");
        Objects.requireNonNull(unitKind, "unitKind must not be null");
        Checks.requireAtLeast(attemptCount, 1, "attemptCount");
        Checks.requireNonBlank(lastFailureType, "lastFailureType");
        Objects.requireNonNull(lastFailureMessage, "lastFailureMessage must not be null");
        Objects.requireNonNull(recordedAt, "recordedAt must not be null");
    }
}
