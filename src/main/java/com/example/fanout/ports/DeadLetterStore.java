package com.example.fanout.ports;

import com.example.fanout.domain.DeadLetterRecord;
import java.util.List;

/**
 * Append-only store of exhausted work units — the replay handle. Replaces
 * silent drops (spec FR-041, R6).
 *
 * <p><b>Semantics</b>: {@link #store} appends; it never overwrites and never
 * deduplicates (two exhaustions of the same unit are two records — forensic
 * fidelity). Records carry identifiers, failure class/message, and
 * measurements only — never engagement content (spec FR-070).
 *
 * <p>{@link #byEvent} returns records in store order for one event id; an
 * event with no records yields an empty list. Null record / blank eventId are
 * rejected.
 *
 * <p>Correctness MUST NOT depend on metrics; the dead-letter alert counter
 * ({@code fanout.dlq}) is emitted by the engine/guard at store time, not by
 * this store.
 *
 * <p>A replayed unit MUST be re-executable through the same engine paths; row
 * idempotency (IndexWriter contract) and cursor semantics make replay safe.
 * The replay tool itself is a later phase (spec Out of Scope).
 */
public interface DeadLetterStore {

    void store(DeadLetterRecord record);

    List<DeadLetterRecord> byEvent(String eventId);
}
