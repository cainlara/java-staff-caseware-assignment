# Phase 1 Data Model: Fan-Out Worker Scaffold & Contracts

**Feature**: 001-fanout-worker-scaffold | **Date**: 2026-10-18

All entities are immutable value objects (Java records). Validation runs at
construction in compact constructors. Naming mirrors the spec; field lists are
authoritative for implementation.

## Entity: TemplatePublished

Event: a product team published a template version.

| Field | Type | Key? | Validation |
|-------|------|------|------------|
| publishId | String | natural key | mandatory, non-blank |
| productId | String | natural key | mandatory, non-blank |
| fromVersion | String | | mandatory, non-blank |
| toVersion | String | | mandatory, non-blank |
| publishedAt | Instant | | mandatory, non-null |

- Idempotency: consumers key on `publishId` (+ `productId` for scoping).
- Rejected: null or blank on any String field; null `publishedAt`.

## Entity: TemplateWithdrawn

Event: a published template version was retracted.

| Field | Type | Key? | Validation |
|-------|------|------|------------|
| withdrawId | String | natural key | mandatory, non-blank |
| productId | String | natural key | mandatory, non-blank |
| version | String | | mandatory, non-blank |
| withdrawnAt | Instant | | mandatory, non-null |

- Idempotency: consumers key on `withdrawId` (+ `productId`).

## Entity: EngagementRef

Lightweight handle to one engagement file — the only engagement-related payload
permitted off the hot path. Never carries engagement content.

| Field | Type | Key? | Validation |
|-------|------|------|------------|
| engagementId | String | natural key | mandatory, non-blank |
| firmId | String | natural key | mandatory, non-blank |
| productId | String | | mandatory, non-blank |
| currentVersion | String | | mandatory, non-blank |
| region | String | | mandatory, non-blank (opaque id; residency enforcement later) |

## Enum: PendingIndexState

```
PENDING | APPLIED | DECLINED | CREATED
```

## Entity: PendingIndexRow

One metadata row in the Pending Update Index. Metadata identifiers only — the
type must not represent engagement content.

| Field | Type | Key? | Validation |
|-------|------|------|------------|
| engagementId | String | part of idempotency key | mandatory, non-blank |
| firmId | String | | mandatory, non-blank |
| productId | String | | mandatory, non-blank |
| currentVersion | String | | mandatory, non-blank |
| offeredVersion | String | part of idempotency key | mandatory, non-blank |
| state | PendingIndexState | part of idempotency key | mandatory, non-null |
| decision | String | | nullable (absent until decided) |
| pinnedRanges | List<String> | | mandatory, non-null; each element non-blank; defensively copied, unmodifiable |
| summaryKey | String | | nullable (absent until summary exists) |
| publishIds | Set<String> | | mandatory, non-null, non-empty; each element non-blank; defensively copied, unmodifiable |
| rowVersion | long | CAS token | >= 1 |
| updatedAt | Instant | | mandatory, non-null |

- **Idempotency key**: `(engagementId, offeredVersion, state)`.
- **CAS**: `rowVersion` — writers must present the expected current version;
  stale versions are rejected (see contracts/index-writer.md).
- Relationships: one row per (engagement, offered version, state); collects the
  set of publish ids that contributed to the offer.

## Entity: FanOutTask

Schedulable chunk of one publish's fan-out work (chunking behavior itself is a
later phase).

| Field | Type | Key? | Validation |
|-------|------|------|------------|
| publishId | String | | mandatory, non-blank |
| batchId | String | batch identity | mandatory, non-blank |
| batchIndex | int | batch identity | >= 0 |
| batchSize | int | batch identity | >= 1 |

- Batch identity: `(publishId, batchId, batchIndex)` identifies one chunk;
  `batchSize` documents the chunk's intended size.

## Entity: PublishOutcome

Report of what one publish achieved (coverage measurement / alerting input).

| Field | Type | Validation |
|-------|------|------------|
| publishId | String | mandatory, non-blank |
| rowsWritten | long | >= 0 |
| rowsSkipped | long | >= 0 |
| failures | long | >= 0 |
| status | enum `PublishStatus { IN_PROGRESS, COMPLETED, PARTIAL, FAILED }` | mandatory, non-null |

## Supporting types

### CheckpointKey

| Field | Type | Validation |
|-------|------|------------|
| kind | enum `CheckpointKind { PUBLISH, BACKFILL_BATCH }` | mandatory |
| id | String | mandatory, non-blank |

- Natural key: `(kind, id)`. Progress for a publish and for a backfill batch
  never collide.

### WriteResult (returned by IndexWriter)

```
WRITTEN | SKIPPED_DUPLICATE | REJECTED_STALE
```

- `WRITTEN`: row stored (insert or CAS update).
- `SKIPPED_DUPLICATE`: identical effective state already present for the
  idempotency key — no-op, success.
- `REJECTED_STALE`: incoming row_version not current — caller must re-read.

## Relationships (summary)

```
TemplatePublished ──(fan-out, later phases)──> FanOutTask ──(produces)──> PendingIndexRow
TemplateWithdrawn ──(affects, later phases)──> PendingIndexRow.state
EngagementRef ──(off-hot-path handle)──> DownstreamLoadClient
PendingIndexRow.publishIds ──(many-to-one)──> TemplatePublished.publishId
PublishOutcome.publishId ──(one)──> TemplatePublished.publishId
CheckpointKey(kind=PUBLISH).id ──<──> publishId
```

## State transitions (documented now; logic later)

```
CREATED ──publish offered──> PENDING
PENDING ──user/firm accepts──> APPLIED
PENDING ──user/firm rejects──> DECLINED (pins decision + pinnedRanges)
```

- Transitions are recorded by future phases via IndexWriter CAS updates; the
  scaffold only defines the states and the safe-update mechanism.
- Withdrawal handling (what happens to PENDING rows for a withdrawn version)
  is intentionally undefined this phase — later design must specify it and
  keep CAS idempotency.
