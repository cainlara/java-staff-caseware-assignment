package com.example.fanout.backfill;

import com.example.fanout.ports.CheckpointKey;
import com.example.fanout.ports.CheckpointKind;
import com.example.fanout.ports.CheckpointStore;
import java.util.Objects;
import java.util.Optional;

/**
 * Sole encoder/decoder of the per-firm backfill checkpoint
 * (contracts/backfill-checkpoint.md, research.md D2): one
 * {@code CheckpointKind.BACKFILL_BATCH} key per firm, value
 * {@code status US lastResolvedEngagementId} with {@code US} = U+001F.
 *
 * <p>{@code lastResolvedEngagementId} is the exclusive lower bound for the
 * next enumeration call: the last engagement id of the newest fully resolved
 * batch, empty when no batch has resolved yet. Missing key = start of
 * enumeration. A corrupt value (bad field count, unknown status token) throws
 * {@link IllegalStateException} — loud, never a silent restart.
 *
 * <p>Single-runner assumption (same contract as {@code FANOUT_CURSOR}):
 * concurrent advances to one firm's key are a caller bug. Invariants: the
 * value never regresses to {@code RUNNING} once {@code COMPLETE}, and a
 * {@code COMPLETE} cursor is never rewritten.
 *
 * <p>Failure mapping at the worker boundary: store unavailability surfaces as
 * {@link com.example.fanout.fanout.TransientFanOutException} (bounded
 * {@code BACKFILL_CURSOR} retry), a corrupt value as
 * {@link IllegalStateException} (dead-lettered, never retried as transient),
 * and any other failure — including simulated process death — propagates
 * untouched (crash seam, research.md D12).
 */
public final class BackfillCursorRepository {

    private static final char US = (char) 0x1F;
    private static final String SEP = String.valueOf(US);
    private static final String RUNNING = "RUNNING";
    private static final String COMPLETE = "COMPLETE";

    /** Lifecycle of one firm's backfill cursor. */
    public enum Status {
        RUNNING,
        COMPLETE
    }

    /** Decoded cursor value; {@code lastResolvedEngagementId} may be empty. */
    public record Cursor(Status status, String lastResolvedEngagementId) {

        public Cursor {
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(lastResolvedEngagementId,
                    "lastResolvedEngagementId must not be null");
        }

        /** Exclusive lower bound for the next enumeration call. */
        public String startCursor() {
            return lastResolvedEngagementId;
        }
    }

    private final CheckpointStore store;

    public BackfillCursorRepository(CheckpointStore store) {
        this.store = Objects.requireNonNull(store, "store must not be null");
    }

    /** Decoded cursor for one firm; empty when the firm never resolved a batch. */
    public Optional<Cursor> read(String firmId) {
        requireNonBlank(firmId, "firmId");
        return store.read(key(firmId)).map(value -> decode(firmId, value));
    }

    /**
     * Persist the newest fully resolved batch boundary of a {@code RUNNING}
     * cursor (last-write-wins). A {@code COMPLETE} cursor is never rewritten
     * (invariant 2) — the call is a no-op.
     */
    public void advance(String firmId, String lastResolvedEngagementId) {
        requireNonBlank(firmId, "firmId");
        requireNonBlank(lastResolvedEngagementId, "lastResolvedEngagementId");
        Optional<Cursor> current = read(firmId);
        if (current.isPresent()
                && current.orElseThrow().status() == Status.COMPLETE) {
            return;
        }
        store.write(key(firmId), RUNNING + SEP + lastResolvedEngagementId);
    }

    /**
     * Mark the firm's enumeration finished (never rewritten): called only when
     * the enumerator reported done AND the final batch resolved.
     */
    public void complete(String firmId, String lastResolvedEngagementId) {
        requireNonBlank(firmId, "firmId");
        Objects.requireNonNull(lastResolvedEngagementId,
                "lastResolvedEngagementId must not be null");
        Optional<Cursor> current = read(firmId);
        if (current.isPresent()
                && current.orElseThrow().status() == Status.COMPLETE) {
            return;
        }
        store.write(key(firmId), COMPLETE + SEP + lastResolvedEngagementId);
    }

    /**
     * Exclusive lower bound for the next {@code enumerator.nextBatch} call:
     * the firm's last fully resolved engagement id, empty when none resolved
     * or the key is missing.
     */
    public String startCursor(String firmId) {
        requireNonBlank(firmId, "firmId");
        return read(firmId).map(Cursor::startCursor).orElse("");
    }

    private static Cursor decode(String firmId, String value) {
        String[] parts = value.split(SEP, -1);
        if (parts.length != 2) {
            throw new IllegalStateException(
                    "corrupt backfill cursor for " + firmId + ": field count " + parts.length);
        }
        Status status = switch (parts[0]) {
            case RUNNING -> Status.RUNNING;
            case COMPLETE -> Status.COMPLETE;
            default -> throw new IllegalStateException(
                    "corrupt backfill cursor for " + firmId + ": unknown status " + parts[0]);
        };
        return new Cursor(status, parts[1]);
    }

    private static CheckpointKey key(String firmId) {
        return new CheckpointKey(CheckpointKind.BACKFILL_BATCH, firmId);
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must be non-blank");
        }
    }
}
