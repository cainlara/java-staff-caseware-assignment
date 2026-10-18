package com.example.fanout.ingest;

import com.example.fanout.ports.CheckpointKey;
import com.example.fanout.ports.CheckpointKind;
import com.example.fanout.ports.CheckpointStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Typed adapter over {@link CheckpointStore} — the sole record of ingest
 * progress. All US/LF-delimited string encoding of the phase-2 checkpoint
 * schemas lives here, nowhere else. Natural keys are assumed delimiter-free
 * (UUID-like): {@code US} = U+001F unit separator, {@code LF} = newline.
 *
 * <p>Claim operations ({@link #claimPublish}, {@link #claimWithdraw}) use the
 * store's atomic {@code writeIfAbsent} — first claim wins; the written value
 * IS the processed record. Read-modify-write operations are synchronized per
 * repository instance; concurrent same-product sequencing across instances is
 * observational-only (see research.md disclosures).
 */
public final class IngestProgressRepository {

    private static final char US = (char) 0x1F;
    private static final String LF = "\n";

    private final CheckpointStore store;

    public IngestProgressRepository(CheckpointStore store) {
        this.store = Objects.requireNonNull(store, "store must not be null");
    }

    // ---- claims (idempotency) ----

    /** Atomic claim of a publish id; true = newly claimed, false = duplicate. */
    public boolean claimPublish(String publishId, String productId, String toVersion, Instant publishedAt) {
        String value = productId + US + toVersion + US + publishedAt.toEpochMilli();
        return store.writeIfAbsent(new CheckpointKey(CheckpointKind.PUBLISH, publishId), value);
    }

    /** Atomic claim of a withdraw id (initial status DEFERRED); true = newly claimed. */
    public boolean claimWithdraw(String withdrawId, String productId, String version, Instant withdrawnAt) {
        String value = productId + US + version + US + WithdrawStatus.DEFERRED + US + US + withdrawnAt.toEpochMilli();
        return store.writeIfAbsent(new CheckpointKey(CheckpointKind.WITHDRAW, withdrawId), value);
    }

    // ---- per-product ingest order ----

    /** Next per-product ingest sequence (read-modify-write; observational). */
    public synchronized long nextProductSequence(String productId) {
        Objects.requireNonNull(productId, "productId must not be null");
        Optional<String> current = store.read(new CheckpointKey(CheckpointKind.PRODUCT_INGEST, productId));
        long next = current.map(Long::parseLong).orElse(0L) + 1L;
        store.write(new CheckpointKey(CheckpointKind.PRODUCT_INGEST, productId), Long.toString(next));
        return next;
    }

    /** Append a publish id to the product's ingest-ordered list (idempotent). */
    public synchronized void addProductPublish(String productId, String publishId) {
        CheckpointKey key = new CheckpointKey(CheckpointKind.PRODUCT_PUBLISHES, productId);
        List<String> ids = readIdList(key);
        if (!ids.contains(publishId)) {
            ids.add(publishId);
            store.write(key, String.join(LF, ids));
        }
    }

    /** Publish ids ingested for the product, in arrival order. */
    public List<String> productPublishes(String productId) {
        return List.copyOf(readIdList(new CheckpointKey(CheckpointKind.PRODUCT_PUBLISHES, productId)));
    }

    // ---- withdraw deferral / resolution ----

    /** List a deferred withdraw under its (product, version) if not already listed. */
    public synchronized void deferWithdraw(String productId, String version, String withdrawId) {
        CheckpointKey key = new CheckpointKey(CheckpointKind.PENDING_WITHDRAW, productId + US + version);
        List<String> ids = readIdList(key);
        if (!ids.contains(withdrawId)) {
            ids.add(withdrawId);
            store.write(key, String.join(LF, ids));
        }
    }

    /**
     * Read and clear the pending list for (product, version); returns the ids
     * that were listed (possibly empty). Clearing writes an empty value.
     */
    public synchronized List<String> takePendingWithdraws(String productId, String version) {
        CheckpointKey key = new CheckpointKey(CheckpointKind.PENDING_WITHDRAW, productId + US + version);
        List<String> ids = readIdList(key);
        if (!ids.isEmpty()) {
            store.write(key, "");
        }
        return List.copyOf(ids);
    }

    /** Pending withdraw ids for (product, version) without clearing. */
    public List<String> pendingWithdraws(String productId, String version) {
        return List.copyOf(readIdList(
                new CheckpointKey(CheckpointKind.PENDING_WITHDRAW, productId + US + version)));
    }

    /** Overwrite a claimed withdraw to RESOLVED with its matched publish id. */
    public synchronized void resolveWithdraw(String withdrawId, String matchedPublishId) {
        Objects.requireNonNull(matchedPublishId, "matchedPublishId must not be null");
        CheckpointKey key = new CheckpointKey(CheckpointKind.WITHDRAW, withdrawId);
        IngestedWithdraw current = store.read(key)
                .map(raw -> decodeWithdraw(withdrawId, raw))
                .orElseThrow(() -> new IllegalStateException("withdraw not claimed: " + withdrawId));
        if (current.status() == WithdrawStatus.RESOLVED) {
            return;
        }
        store.write(key, encodeWithdraw(new IngestedWithdraw(
                withdrawId, current.productId(), current.version(), WithdrawStatus.RESOLVED,
                matchedPublishId, current.withdrawnAt())));
    }

    // ---- reads ----

    /** Decoded PUBLISH record, or empty if never claimed. */
    public Optional<IngestedPublish> publish(String publishId) {
        return store.read(new CheckpointKey(CheckpointKind.PUBLISH, publishId))
                .map(raw -> decodePublish(publishId, raw));
    }

    /** Decoded WITHDRAW record, or empty if never claimed. */
    public Optional<IngestedWithdraw> withdraw(String withdrawId) {
        return store.read(new CheckpointKey(CheckpointKind.WITHDRAW, withdrawId))
                .map(raw -> decodeWithdraw(withdrawId, raw));
    }

    // ---- encoding ----

    static String encodePublish(IngestedPublish p) {
        return p.productId() + US + p.toVersion() + US + p.publishedAt().toEpochMilli();
    }

    static IngestedPublish decodePublish(String publishId, String value) {
        String[] parts = value.split(String.valueOf(US), -1);
        if (parts.length != 3) {
            throw new IllegalStateException("malformed PUBLISH checkpoint value");
        }
        return new IngestedPublish(publishId, parts[0], parts[1], Instant.ofEpochMilli(Long.parseLong(parts[2])));
    }

    static String encodeWithdraw(IngestedWithdraw w) {
        String matched = w.matchedPublishId() == null ? "" : w.matchedPublishId();
        return w.productId() + US + w.version() + US + w.status() + US + matched
                + US + w.withdrawnAt().toEpochMilli();
    }

    static IngestedWithdraw decodeWithdraw(String withdrawId, String value) {
        String[] parts = value.split(String.valueOf(US), -1);
        if (parts.length != 5) {
            throw new IllegalStateException("malformed WITHDRAW checkpoint value");
        }
        WithdrawStatus status = WithdrawStatus.valueOf(parts[2]);
        String matched = parts[3].isEmpty() ? null : parts[3];
        return new IngestedWithdraw(withdrawId, parts[0], parts[1], status, matched,
                Instant.ofEpochMilli(Long.parseLong(parts[4])));
    }

    private List<String> readIdList(CheckpointKey key) {
        Optional<String> raw = store.read(key);
        if (raw.isEmpty() || raw.get().isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(List.of(raw.get().split(LF)));
    }
}
