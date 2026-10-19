# Phase 1 Data Model: Capacity-Aware Fan-Out Engine

**Feature**: 003-capacity-aware-fanout | **Date**: 2026-10-18

New entities are immutable Java records with compact-constructor validation
(phase-1 convention). Phase-1/2 entities (`EngagementRef`, `PendingIndexRow`,
`FanOutTrigger`, `TemplatePublished`, `TemplateWithdrawn`) are unchanged and
only referenced. Field lists are authoritative for implementation.

## Entity: FanOutTask (new)

One schedulable chunk of a run — a positional slice of the ordered eligibility
list. Never carries engagement content.

| Field | Type | Key? | Validation |
|-------|------|------|------------|
| eventId | String | part of task identity | mandatory, non-blank (publish id or withdraw id) |
| productId | String | part of task identity | mandatory, non-blank |
| kind | enum `FanOutRunKind { PUBLISH, WITHDRAW_SUPERSEDE }` | | mandatory, non-null |
| chunkIndex | int | part of task identity | >= 0 |
| firmId | String | fairness key | mandatory, non-blank |
| engagements | `List<EngagementRef>` | | mandatory, non-null, non-empty, defensively copied unmodifiable; each element validated by `EngagementRef` |

- **Task identity**: `(kind, eventId, productId, chunkIndex)` — matches the
  cursor's positional chunking (spec R2/FR-003).
- Rejected: null kind / blank strings / chunkIndex < 0 / empty list.

## Entity: FanOutCursor (new)

Per-run progress record held in the checkpoint store (sole resume truth,
spec FR-050). Not stored as a record — encoded per D3 schema in
data-model below; this record is the decoded view.

| Field | Type | Key? | Validation |
|-------|------|------|------------|
| kind | enum `FanOutRunKind` | part of cursor key | mandatory, non-null |
| eventId | String | part of cursor key | mandatory, non-blank |
| productId | String | part of cursor key | mandatory, non-blank |
| status | enum `FanOutRunStatus { RUNNING, COMPLETE }` | | mandatory, non-null |
| totalChunks | int | | >= 0 |
| lastCompletedChunkIndex | int | | >= -1 (-1 = none completed) |

- **Cursor key**: `CheckpointKey(FANOUT_CURSOR, kind + US + eventId +
  US + productId)` (US = U+001F).
- Invariant: `lastCompletedChunkIndex < totalChunks` whenever
  `totalChunks > 0`; `COMPLETE` implies
  `lastCompletedChunkIndex == totalChunks - 1` (or `totalChunks == 0`).

## Entity: DeadLetterRecord (new)

Identity of an exhausted work unit — the replay handle. Never carries
engagement content; `lastFailureMessage` comes from port-level exceptions
(identifier-only by phase-1 contract).

| Field | Type | Key? | Validation |
|-------|------|------|------------|
| eventId | String | part of replay lookup | mandatory, non-blank |
| productId | String | | mandatory, non-blank |
| chunkIndex | int | | >= -1 (-1 = non-chunk unit) |
| unitKind | enum `DeadLetterUnitKind { CHUNK_WRITE, ELIGIBILITY_STREAM, DOWNSTREAM_CALL, CURSOR_WRITE, SUPERSEDE_PLAN }` | | mandatory, non-null |
| attemptCount | int | | >= 1 |
| lastFailureType | String | | mandatory, non-blank (exception class name) |
| lastFailureMessage | String | | mandatory, may be empty (never engagement content) |
| recordedAt | java.time.Instant | | mandatory, non-null |

## Entity: FanOutConfig (new, tunables)

Single validation-friendly record of every configurable bound (spec
Assumptions list). Tests override freely.

| Field | Type | Default | Validation |
|-------|------|---------|------------|
| chunkSize | int | 500 | >= 1 |
| poolBound | int | 16 | >= 1 |
| firmInFlightCap | int | 2 | >= 1 |
| retryMaxAttempts | int | 5 | >= 1 |
| retryBaseDelay | Duration | 100 ms | positive |
| retryMaxDelay | Duration | 30 s | >= base delay |
| jitterFactorMin | double | 0.5 | >= 0.0, < jitterFactorMax |
| jitterFactorMax | double | 1.5 | > jitterFactorMin |

## Entity: FanOutRunOutcome (new)

Result of one engine entry call. Returned, never thrown, for skip and
dead-letter paths.

| Field | Type | Validation |
|-------|------|------------|
| status | enum `FanOutRunStatus_outcome { COMPLETED, COMPLETED_EMPTY, SKIPPED_DUPLICATE, SKIPPED_IN_PROGRESS, FAILED_DEAD_LETTERED }` | mandatory, non-null |
| eventId | String | mandatory, non-blank |
| chunkCount | int | >= 0 |
| rowsResolved | long | >= 0 (writes + duplicate skips + converged re-applies) |
| deadLetterCount | int | >= 0 |

- `COMPLETED`: cursor claimed by this run and moved to COMPLETE with ≥ 1 chunk.
- `COMPLETED_EMPTY`: zero eligible engagements; cursor COMPLETE with
  totalChunks = 0 (spec FR-004).
- `SKIPPED_DUPLICATE`: cursor already COMPLETE (replay converged; spec
  FR-053/SC-004).
- `SKIPPED_IN_PROGRESS`: cursor RUNNING and claimed by another live run
  (duplicate trigger racing an active run; the durable cursor stays the truth).
- `FAILED_DEAD_LETTERED`: a unit exhausted retries, was stored in the
  dead-letter store, alert incremented; other work continued where possible
  (spec FR-041).

## Enum: CheckpointKind (extended, additive)

```
PUBLISH | BACKFILL_BATCH | WITHDRAW | PRODUCT_INGEST | PENDING_WITHDRAW | PRODUCT_PUBLISHES
| FANOUT_CURSOR (new) | PRODUCT_WITHDRAWN_VERSION (new)
```

- Phase-1/2 constants unchanged (backward compatible, Constitution VII).

## Checkpoint value schemas (new kinds; US = U+001F, LF = newline)

Encoding lives only in `FanOutCursorRepository` (cursor) and the withdraw
marker section of the same repository (marker). Natural keys assumed
delimiter-free (inherited phase-2 assumption).

### FANOUT_CURSOR (key id = `kind + US + eventId + US + productId`)

```
status (RUNNING | COMPLETE) US totalChunks US lastCompletedChunkIndex
```

- Written once atomically via `writeIfAbsent` as the run claim (RUNNING,
  totalChunks known after materialization, lastCompleted = -1); advanced by
  last-write-wins rewrite after each completed chunk; rewritten to COMPLETE
  at end. Claim `false` + COMPLETE => duplicate skip; claim `false` + RUNNING
  => in-progress skip, or resume continuation after restart (spec FR-051).
- Corrupt/unknown encoding fails loudly (exception -> dead-letter/alert,
  spec Edge Case).

### PRODUCT_WITHDRAWN_VERSION (key id = productId)

```
version1 LF version2 LF ...
```

- Append-if-absent list of withdrawn versions for a product, written by the
  engine before supersede work (D7). Read when selecting the next eligible
  non-withdrawn version (spec R7). Append is synchronized
  read-modify-write (same pattern as `PRODUCT_PUBLISHES`).

## Entity: FairShardState (derived, in-process, not stored)

Per-run scheduling state built from the materialized eligibility list:

- `Map<firmId, Deque<FanOutTask>>` — FIFO within firm (index order).
- `TreeSet<firmId>` pending-firm order + rotating cursor (round-robin).
- `Map<firmId, Semaphore>` — one permit pool of `firmInFlightCap` per firm.
- Invariant (spec FR-011, asserted at every observation point): per-firm
  in-flight ≤ `firmInFlightCap`; total in-flight ≤ `poolBound`.

## IndexWriter v2 (additive method)

| Operation | Semantics |
|-----------|-----------|
| `find(engagementId, offeredVersion, state)` | Current stored row for the idempotency triple, or empty. Read-only, no engagement content. |

Engine write path (spec R4/FR-021): upsert presenting expected `rowVersion`
→ on `REJECTED_STALE`: `find`, deterministic merge (below), re-upsert once →
second `REJECTED_STALE` is transient failure (retry → DLQ).

### Deterministic merge on re-apply (spec R4, FR-021, FR-060/FR-061)

Given stored row S and intended row I for the same idempotency triple:

- `engagementId/firmId/productId/currentVersion/offeredVersion/state`: take
  I's values (I is the engine's recomputed intent against S's fresh version).
- `decision`, `pinnedRanges`, `summaryKey`: take S's values when I is a
  publish-kind write (history preserved, never clobbered); take the
  supersede intent's preserved copies when I is a supersede write (they were
  themselves copied from S at plan time — same bytes).
- `publishIds`: union of S.publishIds and I.publishIds.
- `rowVersion`: S.rowVersion + 1 (presented on the re-upsert).
- `updatedAt`: I's timestamp (the later writer).

Exactly one concurrent writer advances each version step; the loser observes
`REJECTED_STALE` and re-enters the cycle — final content is a deterministic
function of the merged inputs, independent of interleaving.

## EngagementEligibilitySource (new port)

| Operation | Semantics |
|-----------|-----------|
| `Stream<EngagementRef> streamEligible(productId, publishId)` | Eligible engagements for the publish, from index metadata only, ordered firm-id asc then engagement-id asc (spec R1). |
| `Stream<EngagementRef> streamOffering(productId, version)` | Rows currently offering `version`, same ordering (supersede input, D7). |

Fake: `FakeEligibilitySource` — in-memory list per key, programmatic
generators for scale tests (D12), failure injection for
`ELIGIBILITY_STREAM` retry tests.

## DeadLetterStore (new port)

| Operation | Semantics |
|-----------|-----------|
| `store(record)` | Append one record (last-write-wins not applicable; append-only). |
| `byEvent(eventId)` | Records for one event, in store order (replay/tests). |

Fake: `FakeDeadLetterStore` (synchronized list).

## FanOutCursorRepository operations (typed adapter)

| Operation | Kind(s) | Semantics |
|-----------|---------|-----------|
| `claimRun(kind, eventId, productId, totalChunks)` | FANOUT_CURSOR | `writeIfAbsent` RUNNING; true = claimed by this run |
| `readRun(kind, eventId, productId)` | FANOUT_CURSOR | decoded cursor or empty |
| `advance(kind, eventId, productId, totalChunks, lastCompleted)` | FANOUT_CURSOR | last-write-wins rewrite RUNNING |
| `complete(kind, eventId, productId, totalChunks)` | FANOUT_CURSOR | rewrite COMPLETE |
| `markWithdrawn(productId, version)` | PRODUCT_WITHDRAWN_VERSION | append-if-absent (synchronized) |
| `withdrawnVersions(productId)` | PRODUCT_WITHDRAWN_VERSION | list (possibly empty) |
| `ingestedPublishes(productId)` | PRODUCT_PUBLISHES + PUBLISH | decoded `IngestedPublish` list (reuses phase-2 decoding) |

## State transitions (this phase)

### Fan-out run lifecycle (per trigger identity)

```
(no cursor) ──claim (writeIfAbsent, RUNNING)──> RUNNING
RUNNING ──chunk completes (all writes resolved)──> RUNNING (cursor += 1)
RUNNING ──all chunks done──> COMPLETE
(no cursor claim) cursor already COMPLETE ──> SKIPPED_DUPLICATE (no writes)
(no cursor claim) cursor already RUNNING, live other run ──> SKIPPED_IN_PROGRESS
RUNNING (post-restart) ──resume──> RUNNING (from lastCompleted + 1)
RUNNING ──unit retries exhausted──> unit in DeadLetterStore + alert; run may
                                     still COMPLETE other chunks (FAILED_DEAD_LETTERED
                                     outcome if any unit was dead-lettered)
```

### Index row lifecycle touched by this phase

```
(no row) ──publish chunk write──> PENDING (offeredVersion = toVersion)
PENDING(V) ──supersede, next version N exists──> PENDING(N)  [decision history preserved]
PENDING(V) ──supersede, no next version───────> CREATED      [decision history preserved]
(any) ──CAS stale rejection──> re-read, merge, re-apply once (deterministic)
```

## Relationships (summary)

```
FanOutTrigger ──(triggers)──> FanOutRun (one cursor per (kind, eventId, productId))
FanOutRun ──(materializes from)──> EngagementEligibilitySource ──(streams)──> EngagementRef
FanOutRun ──(partitions into)──> FanOutTask* ──(scheduled by)──> FairShardState
FanOutTask ──(writes via)──> IndexWriter ──> PendingIndexRow
FanOutRun ──(progress in)──> CheckpointStore(FANOUT_CURSOR)
Withdraw trigger ──(records)──> CheckpointStore(PRODUCT_WITHDRAWN_VERSION)
                             ──(plans from)──> streamOffering + ingestedPublishes - withdrawn
Exhausted unit ──(stored in)──> DeadLetterStore(DeadLetterRecord)
FanOutMetrics ──(observes)──> all of the above (counters/timers only)
```
