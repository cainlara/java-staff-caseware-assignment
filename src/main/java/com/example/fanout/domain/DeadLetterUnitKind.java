package com.example.fanout.domain;

/**
 * Kind of work unit that can exhaust its retry budget and be dead-lettered
 * (spec R6 / data-model.md DeadLetterRecord).
 */
public enum DeadLetterUnitKind {
    /** One chunk's index writes. */
    CHUNK_WRITE,
    /** One eligibility stream read (materialization or supersede planning input). */
    ELIGIBILITY_STREAM,
    /** One off-path downstream engagement-load call. */
    DOWNSTREAM_CALL,
    /** Cursor read/decode/advance failure. */
    CURSOR_WRITE,
    /** Supersede plan computation (withdrawn marker / next-version selection). */
    SUPERSEDE_PLAN,
    /** One backfill engagement's load, compare, and write pipeline. */
    BACKFILL_ENGAGEMENT,
    /** One backfill enumeration fetch (including cursor store read failures). */
    BACKFILL_BATCH,
    /** Backfill cursor advance, complete, or decode failure (chunkIndex = -1). */
    BACKFILL_CURSOR
}
