# Contract: BackfillWorker

**Phase**: 4 (new) · **Spec**: B2–B4, B7–B9, FR-002–FR-008, FR-030–FR-034,
FR-050–FR-052 · **Research**: D3, D7, D9, D10, D11

## Purpose

Incrementally backfill pending-update rows for pre-feature engagements:
enumerate → permit-gated version capture → idempotent row write → external
per-firm checkpoint, haltable and resumable at every boundary.

## Collaborators (constructor injection)

| Collaborator | Role |
|--------------|------|
| `EngagementEnumerator` | work source (per firm) |
| `BackfillCursorRepository` | external per-firm cursor (BACKFILL_BATCH) |
| `FanOutCursorRepository` | effective-version derivation (ingested publishes − withdrawn) |
| `IndexWriter` | idempotent row writes (delivered port) |
| `PermitGuardedDownstream` | the ONLY downstream call path; constructed on the **B_backfill** budget with the halt probe (capacity-shares.md, admission-gates.md) |
| `RetryExecutor` | bounded retry; exhaustion → DeadLetterStore via D4 identity |
| `AdmissionGate` | halt + product/firm gates at every admission |
| `FanOutMetrics` | counters (identifiers only) |
| `TaskRunner` | backfill pool — dedicated `fixedPool(bBackfill)`; never the fan-out pool |
| `BackfillConfig` | knobs + share validation |

The worker has **no** dependency on the fan-out pool or the repair-share
budget (structural independence, FR-020/FR-024).

## Pipeline (per engagement, spec B2)

1. **Admission** (`AdmissionGate`): `HALTED` ⇒ abort run (outcome HALTED, no
   further admissions, FR-030). `GATED_*` ⇒ firm skipped this cycle; not a
   failure; not buffered (FR-043).
2. **Permit**: `PermitGuardedDownstream.load(ref)` acquires one B_backfill
   permit before the call; blocked waiters re-check the halt probe each pause
   and abandon with `AdmissionHaltedException` (counted, not dead-lettered,
   FR-031); release exactly once in `finally`.
3. **Capture**: downstream returns the captured version string (opaque).
4. **Compare** (data-model.md): captured == effective to-version, or no
   effective version ⇒ NOOP. Else build the PENDING row recipe and
   `indexWriter.upsert`.
5. **CAS convergence**: `REJECTED_STALE` ⇒ re-read, re-apply once (delivered
   engine merge semantics); second stale ⇒ `TransientFanOutException` (retry).
6. **Resolve**: outcome counted (`backfill.engagements.{written,noop,dlq}`).

## Batch + cursor protocol (spec B4)

- `BackfillScheduler` admits at most `firmInFlightCap` concurrent batches per
  firm, round-robin across firms in rotating firm-id order (one batch per firm
  per cycle).
- A batch = `enumerator.nextBatch(firm, cursor, batchSize)` result; engagements
  execute sequentially on the batch's pool thread.
- The firm cursor advances (`BackfillCursorRepository.advance`) only after
  every engagement in the batch resolved (written / no-op / dead-lettered).
  Cursor failures retry as `BACKFILL_CURSOR` units; exhaustion dead-letters
  and alerts — never a silent skip.
- Enumerator fetch failures retry as `BACKFILL_BATCH` units; the cursor never
  advances past a failed fetch.
- Completion: enumerator `done` + final batch resolved ⇒ cursor `COMPLETE`.
- Corrupt cursor value ⇒ `BACKFILL_CURSOR` dead-letter + alert; no writes.

## Halt protocol (spec B6, FR-030–FR-034)

- Halt is read live at: run admission, batch admission, and every permit-wait
  iteration. No polling thread; observation latency ≤ one BackoffWaiter pause
  (≪ haltPollInterval).
- On halt: no new batches, no new permits, blocked waiters abandon; in-flight
  loads finish under normal rules; completed-batch checkpoints are already
  durable; all permits released. Drain wall-time is measured and emitted as
  `backfill.halt.drain` (timer).
- Resume: a fresh `run()` over the same stores continues per firm from the
  persisted cursors. At most one batch per firm re-executes; idempotent writes
  make the row set fault-free-identical (FR-005, FR-033).

## Entry point and outcome

```java
BackfillOutcome run();   // single runner assumed; re-entrant across restarts
```

| Outcome status | Meaning |
|----------------|---------|
| `COMPLETED` | every firm's cursor COMPLETE; deadLettered may be > 0 (gaps tracked in the DLQ) |
| `HALTED` | halt observed; some firms incomplete; cursors durable; safe to re-run |
| `FAILED_DEAD_LETTERED` | reserved for run-level failures (e.g. corrupt cursor) with zero progress possible |

The worker never throws for halt or per-engagement exhaustion; non-transient
failures (process death) propagate (crash-test seam).

## Counters (FR-053, identifiers only)

`backfill.batches.{admitted,completed}` · `backfill.engagements.{written,noop,dlq}`
· `backfill.admit.reject.{halt,product,firm}` · `backfill.halt.abandoned` ·
`backfill.halt.drain` (timer) · `backfill.permit.park` ·
`backfill.downstream.inflight.hwm` · `fanout.dlq` with
`unit_kind=BACKFILL_*` (delivered counter, tagged).

## Invariants (asserted by tests)

1. Observed in-flight loads ≤ B_backfill at every sample (SC-005).
2. Every acquired permit released exactly once — including halt-abandoned and
   failed loads.
3. Per-firm in-flight engagements ≤ firmInFlightCap (by construction: ≤ 1
   concurrent batch, sequential engagements).
4. Final row set of any interrupted/replayed run equals the fault-free run;
   per-engagement create-count == 1 (SC-001/SC-003).
5. Zero engagement content in every counter tag, exception message, and
   checkpoint value.
6. The worker never touches the fan-out pool or the repair-share budget.

## Non-goals

- Multi-runner coordination (single runner; idempotent writes are the safety
  net).
- Dead-letter replay tooling (replay = re-invoking the worker or a future
  tool; writes are idempotent).
- Per-engagement parallelism within a batch (knob `firmInFlightCap` governs
  cross-batch parallelism).
