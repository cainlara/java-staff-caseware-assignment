# Contract: Admission Gates (Halt + Staged Enablement)

**Phase**: 4 · **Spec**: B6, B9, FR-030–FR-034, FR-040–FR-044 ·
**Research**: D6, D8

## FeatureFlags (delivered port, unchanged)

`haltSwitch()`, `enabled(productId)`, `enabledFirm(firmId)`. Delivered
semantics stand: unknown ids ⇒ **false** (opt-in rollout), halt dominates,
null/blank ids rejected. Reconciliation D6: staged enablement is opt-in on
both paths; the gated build's rollout seeds the flag source with currently
live products/firms. Backfill additionally requires explicit enablement
(default-off is the desired safety property).

## AdmissionGate

```java
public final class AdmissionGate {
    public enum Verdict { ADMIT, GATED_PRODUCT, GATED_FIRM, HALTED }

    public AdmissionGate(FeatureFlags flags, FanOutMetrics metrics); // fanout.* counters
    public static AdmissionGate forBackfill(FeatureFlags flags, FanOutMetrics metrics); // backfill.* counters

    public Verdict admit(String productId, String firmId);
}
```

Precedence (normative): `haltSwitch()` ⇒ `HALTED`; else `!enabled(productId)`
⇒ `GATED_PRODUCT`; else `!enabledFirm(firmId)` ⇒ `GATED_FIRM`; else `ADMIT`.
Every non-ADMIT verdict increments
`{fanout|backfill}.admit.reject.{halt,product,firm}` (tags: product_id,
firm_id — identifiers only). Verdicts are pure observations of live flag
state; the gate stores nothing.

## Fan-out integration (FairShardScheduler + FanOutEngine)

- `FairShardScheduler` gains an optional admission predicate
  `Predicate<FanOutTask>` (existing two-arg constructor delegates with
  `task -> true`; all delivered tests unchanged).
- `tryPick`: a firm whose pending **head** chunk is non-ADMIT is skipped for
  the scan — identical treatment to a firm at its in-flight cap (skipped,
  never queued ahead; fairness machinery untouched).
- Termination: when nothing is in flight and no pending head is ADMIT,
  `runAll` returns `SchedulerResult.AWAITING_ADMISSION` (new overload;
  the delivered void overload returns only on full completion as before).
- `FanOutEngine` maps results: all chunks resolved ⇒ `COMPLETED` (or the
  delivered empty/duplicate statuses); pending + `haltSwitch()` ⇒ outcome
  **`HALTED`**; pending + gates ⇒ outcome **`GATED`** (additive
  `FanOutOutcomeStatus` values). Cursor state is untouched at that point —
  the delivered contiguous-prefix advance already persisted the completed
  prefix.
- Chunk-level mid-chunk behavior: gates are admission-time only; an admitted
  in-flight chunk completes (index-only, sub-second). Spec FR-041 "stop
  remaining admission" is satisfied: nothing new is admitted after the flag
  is observed.

### Resume (FR-042/FR-043)

`HALTED` and `GATED` runs leave everything needed in the `FANOUT_CURSOR`.
Re-invoking `runPublish` / `runWithdrawSupersede` with the same trigger takes
the delivered duplicate-claim path (`claimRun` returns false → read cursor →
resume at `lastCompletedChunkIndex + 1`). Gated-off work is never buffered,
never dead-lettered, and never dropped — it exists only as the gap between
the cursor and the eligibility stream, re-derived on demand.

## Backfill integration (BackfillWorker / BackfillScheduler)

- Batch admission consults `AdmissionGate.forBackfill(...)` per firm:
  `HALTED` ⇒ the run stops admitting anywhere (outcome `HALTED`);
  `GATED_*` ⇒ the firm is skipped this rotation (other firms continue).
- Engagement-level admission inside a batch re-checks the gate before each
  permit acquisition (a flag flip mid-batch stops the remaining engagements
  of in-flight batches at the next engagement boundary; the partial batch
  still resolves and checkpoints — data-model.md engagement state machine).
- The permit-wait loop consults `haltSwitch` via the `PermitGuardedDownstream`
  probe (capacity-shares.md).

## Halt observation latency (FR-030/FR-034)

Halt is read live at every admission decision — scheduler pick, batch pick,
engagement pick, permit-wait iteration. No polling thread exists. The slowest
observation point is a blocked permit waiter, re-checked once per
`BackoffWaiter` pause (default 100 ms jittered) — inside the 1 s
`haltPollInterval` bound. Tests assert zero new admissions after the flag at
scheduler-clock granularity plus a bounded wall-clock drain.

## Invariants

1. `HALTED` dominates `GATED_*` dominates `ADMIT` — deterministic, tested.
2. Rejection counters increment once per rejected admission attempt; tags
   carry identifiers only.
3. A `GATED` or `HALTED` outcome never coincides with lost rows: cursor
   prefix + idempotent writes are the complete truth.
4. No deploy is ever required to change any flag; all reads are live.
