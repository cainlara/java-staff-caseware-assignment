package com.example.fanout.ports;

/**
 * Domain of a checkpoint key.
 *
 * <p>Phase 1 kinds: {@link #PUBLISH} (publish progress / ingest claim),
 * {@link #BACKFILL_BATCH} (backfill-batch progress).
 *
 * <p>Phase 2 kinds (internal to ingest progress encoding; values are
 * string-encoded by {@code IngestProgressRepository}):
 * <ul>
 *   <li>{@link #WITHDRAW} — one withdraw record per withdraw id
 *       (idempotency claim + deferred/resolved status).</li>
 *   <li>{@link #PRODUCT_INGEST} — per-product ingest sequence counter
 *       (observational; effective version uses the published-at/publish-id
 *       rule, not this counter).</li>
 *   <li>{@link #PENDING_WITHDRAW} — resolution index of deferred withdraw ids
 *       for one (product, version); key id is {@code productId + US + version}.</li>
 *   <li>{@link #PRODUCT_PUBLISHES} — per-product ingest-ordered list of
 *       publish ids (all retained; arrival order recorded).</li>
 * </ul>
 */
public enum CheckpointKind {
    PUBLISH,
    BACKFILL_BATCH,
    WITHDRAW,
    PRODUCT_INGEST,
    PENDING_WITHDRAW,
    PRODUCT_PUBLISHES
}
