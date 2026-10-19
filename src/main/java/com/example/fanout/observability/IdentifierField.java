package com.example.fanout.observability;

/**
 * The log-field whitelist (spec FR-004). A closed set: this enum IS the
 * whitelist, so a non-whitelisted log field is unrepresentable at compile
 * time.
 *
 * <p>Declaration order is significant — JSON serialization emits identifiers
 * in exactly this order (deterministic tests).
 *
 * <p>{@link #ENGAGEMENT_ID} is an error-path field: it is permitted only on
 * the retry/dead-letter events, never on hot-path success logs and never as a
 * metric tag (spec FR-003/FR-013).
 */
public enum IdentifierField {

    /** Bus publish identity. */
    publish_id,

    /** Product id (≤ ~40 distinct). */
    product_id,

    /** Firm id (≤ ~4,000 distinct). */
    firm_id,

    /** Engagement id — error/DLQ/retry paths only. */
    engagement_id,

    /** Data-residency marker. */
    region,

    /** Positional chunk index (int as string). */
    chunk_id,

    /** State transition value. */
    state
}
