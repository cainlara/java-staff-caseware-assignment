# Contract: Backfill Dead-Letter Identity

**Phase**: 4 · **Spec**: B8, FR-051/FR-052, SC-009 · **Research**: D4, D10

## Scope

Exhausted backfill work units reuse the delivered `DeadLetterRecord` +
`DeadLetterStore` (one alerting surface). The record shape is unchanged; the
backfill work identity is encoded into existing fields by the codec
`BackfillDeadLetterIdentity` (encode + decode, contract-tested).

## Unit kinds (additive enum values)

| `DeadLetterUnitKind` | Unit |
|----------------------|------|
| `BACKFILL_ENGAGEMENT` | one engagement's load → compare → write pipeline |
| `BACKFILL_BATCH` | one `EngagementEnumerator.nextBatch` fetch (incl. store read failures) |
| `BACKFILL_CURSOR` | cursor advance/complete/decode failure (`chunkIndex = -1`) |

## Encoding

`US` = U+001F (delivered delimiter discipline; ids assumed delimiter-free).

| Unit | `eventId` | `productId` | `chunkIndex` |
|------|-----------|-------------|--------------|
| `BACKFILL_ENGAGEMENT` | `bf US firmId US batchIndex US engagementId` | product id | batch index ≥ 0 |
| `BACKFILL_BATCH` | `bf US firmId US batchIndex` | product id (when known; else `unknown`) | batch index |
| `BACKFILL_CURSOR` | `bf US firmId` | product id (when known; else `unknown`) | −1 |

Other fields: `attemptCount` = attempts consumed (≥ 1);
`lastFailureType` = failure class name; `lastFailureMessage` = failure
identity string (port-level, identifier-only — never engagement content);
`recordedAt` = clock instant. Decoding a non-`bf` `eventId` or a malformed
segment count throws `IllegalStateException` (corrupt DLQ identity, loud).

## Semantics (delivered retry rules, applied per unit)

1. Transient failures (`TransientFanOutException`) retry with bounded
   exponential backoff + jitter under the injected clock; attempt and delay
   bounds come from `BackfillConfig.retryPolicy`.
2. On exhaustion: store the record, increment `fanout.dlq` (tag
   `unit_kind=BACKFILL_*`), throw `FanOutExhaustedException` — never a
   silent drop.
3. **Batch progress rule**: a dead-lettered `BACKFILL_ENGAGEMENT` is a
   *resolved* outcome for cursor purposes — its batch still checkpoints
   (the poisoned engagement must not block its firm forever). The DLQ entry
   is the tracked coverage gap.
4. **Batch-fetch rule**: a dead-lettered `BACKFILL_BATCH` does NOT advance
   the cursor; the firm's position stays put and a later run retries the
   fetch.
5. Replay safety: re-executing any dead-lettered unit is safe under the
   index-writer idempotency contract; final state equals first-attempt
   success (FR-052). Replay tooling itself is out of scope.
6. `AdmissionHaltedException` is never dead-lettered (halt is not a
   failure — capacity-shares.md).

## Invariants

1. Every stored backfill record decodes to `(firmId, batchIndex[, engagementId])`
   — full work identity (FR-051).
2. All encoded segments are identifiers; fuzz tests assert no record field
   equals or embeds engagement content sentinels.
3. Alert counter increments exactly once per stored record.
