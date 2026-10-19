# Contract: DeadLetterStore

**Component**: `com.example.fanout.ports.DeadLetterStore` (new port)

Append-only store of exhausted work units — the replay handle. Replaces
silent drops (spec FR-041, R6).

## Signature (conceptual)

```
void store(record: DeadLetterRecord)
List<DeadLetterRecord> byEvent(eventId: non-blank String)
```

`DeadLetterRecord` fields (data-model.md): `eventId`, `productId`,
`chunkIndex` (>= -1), `unitKind`
(`CHUNK_WRITE | ELIGIBILITY_STREAM | DOWNSTREAM_CALL | CURSOR_WRITE | SUPERSEDE_PLAN`),
`attemptCount` (>= 1), `lastFailureType`, `lastFailureMessage`, `recordedAt`.

## Semantics (normative)

- `store` appends; it never overwrites and never deduplicates (two exhaustions
  of the same unit are two records — forensic fidelity).
- Records carry identifiers, failure class/message, and measurements only —
  never engagement content (spec FR-070). `lastFailureMessage` comes from
  port-level exceptions, which are identifier-only by phase-1 contracts.
- `byEvent` returns records in store order for one event id (replay and
  tests). An event with no records yields an empty list.
- Null record / blank eventId rejected.
- Correctness MUST NOT depend on metrics; the DLQ alert counter
  (`fanout.dlq`) is emitted by the engine/guard at store time, not by the
  store.

## Replay obligation (contract for later phases)

A replayed unit MUST be re-executable through the same engine paths; row
idempotency (IndexWriter contract) and cursor semantics make replay safe.
The replay tool itself is a later phase (spec Out of Scope).

## Fake: `FakeDeadLetterStore` (test)

Synchronized in-memory list; `store` appends; `byEvent` filters; test hooks:
`all()`, `count()`.

## Contract test vectors

1. Store two records for one event; `byEvent` returns both in order.
2. `byEvent` for unknown id returns empty list.
3. Record validation: blank eventId / attemptCount < 1 / null unitKind
   rejected at record construction.
4. Stored record content round-trips unchanged (fields preserved byte-for-byte
   where applicable — supports spec SC-007 "full work identity").
5. Every engine/guard test that exhausts retries asserts: exactly one record
   per exhausted unit, `fanout.dlq` incremented, run does not silently drop
   the unit.
