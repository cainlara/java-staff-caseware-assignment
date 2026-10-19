# Contract: FanOutEngine

**Component**: `com.example.fanout.fanout.FanOutEngine` (new)

Consumes a phase-2 `FanOutTrigger` and materializes it as Pending Update Index
rows: publish fan-out and withdraw supersede. Chunked, fair-sharded,
checkpointed, idempotent, retry-bounded. Zero downstream engagement-load
calls — by construction (no such dependency) and by per-test assertion.

## Signature (conceptual)

```
FanOutRunOutcome runPublish(trigger: FanOutTrigger)                 // kind = PUBLISH
FanOutRunOutcome runWithdrawSupersede(trigger: FanOutTrigger)       // kind = WITHDRAW

FanOutRunOutcome = (status: COMPLETED | COMPLETED_EMPTY | SKIPPED_DUPLICATE
                    | SKIPPED_IN_PROGRESS | FAILED_DEAD_LETTERED,
                    eventId, chunkCount, rowsResolved, deadLetterCount)
```

Constructor-injected: `EngagementEligibilitySource`, `IndexWriter`,
`CheckpointStore` (via `FanOutCursorRepository`), `FanOutMetrics`,
`FanOutConfig`, `TaskRunner`, `BackoffWaiter`, `java.time.Clock`,
`JitterSource`. Throws only on null arguments. The engine has NO
`DownstreamLoadClient` / `CapacityBudget` dependency (spec FR-032;
off-path gating is `PermitGuardedDownstream`, a separate component).

## Publish run (normative; spec R1–R4, R6, FR-002…FR-053)

1. Validate trigger (kind matches entry point, fields non-blank — record
   construction already enforces this).
2. Stream + materialize eligibility in contract order (R1); partition into
   positional chunks of `chunkSize` (R2). Zero chunks => claim cursor with
   totalChunks = 0, mark COMPLETE, return `COMPLETED_EMPTY` (FR-004).
3. Claim the run: `writeIfAbsent(FANOUT_CURSOR, RUNNING, totalChunks, -1)`.
   - Claim false + COMPLETE => `SKIPPED_DUPLICATE`, zero writes (FR-053).
   - Claim false + RUNNING => resume continuation: read cursor; if restart
     scenario, continue from `lastCompletedChunkIndex + 1` (FR-051). A
     second live runner is excluded by the single-claimant convention; if a
     run observes another live runner it returns `SKIPPED_IN_PROGRESS`.
4. Schedule remaining chunks via `FairShardScheduler` (R3) on the bounded
   `TaskRunner`. Per chunk, per engagement:
   a. Build intended `PendingIndexRow`: `offeredVersion` = publish
      to-version (obtained from the eligibility refs' product context /
      trigger), `state` = PENDING, `publishIds` = {publishId}, fresh
      `updatedAt` from the injected clock, `rowVersion` = 1 for new triples.
   b. Upsert through `IndexWriter`. `WRITTEN` / `SKIPPED_DUPLICATE`:
      success. `REJECTED_STALE`: `find`, deterministic merge
      (data-model.md), re-upsert once; second stale => transient failure.
   c. Transient failures: `RetryExecutor` per R6 (bounded exponential +
      jitter via waiter/clock/jitter source). Exhaustion: store
      `DeadLetterRecord(CHUNK_WRITE, chunkIndex, …)`, increment
      `fanout.dlq`, count the chunk as dead-lettered; the run continues
      other chunks and ends `FAILED_DEAD_LETTERED` if any unit died (never
      silent drop, FR-041).
5. After every write of a chunk has resolved (success or dead-letter —
   dead-lettered chunks still advance the cursor so the run terminates;
   the DLQ holds the gap for replay), advance the cursor
   (`lastCompletedChunkIndex = chunkIndex`).
6. On all chunks done: rewrite cursor COMPLETE; record timer
   `fanout.publish_to_pending`; return outcome.

## Withdraw supersede run (normative; spec R7, FR-060…FR-063)

1. Atomically record the withdrawn marker
   (`PRODUCT_WITHDRAWN_VERSION` append-if-absent) — before any supersede
   write (durable across crashes, D7).
2. Claim cursor (same as publish; `kind = WITHDRAW_SUPERSEDE`).
3. Plan: `streamOffering(productId, withdrawnVersion)` materializes rows
   offering V. Next eligible version N = max `(publishedAt, publishId)`
   among ingested publishes whose to-version is non-withdrawn (marker list)
   and strictly newer than V's publish under phase-2 R4 ordering; none =>
   target state `CREATED` (spec FR-060).
4. Chunk + schedule identically to publish. Per row: intended write =
   same idempotency triple when state/value changes require an update via
   `find`+merge; **preserve** `decision`, `pinnedRanges`, `summaryKey` from
   the stored row (spec FR-061, SC-008); set `offeredVersion` = N or keep
   version with `state` = CREATED; add the supersede identity to
   `publishIds`; bump `rowVersion` via CAS (expected = stored version).
5. Zero offering rows => `COMPLETED_EMPTY` with cursor (FR-063).
6. Same retry/DLQ/cursor rules as publish (FR-062).

## Fair scheduling (normative; spec R3, FR-010…FR-012)

- Per-firm FIFO deques built from the materialized list; `TreeSet` scan with
  rotating cursor; one `Semaphore(firmInFlightCap)` per firm; skip firms at
  cap (do not queue them ahead); bound total in-flight by `poolBound`.
- Invariants asserted at every observation point (tests): per-firm in-flight
  ≤ cap; total in-flight ≤ poolBound; median firms complete while the
  largest firm still has pending chunks (SC-005).

## Hot-path invariants

- Zero `DownstreamLoadClient` calls; zero `CapacityBudget` acquisitions
  (asserted per test via call-log and available-permits inspection).
- No engagement content in rows, checkpoints, dead letters, metrics, logs
  (spec FR-070).
- Correctness never depends on metric values.

## Metric emissions (identifier-only; v2 fan-out-metrics.md)

| Counter / timer | When |
|---|---|
| `fanout.chunks.started` | chunk execution begins |
| `fanout.chunks.completed` | chunk fully resolved (tag `product_id`) |
| `fanout.rows.resolved` | delta = rows resolved in a chunk |
| `fanout.retry` | transient failure retried |
| `fanout.dlq` | unit exhausted -> stored (alert; tags `product_id`, `unit_kind`) |
| `fanout.duplicate.run` | cursor claim said skip |
| `fanout.cas.reapply` | stale-rejection re-apply cycle |
| `fanout.publish_to_pending` (timer) | trigger receipt -> cursor COMPLETE |

## Config defaults (FanOutConfig)

chunkSize 500 · poolBound 16 · firmInFlightCap 2 · retryMaxAttempts 5 ·
retryBaseDelay 100 ms · retryMaxDelay 30 s · jitter [0.5, 1.5).

## Contract test vectors

1. Publish, 1,000 eligible -> 1,000 PENDING rows, 2 chunks (size 500),
   cursor COMPLETE, zero downstream calls (FR-001/FR-032, SC-001).
2. Publish, zero eligible -> `COMPLETED_EMPTY`, zero writes (FR-004).
3. Replay of completed publish -> `SKIPPED_DUPLICATE`, zero writes,
   identical row set (FR-053, SC-004).
4. Duplicate chunk execution (concurrent or post-crash) -> identical row set,
   one cursor advance (FR-052/FR-053).
5. Writer fails k < budget times -> succeeds; attempts and delays within
   policy bounds under fake clock + seeded jitter (FR-040, SC-006).
6. Writer fails >= budget -> `FailedFanOutException`… unit stored in
   dead-letter store, `fanout.dlq` incremented, run outcome
   `FAILED_DEAD_LETTERED`, no silent drop (FR-041, SC-007).
7. Kill after chunk k-1, restart with same store -> resume at chunk k;
   final rows identical to fault-free run (FR-051, SC-003).
8. Fairness fixture (1 big + 500 median firms) -> caps held; median firms
   complete first (FR-012, SC-005).
9. Withdraw supersede: rows offering V -> next version N with decision
   fields byte-identical; no N -> CREATED with fields preserved
   (FR-060/FR-061, SC-008).
10. Every test above: downstream call log empty (FR-032, SC-001).
