# Phase 1 Data Model: Idempotent Event Ingestion & External Checkpointing

**Feature**: 002-idempotent-event-ingestion | **Date**: 2026-10-18

New entities are immutable Java records with compact-constructor validation
(phase-1 convention). Phase-1 entities (`TemplatePublished`, `TemplateWithdrawn`)
are unchanged and only referenced. Field lists are authoritative for
implementation.

## Entity: FanOutTrigger (new)

Internal signal that one resolved event awaits fan-out. Carries identifiers and
measurements only — never engagement content.

| Field | Type | Key? | Validation |
|-------|------|------|------------|
| kind | enum `FanOutTriggerKind { PUBLISH, WITHDRAW }` | part of consumer idempotency key | mandatory, non-null |
| eventId | String | part of consumer idempotency key (with productId) | mandatory, non-blank (publish id or withdraw id) |
| productId | String | part of consumer idempotency key | mandatory, non-blank |
| ingestSequence | long | | >= 1 (per-product arrival order) |
| matchedPublishId | String | | nullable; mandatory non-blank when kind = WITHDRAW, must be null when kind = PUBLISH |

- **Consumer idempotency key**: `(kind, eventId, productId)` (R5; documented on
  `FanOutTriggerSink`).
- Rejected: null kind / blank strings / null matchedPublishId for WITHDRAW /
  non-null matchedPublishId for PUBLISH / ingestSequence < 1.

## Entity: IngestOutcome (new)

Result of one `IngestService.ingest(...)` call. Returned, never thrown, for
duplicate/deferred paths (FR-003: duplicates ack without error).

| Field | Type | Validation |
|-------|------|------------|
| status | enum `IngestStatus { INGESTED, DUPLICATE, DEFERRED, REJECTED }` | mandatory, non-null |
| eventId | String | mandatory, non-blank |
| resolvedWithdrawIds | List<String> | mandatory, non-null, possibly empty; each element non-blank; defensively copied unmodifiable |

- `INGESTED`: newly claimed event; exactly one trigger emitted (publish, or a
  withdraw that was processed immediately).
- `DUPLICATE`: event id already checkpointed; no trigger; duplicate counter
  incremented (FR-003).
- `DEFERRED`: withdraw recorded as deferred; no trigger (FR-010).
- `REJECTED`: null argument or invalid input; no checkpoint, no trigger
  (FR-004). (Phase-1 records reject invalid field values at construction, so
  REJECTED covers only the defensive null-argument path today.)
- `resolvedWithdrawIds` non-empty only on INGESTED publish outcomes that
  resolved deferred withdraws (FR-011).

## Enum: CheckpointKind (extended, additive)

```
PUBLISH | BACKFILL_BATCH | WITHDRAW | PRODUCT_INGEST | PENDING_WITHDRAW | PRODUCT_PUBLISHES
```

- Phase-1 constants unchanged (backward compatible, Constitution VII).
- New constants are internal to the ingest progress encoding (D3 in
  research.md): `WITHDRAW` — one withdraw record per withdraw id;
  `PRODUCT_INGEST` — per-product sequence counter; `PENDING_WITHDRAW` —
  resolution index keyed `productId US version`; `PRODUCT_PUBLISHES` —
  per-product ingest-ordered publish-id list.

## Checkpoint value schemas (string-encoded; US = U+001F unit separator, LF = newline)

Natural keys are assumed delimiter-free (UUID-like); encoding lives only in
`IngestProgressRepository`.

### PUBLISH (key id = publishId)

```
productId US toVersion US publishedAtEpochMilli
```

Written once, atomically, as the duplicate claim (D2). Presence of the key IS
the "processed" marker (FR-024: identity + ingest metadata sufficiency — the
product id and to-version let a reconciler detect what needed a trigger).

### WITHDRAW (key id = withdrawId)

```
productId US version US status US matchedPublishId-or-empty US withdrawnAtEpochMilli
```

- `status` is `DEFERRED` or `RESOLVED`.
- Written once, atomically, as the duplicate claim; the status field is later
  overwritten (last-write-wins) on resolution — the claim key existence, not
  the status, guards duplicates.

### PRODUCT_INGEST (key id = productId)

```
nextSequence (decimal long)
```

Read-modify-write counter; observational only (spec Assumptions; effective
version uses rule R4, not this counter).

### PENDING_WITHDRAW (key id = `productId US version`)

```
withdrawId1 LF withdrawId2 LF ...
```

Resolution index of deferred withdraws for one (product, version). Read on
matching publish ingest; entries removed as each withdraw resolves; key
cleared (removed or emptied) after all resolve. Entries persist until resolved
— enabling the carried-forward repair pass (research.md obligation 6).

### PRODUCT_PUBLISHES (key id = productId)

```
publishId1 LF publishId2 LF ...
```

Append-only list in ingest arrival order (FR-013: both retained, ingest order
recorded). All publishes retained; no pruning this phase.

## Entity: EffectiveVersion (derived, not stored)

Pure function over a product's ingested publishes:

- Load `PRODUCT_PUBLISHES` list, decode each `PUBLISH` record.
- Select the publish maximizing `(publishedAtEpochMilli, publishId)` —
  timestamp descending, publish id lexicographic ascending as tie-break.
- Result: the publish record; its `toVersion` is the product's effective
  current version (rule R4).

Withdraw matching (R3): among the same loaded publishes, filter
`toVersion == withdraw.version`; bind to the max-`(publishedAt, publishId)`
of that subset; if the subset is empty, the withdraw stays deferred.

## IngestProgressRepository operations (typed adapter)

| Operation | Kind(s) touched | Semantics |
|-----------|-----------------|-----------|
| `claimPublish(publishId, productId, toVersion, publishedAt)` | PUBLISH | `writeIfAbsent`; true = claimed, false = duplicate |
| `claimWithdraw(withdrawId, productId, version, withdrawnAt)` | WITHDRAW | `writeIfAbsent` with status DEFERRED; true = claimed, false = duplicate |
| `nextProductSequence(productId)` | PRODUCT_INGEST | read, +1, write; return assigned value |
| `addProductPublish(productId, publishId)` | PRODUCT_PUBLISHES | append if absent (idempotent append) |
| `deferWithdraw(productId, version, withdrawId)` | PENDING_WITHDRAW | append id to list if absent |
| `takePendingWithdraws(productId, version)` | PENDING_WITHDRAW | read list; clear key; return ids |
| `resolveWithdraw(withdrawId, matchedPublishId)` | WITHDRAW | overwrite status to RESOLVED + matchedPublishId |
| `productPublishes(productId)` | PRODUCT_PUBLISHES | read list |
| `publish(publishId)` / `withdraw(withdrawId)` | PUBLISH / WITHDRAW | decode record or empty |
| `pendingWithdraws(productId, version)` | PENDING_WITHDRAW | read list without clearing (observability/tests) |

## Relationships (summary)

```
TemplatePublished ──(ingested by)──> IngestService ──(emits)──> FanOutTrigger(kind=PUBLISH)
TemplateWithdrawn ──(ingested by)──> IngestService ──(emits, when resolved)──> FanOutTrigger(kind=WITHDRAW)
FanOutTrigger.eventId ──(one-to-one)──> checkpointed publish/withdraw id
PENDING_WITHDRAW entries ──(resolve against)──> matching PUBLISH (product, toVersion)
PRODUCT_PUBLISHES ──(ordered list of)──> PUBLISH ids; EffectiveVersion derived from them
IngestOutcome ──(returned by)──> IngestService.ingest(...)
```

## State transitions (this phase)

### Withdraw lifecycle

```
(none) ──ingest, publish (P,V) already present──> RESOLVED   [+ trigger emitted once]
(none) ──ingest, publish (P,V) absent──────────> DEFERRED    [+ listed in PENDING_WITHDRAW (P,V)]
DEFERRED ──matching publish ingested───────────> RESOLVED    [+ trigger emitted once; index entry removed]
```

- Duplicates at any state: DUPLICATE outcome, no transition, no trigger (R2).
- A resolved withdraw is never re-resolved (its `WITHDRAW` claim exists; the
  publish path skips already-claimed ids).

### Publish lifecycle

```
(none) ──ingest──> checkpointed (+ PRODUCT_PUBLISHES append, sequence assigned, trigger emitted once)
```

- No further publish transitions this phase (fan-out status is a later phase).
- Duplicates: DUPLICATE outcome, no append, no trigger (R1).
