# Contract: IndexWriter (v2 — additive `find`)

**Component**: `com.example.fanout.ports.IndexWriter` (phase-1 port, extended)

Phase-1 contract (idempotency key `(engagementId, offeredVersion, state)`,
`rowVersion` compare-and-set, hot-path metadata-only, no downstream calls)
stands unchanged. This phase adds ONE read method so the engine can implement
the spec R4/FR-021 re-read-and-re-apply cycle on `REJECTED_STALE`.

## Added signature

```
Optional<PendingIndexRow> find(engagementId: non-blank String,
                               offeredVersion: non-blank String,
                               state: PendingIndexState)
```

## Semantics (normative)

- Returns the currently stored row for the idempotency triple, or empty if
  absent. Read-only; never writes; never loads engagement content.
- Read-your-writes for a caller that just observed `WRITTEN` on the same
  thread/store instance (satisfied trivially by the in-memory fake; a real
  adapter must provide read-your-writes for its own writes).
- Null/blank arguments rejected.

## Engine write cycle (spec R4, FR-021 — normative)

```
upsert(intended with expected rowVersion)
  WRITTEN | SKIPPED_DUPLICATE  -> success
  REJECTED_STALE               -> current = find(triple)
                                  intended' = deterministicMerge(current, intended)  // data-model.md
                                  upsert(intended' with current.rowVersion + 1)
                                    WRITTEN | SKIPPED_DUPLICATE -> success
                                    REJECTED_STALE              -> transient failure
                                                                  (retry -> DLQ on exhaustion)
```

`fanout.cas.reapply` increments once per re-apply cycle. Merge rules
(publishIds union; decision history preserved; updatedAt = later writer) are
defined in data-model.md — final content is a deterministic function of the
merged inputs regardless of writer interleaving (spec FR-021).

## Hot path (unchanged)

Writes touch metadata rows only; the publish hot path MUST NOT call
`DownstreamLoadClient` before or during an `upsert` or a `find`.

## Implementation note

`InMemoryIndexWriter` (test fake) implements `find` as a `ConcurrentHashMap`
lookup on the existing triple key. No production adapter exists yet; the
method is additive (Constitution VII — expand, never break).

## Contract test vectors (additive to phase-1 vectors)

1. `find` after `WRITTEN` returns the stored row (read-your-writes).
2. `find` on absent triple returns empty.
3. `find` does not mutate stored state or rowVersion.
4. Stale upsert -> `REJECTED_STALE` -> `find` returns current ->
   merge -> upsert with fresh version -> `WRITTEN`; stored row reflects the
   merge (publishIds union, preserved decision fields).
5. Two concurrent writers on one triple: one wins each version step; the
   loser's re-apply converges to the same final content (deterministic
   interleaving test).
6. Every test: zero downstream calls; no engagement content accepted in any
   field.
