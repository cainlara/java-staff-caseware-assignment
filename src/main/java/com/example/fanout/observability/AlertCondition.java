package com.example.fanout.observability;

/**
 * The five alert conditions (spec data-model.md, contracts/alert-hooks.md).
 * Closed set: a new condition is a spec change, not a config change.
 */
public enum AlertCondition {

    /** Ring p99 of {@code fanout.publish_to_pending} over its limit. */
    FRESHNESS_BURN,

    /** A non-COMPLETE run whose publish is older than the coverage age limit. */
    COVERAGE_BREACH,

    /** Dead-letter store depth above zero. */
    DLQ_NON_EMPTY,

    /** Backfill enabled, not halted, with a stalled checkpoint. */
    BACKFILL_STALLED,

    /** Downstream pinned at capacity with zero completions over the window. */
    DOWNSTREAM_SATURATION
}
