package com.example.fanout.ports;

/**
 * Outcome of an {@link IndexWriter#upsert} call.
 *
 * <ul>
 *   <li>{@link #WRITTEN} — row stored (insert or compare-and-set update).</li>
 *   <li>{@link #SKIPPED_DUPLICATE} — identical effective state already present
 *       for the idempotency key; no-op, success.</li>
 *   <li>{@link #REJECTED_STALE} — incoming {@code rowVersion} is not the stored
 *       current version; caller must re-read.</li>
 * </ul>
 */
public enum WriteResult {
    WRITTEN,
    SKIPPED_DUPLICATE,
    REJECTED_STALE
}
