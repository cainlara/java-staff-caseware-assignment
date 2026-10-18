# Contract: FanOutTriggerSink

**Port**: `com.example.fanout.ports.FanOutTriggerSink`

Receives internal fan-out triggers emitted by `IngestService` after a
successful checkpoint write. Stub phase: the shipped implementation only
records emissions; a real consumer (scheduler) arrives with the fan-out phase.

## Signature (conceptual)

```
void emit(FanOutTrigger trigger)

FanOutTrigger = (kind: PUBLISH | WITHDRAW,
                 eventId: non-blank String,
                 productId: non-blank String,
                 ingestSequence: long >= 1,
                 matchedPublishId: String|null)   // non-blank iff kind = WITHDRAW
```

## Idempotency key (consumer obligation, R5)

```
(kind, eventId, productId)
```

- Emission is **at-most-once** per ingest transition: `IngestService` emits
  only after the event's checkpoint claim succeeded, and never re-emits on
  redelivery (duplicates take the DUPLICATE path before reaching the sink).
- Consumers MUST treat the key above as idempotent anyway: the checkpoint-to-
  trigger crash window plus a future reconciler may legitimately re-derive a
  trigger from durable checkpoint state (FR-024).
- Within one product, triggers are emitted in ingest-sequence order by a
  single `IngestService` call path (R5); global ordering across products is
  unspecified.

## Payload rules

- `matchedPublishId` is non-blank exactly when `kind = WITHDRAW` (the resolved
  publish id); null for `kind = PUBLISH` (data-model.md validation).
- Payload carries identifiers and measurements only — never engagement
  content (FR-031).
- `ingestSequence` is the per-product arrival-order sequence assigned at
  ingest; observational for consumers that need per-product ordering.

## Durability

- The sink is **not** a durability boundary: it is an in-process signal.
  `CheckpointStore` remains the sole progress truth. A failed or absent
  consumer MUST NOT block or roll back ingest; emission errors surface to the
  caller (crash-injection seam) but never corrupt checkpoint state.

## Shipped stub

`RecordingFanOutTriggerSink` (main source, `adapters.inmemory`): thread-safe
recording list; `emitted()` returns an unmodifiable snapshot; `clear()` for
test isolation. Also serves as the phase-2 seam.

## Contract test vectors

1. Emit valid PUBLISH trigger -> recorded once; payload round-trips.
2. Emit valid WITHDRAW trigger (matchedPublishId set) -> recorded once.
3. Invalid trigger (blank id, ingestSequence < 1, wrong matchedPublishId
   nullability) -> construction rejected (FanOutTrigger validation test).
4. `IngestService` integration: duplicate delivery reaches the sink zero times
   (see ingest-service.md vector 2).
5. Consumer-idempotency note: emitting the same key twice is the consumer's
   concern; the sink itself is a passive recorder.
