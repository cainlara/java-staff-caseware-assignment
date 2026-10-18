# Contract: CheckpointStore

**Port**: `com.example.fanout.ports.CheckpointStore`

Reads/writes external progress so work survives process restart.

## Signature (conceptual)

```
Optional<String> read(CheckpointKey key)
void write(CheckpointKey key, String value)
```

`CheckpointKey = (kind: PUBLISH | BACKFILL_BATCH, id: non-blank String)`

## Idempotency key

```
(kind, id)
```

- Progress for publish `X` and backfill batch `X` never collide because `kind`
  differs.
- `write` is last-write-wins on the same key (progress is a cursor/offset, not
  a multi-writer structure). Concurrent writers to the same key are a caller
  bug; the in-memory impl is thread-safe but does not merge values.

## Durability expectation

- Production semantics: values MUST survive process restart (store is external
  to process memory).
- This phase ships an **in-memory implementation** (spec: "interface only,
  in-memory impl for now"). It intentionally does NOT survive JVM exit; the
  contract test pins interface semantics by sharing the backing map across a
  "restarted" store instance to demonstrate the durability boundary.

## Missing keys

- `read` of a never-written key returns `Optional.empty()` — distinguishable
  from a stored empty-string value (`Optional.of("")`). Implementations MUST
  NOT conflate the two.

## Errors

- Null key or blank id rejected. Null value on `write` rejected.

## Contract test vectors

1. Write then read same key -> `Optional.of(value)`.
2. Read never-written key -> `Optional.empty()`.
3. Write publish key then read backfill key with same id -> `Optional.empty()`.
4. "Restart": new store instance over the same underlying storage -> previously
   written value returned unchanged.
5. Overwrite same key -> latest value wins.
