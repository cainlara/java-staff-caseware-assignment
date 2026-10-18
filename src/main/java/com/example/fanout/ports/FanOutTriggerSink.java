package com.example.fanout.ports;

import com.example.fanout.domain.FanOutTrigger;

/**
 * Receives internal fan-out triggers emitted by the ingest service after a
 * successful checkpoint claim. Stub phase: the shipped implementation only
 * records emissions; a real consumer (scheduler) arrives with the fan-out
 * phase.
 *
 * <p><b>Idempotency key (consumer obligation)</b>: {@code (kind, eventId,
 * productId)}. Emission is at-most-once per ingest transition — the ingest
 * service emits only after the event's checkpoint claim succeeded and never
 * re-emits on redelivery (duplicates take the duplicate path before reaching
 * this sink). Consumers MUST still treat the key above as idempotent: the
 * checkpoint-to-trigger crash window plus a future reconciler may legitimately
 * re-derive a trigger from durable checkpoint state.
 *
 * <p><b>Consistency</b>: this sink is NOT a durability boundary — it is an
 * in-process signal. {@link CheckpointStore} remains the sole progress truth.
 * A failed or absent consumer MUST NOT block or roll back ingest.
 *
 * <p><b>Ordering</b>: within one product, triggers are emitted in
 * ingest-sequence order by a single ingest call path; global ordering across
 * products is unspecified.
 *
 * <p><b>Confidentiality</b>: trigger payloads carry identifiers and
 * measurements only — never engagement content.
 */
public interface FanOutTriggerSink {

    void emit(FanOutTrigger trigger);
}
