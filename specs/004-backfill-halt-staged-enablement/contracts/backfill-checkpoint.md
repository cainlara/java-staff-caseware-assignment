# Contract: Backfill Checkpoint (BACKFILL_BATCH)

**Phase**: 4 · **Spec**: B4, FR-004/FR-005, edge cases (corrupt cursor,
halt-mid-batch) · **Research**: D2 · **Schema**: data-model.md
"Backfill cursor"

## Storage

Existing `CheckpointStore` port, existing `CheckpointKey(CheckpointKind.BACKFILL_BATCH,
firmId)` (kind reserved in phase 1). One key per firm. The
`BackfillCursorRepository` class is the sole encoder/decoder — same pattern
and delimiter discipline as the delivered `FanOutCursorRepository` (`US` =
U+001F; natural keys assumed delimiter-free).

## Value encoding

```
status US lastResolvedEngagementId
```

- `status` ∈ `RUNNING` | `COMPLETE` (unknown token ⇒ corrupt).
- `lastResolvedEngagementId`: last engagement id of the newest **fully
  resolved** batch (see resolution rule). Exclusive lower bound for resume.
- Missing key = start of enumeration (`afterEngagementId` empty).
- Stored empty `lastResolvedEngagementId` with `RUNNING` means "no batch
  resolved yet" (resume still starts from the beginning).

## Operations

| Operation | Semantics |
|-----------|-----------|
| `read(firmId)` | decode; `Optional.empty()` if absent; `IllegalStateException` on corrupt |
| `advance(firmId, lastResolvedEngagementId)` | last-write-wins rewrite of `RUNNING` |
| `complete(firmId, lastResolvedEngagementId)` | rewrite to `COMPLETE` |
| `startCursor(firmId)` | exclusive lower bound for the next `enumerator.nextBatch` call |

Single-runner assumption: concurrent advances to one firm's key are a caller
bug (same contract as `FANOUT_CURSOR`).

## Resolution rule (what "fully resolved" means)

A batch is fully resolved when every engagement in it reached a terminal
outcome:

1. row written (or duplicate skip / CAS converged), or
2. no-op (captured version == effective to-version, or no effective version), or
3. dead-lettered (retry exhausted; record stored; alert incremented).

Only then may `advance` run. A crash between the last write and the advance
re-runs the batch — at most one batch of redundant loads per firm; idempotent
writes make the row set fault-free-identical (spec FR-005, SC-003).

## Failure handling

- **Corrupt value** (bad field count, unknown status): the repository throws
  `IllegalStateException`; the worker dead-letters a `BACKFILL_CURSOR` unit
  and increments the alert counter; no writes, no silent restart from an
  earlier cursor.
- **Store unavailable** (read/write throws): the failure surfaces through the
  batch/cursor retry path (`BACKFILL_BATCH` / `BACKFILL_CURSOR` units);
  exhaustion dead-letters and alerts; admission of new batches pauses — never
  a cursor advance without a durable write.

## Invariants

1. `startCursor` is monotonic per firm (never decreases across the process).
2. A `COMPLETE` cursor is never rewritten by the worker (re-run is a no-op).
3. Values contain identifiers only.
