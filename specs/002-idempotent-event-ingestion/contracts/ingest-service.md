# Contract: IngestService

**Component**: `com.example.fanout.ingest.IngestService`

Receives template lifecycle events, records ingest progress in the external
checkpoint store, and emits internal fan-out triggers. No fan-out work, no
engagement loads.

## Signature (conceptual)

```
IngestOutcome ingest(TemplatePublished event)
IngestOutcome ingest(TemplateWithdrawn event)

IngestOutcome = (status: INGESTED | DUPLICATE | DEFERRED | REJECTED,
                 eventId: non-blank String,
                 resolvedWithdrawIds: List<String>)
```

Constructor-injected: `IngestProgressRepository`, `FanOutTriggerSink`,
`FanOutMetrics`. Throws only on null arguments (defensive REJECTED-equivalent:
no checkpoint, no trigger, error surfaced — FR-004).

## Processing order (normative, FR-001)

For a `TemplatePublished`:

1. Validate argument non-null (field validation already done at record
   construction, phase 1).
2. `claimPublish` via `writeIfAbsent` on `CheckpointKey(PUBLISH, publishId)`
   — value carries productId, toVersion, publishedAt (data-model.md schema).
   - **Claim failed (key exists)**: increment `ingest.duplicate`
     (tag `product_id`), return `(DUPLICATE, publishId, [])`. No further
     writes, no trigger, no error (FR-003).
3. Assign next per-product ingest sequence (`PRODUCT_INGEST` read-modify-write).
4. Append publishId to `PRODUCT_PUBLISHES` for the product (ingest order, R4).
5. Resolve deferred withdraws: read `PENDING_WITHDRAW (product, toVersion)`;
   for each listed withdraw id whose `WITHDRAW` record is still DEFERRED,
   `resolveWithdraw(id, publishId)` (claim semantics: skip if already RESOLVED)
   and collect it; clear the pending list afterwards (FR-011, R3).
6. Emit one `FanOutTrigger(kind=PUBLISH, eventId=publishId, productId,
   ingestSequence, matchedPublishId=null)` (R5).
7. For each resolved withdraw id, emit one
   `FanOutTrigger(kind=WITHDRAW, eventId=withdrawId, productId,
   ingestSequence, matchedPublishId=publishId)`.
8. Increment `ingest.published` and, if any resolved, `ingest.resolved`.
   Return `(INGESTED, publishId, resolvedWithdrawIds)`.

For a `TemplateWithdrawn`:

1. Validate argument non-null.
2. `claimWithdraw` via `writeIfAbsent` on `CheckpointKey(WITHDRAW, withdrawId)`
   — initial status DEFERRED in the value (claim is the record itself).
   - **Claim failed**: increment `ingest.duplicate`, return
     `(DUPLICATE, withdrawId, [])` (FR-003).
3. Determine whether an ingested publish for `(productId, version)` exists
   (load `PRODUCT_PUBLISHES`, filter `toVersion == version`, pick
   max-`(publishedAt, publishId)` per R3).
   - **Match found**: `resolveWithdraw(withdrawId, matchedPublishId)`;
     increment `ingest.withdrawn` and `ingest.resolved`; emit
     `FanOutTrigger(kind=WITHDRAW, ..., matchedPublishId)`; return
     `(INGESTED, withdrawId, [])`.
   - **No match**: append withdrawId to `PENDING_WITHDRAW (productId, version)`
     if not already listed; increment `ingest.withdrawn` and `ingest.deferred`;
     emit no trigger; return `(DEFERRED, withdrawId, [])` (FR-010).

## Idempotency keys

- Publish: `(PUBLISH, publishId)` claim — first writer wins, later deliveries
  are duplicates (R1).
- Withdraw: `(WITHDRAW, withdrawId)` claim — same (R2).
- Trigger consumers must additionally be idempotent on
  `(kind, eventId, productId)` (R5, FanOutTriggerSink contract).

## Durability / crash windows (R5, FR-021/FR-022/FR-024)

- The claim write IS the processed record; it is written before any trigger
  emission (checkpoint-then-trigger, spec-mandated order).
- Kill after claim, before trigger: redelivery -> DUPLICATE, no re-ingest
  (FR-021). Claim value carries identity + ingest metadata so a later
  reconciler can detect the gap (FR-024).
- Kill before claim: redelivery -> processed exactly once (FR-022).
- Kill mid-withdraw-resolution (after publish claim, before pending-list
  clear): residual `PENDING_WITHDRAW` entries persist for a future repair pass
  (research.md disclosure); happy-path reordering is fully covered by tests.

## Hot path invariants

- Zero calls to `DownstreamLoadClient` — enforced by having no such dependency
  and asserted by every ingest test (FR-030/FR-041).
- No engagement content in outcomes, triggers, checkpoints, or metric
  arguments (FR-031).
- Correctness never depends on metric values (FR-032).

## Metric emissions (identifier-only names/tags)

| Counter | When |
|---------|------|
| `ingest.published` | publish newly ingested |
| `ingest.withdrawn` | withdraw newly claimed (deferred or resolved) |
| `ingest.duplicate` | duplicate path taken (mandatory, FR-003) |
| `ingest.deferred` | withdraw deferred |
| `ingest.resolved` | withdraw resolved (by publish ingest or immediate match) |
| `ingest.rejected` | null/invalid argument rejected |

## Contract test vectors

1. Fresh publish -> INGESTED, one PUBLISH trigger, one checkpoint record, one
   PRODUCT_PUBLISHES append, sequence assigned.
2. Duplicate publish -> DUPLICATE, zero new triggers, `ingest.duplicate`
   incremented, checkpoint unchanged, PRODUCT_PUBLISHES unchanged.
3. Fresh withdraw, no matching publish -> DEFERRED, zero triggers, WITHDRAW
   record DEFERRED, PENDING_WITHDRAW lists the id.
4. Then ingest matching publish -> publish INGESTED with resolvedWithdrawIds
   containing the withdraw; WITHDRAW record now RESOLVED with matched
   publish id; exactly one WITHDRAW trigger; PENDING_WITHDRAW cleared.
5. Withdraw after its publish -> INGESTED immediately, one WITHDRAW trigger,
   matched publish id present.
6. Duplicate withdraw (after deferral or resolution) -> DUPLICATE, zero new
   triggers, no state change.
7. Two publishes same product, either order -> both INGESTED, both triggers,
   both ids retained, sequences reflect arrival order, effective version per
   R4 identical across orders.
8. Kill-before-claim (failing store) -> error surfaced, no trigger, no
   durable record; redelivery on healthy store -> INGESTED once.
9. Kill-after-claim (failing sink) -> error surfaced; redelivery -> DUPLICATE,
   zero additional triggers.
10. Two service instances, one store, same publish to each -> first INGESTED,
    second DUPLICATE, exactly one trigger total.
11. Permutation x duplication of a publish batch -> identical functional
    checkpoint state (processed sets, effective versions, withdraw
    resolutions).
12. Null argument to either overload -> REJECTED-equivalent throw; no
    checkpoint, no trigger.
13. Every test above: `FakeDownstreamLoadClient.getCallCount() == 0`.
