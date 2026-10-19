# Contract: EngagementEnumerator Port

**Phase**: 4 (new port) · **Spec**: B1, FR-001–FR-003 · **Research**: D1

## Purpose

Enumerate every engagement of one customer database in stable, resumable,
id-ordered batches — the backfill path's only source of work. Identifier-only:
this port never returns engagement content or template versions.

## Interface

```java
package com.example.fanout.ports;

public interface EngagementEnumerator {

    /**
     * Next batch of engagements for one customer database, strictly above the
     * cursor, ordered by engagementId ascending.
     *
     * @param customerDatabaseId non-blank database identity (= firm id, 1:1)
     * @param afterEngagementId  exclusive lower bound; empty = start from the beginning
     * @param limit              max items, >= 1
     * @return a batch; never null; never an empty item list
     */
    EnumerationBatch nextBatch(String customerDatabaseId,
                               Optional<String> afterEngagementId,
                               int limit);
}
```

Element and batch records live in `com.example.fanout.domain`
(`EnumeratedEngagement`, `EnumerationBatch`) per data-model.md.

## Invariants (contract tests pin these)

1. **Order**: `items` are strictly ascending by `engagementId`; no duplicates
   within a batch.
2. **Stability**: absent concurrent engagement creation, the same
   `(customerDatabaseId, afterEngagementId, limit)` returns the same items.
3. **Resumability**: calling with `nextCursor` of batch *i* returns the
   immediately following engagements — the concatenation of all batches from
   the empty cursor is the database's full engagement set in id order, each
   engagement exactly once.
4. **Cursor**: `nextCursor`, when present, equals the last item's
   `engagementId`; `done == true` implies empty items and empty cursor.
5. **Size**: `items.size() <= limit`.
6. **Content prohibition**: payloads contain only non-blank identifiers; no
   version, region, or content fields exist on the types.
7. **Errors**: null/blank database id or `limit < 1` are rejected with
   `IllegalArgumentException`; downstream enumeration failures surface as
   unchecked exceptions (the worker retries them per the retry policy).

## Fake: SeededEngagementEnumerator (test scope)

`com.example.fanout.fakes.SeededEngagementEnumerator`

- Deterministic dataset: `seed`, firms-per-seed, engagements-per-firm (or an
  explicit injected dataset) → same ids for the same seed.
- Failure injection: `failNextCalls(n)`, `failAlways()`, `failAfterBatches(k)`
  — each failure is an unchecked exception after the call is counted.
- Restartable: honors any cursor, including one from a partially consumed run.
- Call log: records `(databaseId, afterCursor, limit)` per call for
  assertions.

## Non-goals

- No filtering by product, version, or eligibility (that is the eligibility
  source's job on the fan-out path).
- No ordering guarantees beyond engagement-id ascending.
- No real database adapter in this phase.
