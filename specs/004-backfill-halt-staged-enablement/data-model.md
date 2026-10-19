# Phase 1 Data Model: Backfill, Halt, and Staged Enablement

Entities, fields, validation, and state transitions for the phase-4 feature.
Delivered records from phases 1–3 are referenced with their invariants
restated where this feature depends on them; nothing here changes a delivered
schema (all changes are additive).

---

## New domain records

### EnumeratedEngagement (new)

Enumerator batch element — identifiers only. Never carries engagement content
or a version (the version is unknown until the downstream load, D1).

| Field | Type | Validation |
|-------|------|-----------|
| engagementId | String | non-blank |
| firmId | String | non-blank |
| productId | String | non-blank |

### EnumerationBatch (new)

One stable, resumable enumeration slice (spec B1).

| Field | Type | Validation / invariant |
|-------|------|------------------------|
| items | List\<EnumeratedEngagement\> | size ≥ 1 (never empty — a done batch carries no items); ordered by engagementId ascending; all items share the requested customer database via firmId |
| nextCursor | Optional\<String\> | empty iff `done`; else = last item's engagementId (exclusive lower bound for the next call) |
| done | boolean | `true` ⇒ enumeration complete for the database; `nextCursor` empty |

Cross-field: `done == false` ⇒ `items.size() <= limit` and `nextCursor`
present. `done == true` ⇒ `items` empty.

### BackfillConfig (new)

All backfill knobs in one validation-friendly record (mirrors the delivered
`FanOutConfig` pattern).

| Field | Default | Validation |
|-------|---------|------------|
| batchSize | 100 | ≥ 1 |
| firmInFlightCap | 1 | ≥ 1 (concurrent batches per firm; D7/D11) |
| parentC | 20 | ≥ 1 |
| bBackfill | 18 | ≥ 1, `bBackfill + bRepair ≤ parentC` (FR-021) |
| bRepair | 2 | ≥ 0, sum rule as above |
| haltPollInterval | 1 s | > 0 |
| haltDrainBound | 2 min | > 0 |
| retryPolicy | RetryPolicy(5, 100 ms, 30 s, 0.5, 1.5) | non-null; policy fields validated by `RetryPolicy` |

### CapacityShares (new)

| Field | Type | Validation |
|-------|------|-----------|
| parentC | int | ≥ 1 |
| backfill | int | ≥ 1 |
| repair | int | ≥ 0 |
| — | — | compact constructor: `backfill + repair ≤ parentC` |

Derived: two independent `CapacityBudget` instances are constructed from
`backfill` and `repair`; no shared wait queue exists (D5). Invariant (tested
at every observation point, SC-005): `inFlightBackfill ≤ backfill`,
`inFlightRepair ≤ repair`, `total ≤ parentC`.

### BackfillOutcome (new)

| Field | Type |
|-------|------|
| status | BackfillStatus — `COMPLETED`, `HALTED`, `FAILED_DEAD_LETTERED` |
| firmsRemaining | int |
| engagementsResolved | int (written + no-op + dead-lettered) |
| rowsWritten | int |
| noOps | int |
| deadLettered | int |

### RetryPolicy (new, fanout package)

Extracted retry knobs shared by both paths (D10): `maxAttempts` (≥ 1),
`baseDelay` (> 0), `maxDelay` (≥ baseDelay), `jitterFactorMin` (≥ 0),
`jitterFactorMax` (> jitterFactorMin). `FanOutConfig` keeps its components and
its existing `RetryExecutor` constructor delegates to a policy view.

### AdmissionGate (new, fanout package)

Not a data entity — a seam record for auditability:

| Field | Type |
|-------|------|
| verdict | `ADMIT` \| `GATED_PRODUCT` \| `GATED_FIRM` \| `HALTED` |

Verdict precedence (normative, spec B6/B9): `haltSwitch()` true ⇒ `HALTED`
(dominates both flags); else product flag false ⇒ `GATED_PRODUCT`; else firm
flag false ⇒ `GATED_FIRM`; else `ADMIT`. Every non-ADMIT verdict increments
the corresponding rejection counter (identifiers only).

---

## Modified delivered entities (additive)

### DeadLetterUnitKind (enum, additive)

Existing: `CHUNK_WRITE`, `ELIGIBILITY_STREAM`, `DOWNSTREAM_CALL`,
`CURSOR_WRITE`, `SUPERSEDE_PLAN`.
**Added**: `BACKFILL_ENGAGEMENT` (one engagement's load/compare/write unit),
`BACKFILL_BATCH` (one enumeration fetch), `BACKFILL_CURSOR` (cursor advance
failure).

### DeadLetterRecord (unchanged shape, new identity encoding)

Backfill units reuse the delivered record; identity is encoded in existing
fields (D4, contracts/backfill-dlq-identity.md):

| Field | BACKFILL_ENGAGEMENT value |
|-------|---------------------------|
| eventId | `bf US firmId US batchIndex US engagementId` |
| productId | product id |
| chunkIndex | batch index (≥ 0) |
| unitKind | `BACKFILL_ENGAGEMENT` |
| attemptCount | attempts consumed (≥ 1) |
| lastFailureType / lastFailureMessage | failure identity (identifier-only) |
| recordedAt | clock instant |

`BACKFILL_BATCH` omits the trailing engagement segment; `BACKFILL_CURSOR`
uses `chunkIndex = -1` with no engagement segment. Codec:
`BackfillDeadLetterIdentity` (encode + decode, contract-tested).

### FanOutOutcomeStatus (enum, additive)

Existing: `COMPLETED`, `COMPLETED_EMPTY`, `FAILED_DEAD_LETTERED`,
`SKIPPED_IN_PROGRESS`, `SKIPPED_DUPLICATE`.
**Added**: `HALTED` (halt observed with chunks still pending; cursor holds
the completed prefix), `GATED` (all remaining work blocked by product/firm
flags; cursor holds the completed prefix). Both are re-invokable: calling
`runPublish`/`runWithdrawSupersede` again with the same trigger resumes from
the cursor via the delivered claim/resume path (FR-042).

### FeatureFlags (unchanged port)

Delivered contract stands (unknown ids ⇒ false, opt-in; halt dominates) —
reconciliation D6: staged enablement is opt-in for fan-out AND backfill; the
rollout seeds live products/firms explicitly.

### CapacityBudget (unchanged port)

Two instances now exist in a running system (backfill share, repair share);
the port contract (counted permits, exactly-once release, release-without-
acquire throws) is unchanged and enforced per instance.

### PendingIndexRow (unchanged shape; backfill write recipe)

A backfilled row is a normal PENDING row with:

| Field | Value |
|-------|-------|
| engagementId / firmId / productId | from `EnumeratedEngagement` |
| currentVersion | captured version string from the downstream load |
| offeredVersion | effective to-version (D3) |
| state | `PENDING` |
| decision / pinnedRanges / summaryKey | null / [] / null (no user decisions yet) |
| publishIds | `{effective publish id}` |
| rowVersion | 1 (new row; CAS path applies if the row exists) |
| updatedAt | clock instant |

No-op rule: captured version equals effective to-version, or product has no
effective version ⇒ zero writes (FR-006/FR-007).

---

## Backfill cursor (checkpoint schema)

`CheckpointKind.BACKFILL_BATCH` (reserved in phase 1), one key per firm.

| Aspect | Value |
|--------|-------|
| key | `CheckpointKey(BACKFILL_BATCH, firmId)` |
| value | `status US lastResolvedEngagementId` |
| status | `RUNNING` \| `COMPLETE` |
| missing key | start of enumeration (exclusive lower bound = −∞) |
| advance | rewritten only when a whole batch has resolved (written / no-op / dead-lettered per engagement) |
| completion | `COMPLETE US <final engagement id>` when the enumerator reports done AND the final batch resolved |
| corrupt value | `IllegalStateException` from the codec ⇒ worker dead-letters (`BACKFILL_CURSOR`) and alerts; never silent restart (spec edge case) |

### BackfillCursor state machine (per firm)

```text
(ABSENT) --first batch admitted--> RUNNING(c0)
RUNNING(ci) --batch i+1 fully resolved--> RUNNING(ci+1)
RUNNING(cn) --enumerator done + final batch resolved--> COMPLETE(cn)
any RUNNING --halt--> RUNNING(ci)        [persisted as-is; resume continues]
any RUNNING --crash mid-batch--> RUNNING(ci)  [partial batch lost; ≤1 batch re-run]
any state --corrupt decode--> FAILED (dead-letter + alert; no writes)
```

`lastResolvedEngagementId` is an **exclusive lower bound**: resume re-fetches
from `afterEngagementId = lastResolvedEngagementId`. A batch's engagements all
sit strictly above it, so no engagement is skipped (gap-free) and at most the
one in-flight batch per firm is re-fetched (redundant loads absorbed by
idempotent writes — zero duplicate rows).

---

## Engagement unit state machine (per engagement, in-process only)

State is not persisted; only the batch cursor is external. Persisted outcomes
per engagement: row created (audited by the index writer), no-op, or
dead-letter record.

```text
READY --gate/halt check--> ADMITTED --permit acquired--> LOADING
LOADING --version == effective / no effective--> NOOP (resolved)
LOADING --version older + write OK / duplicate skip / CAS converged--> WRITTEN (resolved)
LOADING --retry budget exhausted--> DEAD_LETTERED (resolved; batch still checkpoints)
LOADING --AdmissionHaltedException--> READY  [permit released; batch aborts; cursor unchanged]
any --non-transient failure (process death)--> process exits; restart re-fetches the batch
```

---

## Fan-out chunk admission (integrated with delivered scheduler)

```text
PENDING --AdmissionGate ADMIT--> ADMITTED (scheduler admits; pool slot + firm slot)
PENDING --GATED_PRODUCT / GATED_FIRM--> PENDING  [skipped this cycle; re-derived from cursor later; NOT buffered, NOT dead-lettered]
PENDING --HALTED--> PENDING          [run returns HALTED; cursor holds completed prefix]
ADMITTED --chunk resolved--> RESOLVED (delivered contiguous-prefix cursor advance)
```

Invariants preserved from phase 3: per-firm in-flight ≤ cap, total in-flight ≤
pool bound, contiguous-prefix cursor advancement, out-of-order completion
safety.

---

## Relationships

```text
EngagementEnumerator ──produces──> EnumerationBatch ──contains──> EnumeratedEngagement*
BackfillWorker ──reads/writes──> BackfillCursor (per firm, via BackfillCursorRepository → CheckpointStore)
BackfillWorker ──calls (permit-gated)──> DownstreamLoadClient ──returns──> captured version
BackfillWorker ──derives offer──> EffectiveVersion(ingested publishes − withdrawn) via FanOutCursorRepository
BackfillWorker ──writes──> PendingIndexRow (via IndexWriter)
BackfillWorker ──uses──> CapacityShares.backfill budget; future repair caller ──uses──> CapacityShares.repair budget
AdmissionGate ──reads──> FeatureFlags; ──emits──> FanOutMetrics rejection counters
FanOutEngine ──consults──> AdmissionGate at scheduler admission (additive wiring)
exhausted units ──encode identity──> DeadLetterRecord (via DeadLetterStore)
```

## Validation rules consolidated (from spec)

1. Every backfill load holds exactly one B_backfill permit for the duration of
   the downstream call; release exactly once (port contract + worker finally).
2. In-flight backfill loads ≤ B_backfill at every observation; B_backfill +
   B_repair ≤ C at startup (config rejection otherwise).
3. Checkpoint advances only at fully resolved batch boundaries; corrupt
   checkpoints fail loudly (dead-letter + alert).
4. Effective-version comparison is string equality against the derived
   effective to-version; no version arithmetic anywhere.
5. Halt dominates flags; flags dominate nothing else; gated work is neither
   dead-lettered nor buffered — it stays behind the cursor.
6. All new log lines, metric tags, dead-letter fields, and checkpoint
   payloads carry identifiers and measurements only.
