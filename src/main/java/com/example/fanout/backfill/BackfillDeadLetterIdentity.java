package com.example.fanout.backfill;

import java.util.Objects;
import java.util.Optional;

/**
 * Sole codec for backfill dead-letter identity (spec B8, FR-051,
 * contracts/backfill-dlq-identity.md, research.md D4): the backfill work
 * identity rides in the delivered {@code DeadLetterRecord.eventId}, so the
 * record shape stays untouched.
 *
 * <p>Encoding, with {@code US} = U+001F (delivered delimiter discipline; ids
 * assumed delimiter-free):
 * <ul>
 *   <li>{@code BACKFILL_ENGAGEMENT} — {@code bf US firmId US batchIndex US engagementId},
 *       {@code chunkIndex = batchIndex >= 0};</li>
 *   <li>{@code BACKFILL_BATCH} — {@code bf US firmId US batchIndex},
 *       {@code chunkIndex = batchIndex >= 0};</li>
 *   <li>{@code BACKFILL_CURSOR} — {@code bf US firmId},
 *       {@code chunkIndex = -1}.</li>
 * </ul>
 *
 * <p>{@code batchIndex} is the batch ordinal within the observing run (the
 * persisted cursor stores engagement ids, not batch numbers). Decoding a
 * non-{@code bf} event id or a malformed segment count throws
 * {@link IllegalStateException} — a corrupt DLQ identity is loud, never
 * silently dropped.
 */
public final class BackfillDeadLetterIdentity {

    /** Prefix marker distinguishing every backfill unit identity. */
    public static final String PREFIX = "bf";

    /** Product placeholder for units whose product id is not yet known. */
    public static final String UNKNOWN_PRODUCT = "unknown";

    private static final char US = (char) 0x1F;
    private static final String SEP = String.valueOf(US);

    /** Identity of one backfill work unit decoded from an event id. */
    public record Unit(String firmId, int batchIndex, Optional<String> engagementId) {

        public Unit {
            requireNonBlank(firmId, "firmId");
            if (batchIndex < -1) {
                throw new IllegalArgumentException("batchIndex must be >= -1");
            }
            Objects.requireNonNull(engagementId, "engagementId must not be null");
            if (batchIndex == -1 && engagementId.isPresent()) {
                throw new IllegalArgumentException("a cursor unit carries no engagement id");
            }
        }
    }

    private BackfillDeadLetterIdentity() {
    }

    /** {@code bf US firmId US batchIndex US engagementId} (batchIndex >= 0). */
    public static String engagement(String firmId, int batchIndex, String engagementId) {
        requireNonBlank(firmId, "firmId");
        requireNonBlank(engagementId, "engagementId");
        requireBatchIndex(batchIndex);
        return PREFIX + SEP + firmId + SEP + batchIndex + SEP + engagementId;
    }

    /** {@code bf US firmId US batchIndex} (batchIndex >= 0). */
    public static String batch(String firmId, int batchIndex) {
        requireNonBlank(firmId, "firmId");
        requireBatchIndex(batchIndex);
        return PREFIX + SEP + firmId + SEP + batchIndex;
    }

    /** {@code bf US firmId} — cursor units carry {@code chunkIndex = -1}. */
    public static String cursor(String firmId) {
        requireNonBlank(firmId, "firmId");
        return PREFIX + SEP + firmId;
    }

    /** Decode a backfill event id back to its full work identity. */
    public static Unit decode(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalStateException("corrupt backfill identity: eventId is blank");
        }
        String[] parts = eventId.split(SEP, -1);
        if (!parts[0].equals(PREFIX)) {
            throw new IllegalStateException(
                    "corrupt backfill identity: missing " + PREFIX + " prefix");
        }
        switch (parts.length) {
            case 2 -> {
                requireNonBlankInDecode(parts[1], "firmId");
                return new Unit(parts[1], -1, Optional.empty());
            }
            case 3 -> {
                requireNonBlankInDecode(parts[1], "firmId");
                return new Unit(parts[1], parseBatchIndex(parts[2]), Optional.empty());
            }
            case 4 -> {
                requireNonBlankInDecode(parts[1], "firmId");
                requireNonBlankInDecode(parts[3], "engagementId");
                return new Unit(parts[1], parseBatchIndex(parts[2]), Optional.of(parts[3]));
            }
            default -> throw new IllegalStateException(
                    "corrupt backfill identity: unexpected segment count " + parts.length);
        }
    }

    private static int parseBatchIndex(String raw) {
        try {
            int index = Integer.parseInt(raw);
            if (index < 0) {
                throw new IllegalStateException("corrupt backfill identity: negative batchIndex");
            }
            return index;
        } catch (NumberFormatException e) {
            throw new IllegalStateException("corrupt backfill identity: non-numeric batchIndex", e);
        }
    }

    private static void requireNonBlankInDecode(String value, String field) {
        if (value.isBlank()) {
            throw new IllegalStateException("corrupt backfill identity: blank " + field);
        }
    }

    private static void requireBatchIndex(int batchIndex) {
        if (batchIndex < 0) {
            throw new IllegalArgumentException("batchIndex must be >= 0");
        }
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must be non-blank");
        }
    }
}
