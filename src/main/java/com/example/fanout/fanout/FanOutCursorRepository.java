package com.example.fanout.fanout;

import com.example.fanout.domain.FanOutCursor;
import com.example.fanout.domain.FanOutRunKind;
import com.example.fanout.domain.FanOutRunStatus;
import com.example.fanout.ingest.IngestProgressRepository;
import com.example.fanout.ingest.IngestedPublish;
import com.example.fanout.ports.CheckpointKey;
import com.example.fanout.ports.CheckpointKind;
import com.example.fanout.ports.CheckpointStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Typed adapter over {@link CheckpointStore} — the sole encoder/decoder of the
 * phase-3 fan-out checkpoint schemas (data-model.md, contracts/checkpoint-store.md).
 * Natural keys are assumed delimiter-free (inherited phase-2 assumption);
 * {@code US} = U+001F, {@code LF} = newline.
 *
 * <p><b>FANOUT_CURSOR</b>: key id {@code FanOutRunKind token + US + eventId +
 * US + productId}; value {@code status US totalChunks US lastCompletedChunkIndex}.
 * Claim uses the store's atomic {@code writeIfAbsent} (RUNNING, totalChunks
 * known, lastCompleted = -1); advancement is last-write-wins rewrite;
 * completion rewrites to COMPLETE. Corrupt/unknown encoding throws
 * {@link IllegalStateException} — the engine dead-letters/alerts, never
 * silently restarts from zero.
 *
 * <p><b>PRODUCT_WITHDRAWN_VERSION</b>: key id {@code productId}; value
 * {@code version1 LF version2 LF ...}. Append is a synchronized
 * read-modify-write, idempotent across duplicate triggers (append-if-absent).
 */
public final class FanOutCursorRepository {

    private static final char US = (char) 0x1F;
    private static final String LF = "\n";

    private final CheckpointStore store;
    private final IngestProgressRepository phase2;

    public FanOutCursorRepository(CheckpointStore store) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.phase2 = new IngestProgressRepository(store);
    }

    // ---- run cursor ----

    /**
     * Atomic run claim: writes RUNNING with the given chunk count if absent.
     *
     * @return true if this caller claimed the run; false if a cursor already
     *         existed (left unchanged — first claim wins).
     */
    public boolean claimRun(FanOutRunKind kind, String eventId, String productId, int totalChunks) {
        String value = FanOutRunStatus.RUNNING.name() + separator() + totalChunks + separator() + (-1);
        return store.writeIfAbsent(cursorKey(kind, eventId, productId), value);
    }

    /** Decoded cursor, or empty when never claimed. */
    public Optional<FanOutCursor> readRun(FanOutRunKind kind, String eventId, String productId) {
        return store.read(cursorKey(kind, eventId, productId)).map(raw -> decode(kind, eventId, productId, raw));
    }

    /** Last-write-wins advance of a RUNNING cursor after one completed chunk. */
    public void advance(FanOutRunKind kind, String eventId, String productId,
                        int totalChunks, int lastCompletedChunkIndex) {
        String value = FanOutRunStatus.RUNNING.name() + separator() + totalChunks
                + separator() + lastCompletedChunkIndex;
        store.write(cursorKey(kind, eventId, productId), value);
    }

    /** Rewrite to COMPLETE with the final index (totalChunks == 0 keeps -1). */
    public void complete(FanOutRunKind kind, String eventId, String productId, int totalChunks) {
        int lastCompleted = totalChunks == 0 ? -1 : totalChunks - 1;
        String value = FanOutRunStatus.COMPLETE.name() + separator() + totalChunks + separator() + lastCompleted;
        store.write(cursorKey(kind, eventId, productId), value);
    }

    // ---- withdrawn-version marker ----

    /** Append-if-absent of a withdrawn version for the product (idempotent). */
    public synchronized void markWithdrawn(String productId, String version) {
        requireNonBlank(productId, "productId");
        requireNonBlank(version, "version");
        CheckpointKey key = new CheckpointKey(CheckpointKind.PRODUCT_WITHDRAWN_VERSION, productId);
        List<String> versions = readVersionList(key);
        if (!versions.contains(version)) {
            versions.add(version);
            store.write(key, String.join(LF, versions));
        }
    }

    /** Withdrawn versions recorded for the product, in record order (possibly empty). */
    public synchronized List<String> withdrawnVersions(String productId) {
        requireNonBlank(productId, "productId");
        return List.copyOf(readVersionList(
                new CheckpointKey(CheckpointKind.PRODUCT_WITHDRAWN_VERSION, productId)));
    }

    // ---- phase-2 publish reads (reused for supersede planning) ----

    /** Decoded ingested publishes for the product, in arrival order. */
    public List<IngestedPublish> ingestedPublishes(String productId) {
        requireNonBlank(productId, "productId");
        List<IngestedPublish> publishes = new ArrayList<>();
        for (String publishId : phase2.productPublishes(productId)) {
            phase2.publish(publishId).ifPresent(publishes::add);
        }
        return List.copyOf(publishes);
    }

    /** Decoded ingested publish record for one publish id, if claimed. */
    public Optional<IngestedPublish> ingestedPublish(String publishId) {
        requireNonBlank(publishId, "publishId");
        return phase2.publish(publishId);
    }

    // ---- encoding ----

    private static CheckpointKey cursorKey(FanOutRunKind kind, String eventId, String productId) {
        Objects.requireNonNull(kind, "kind must not be null");
        requireNonBlank(eventId, "eventId");
        requireNonBlank(productId, "productId");
        return new CheckpointKey(CheckpointKind.FANOUT_CURSOR,
                kind.name() + separator() + eventId + separator() + productId);
    }

    private static FanOutCursor decode(FanOutRunKind kind, String eventId, String productId, String raw) {
        String[] parts = raw.split(String.valueOf(US), -1);
        if (parts.length != 3) {
            throw new IllegalStateException("malformed FANOUT_CURSOR checkpoint value: corrupt cursor");
        }
        FanOutRunStatus status;
        try {
            status = FanOutRunStatus.valueOf(parts[0]);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("corrupt cursor: unknown status " + parts[0]);
        }
        int totalChunks;
        int lastCompleted;
        try {
            totalChunks = Integer.parseInt(parts[1]);
            lastCompleted = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("corrupt cursor: non-numeric chunk counters");
        }
        return new FanOutCursor(kind, eventId, productId, status, totalChunks, lastCompleted);
    }

    private static char separator() {
        return US;
    }

    private List<String> readVersionList(CheckpointKey key) {
        Optional<String> raw = store.read(key);
        if (raw.isEmpty() || raw.get().isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(List.of(raw.get().split(LF)));
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must be non-blank");
        }
    }
}
