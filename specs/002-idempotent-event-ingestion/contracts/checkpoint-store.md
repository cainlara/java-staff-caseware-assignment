# Contract: CheckpointStore (v2 — additive extension)

**Port**: `com.example.fanout.ports.CheckpointStore`

Phase-1 contract unchanged (see `specs/001-fanout-worker-scaffold/contracts/checkpoint-store.md`:
read/write, `(kind, id)` key, last-write-wins write, missing-key
`Optional.empty()`, durability expectation). This v2 adds one method and
documents the new key domains used by ingest.

## Additive method (v2)

```
default boolean writeIfAbsent(CheckpointKey key, String value)
```

- Returns `true` if the value was written (key was absent); `false` if a value
  already existed (left unchanged).
- **Default implementation**: non-atomic `read`-then-`write` — preserves any
  existing implementation without forced recompilation semantics (Constitution
  VII: backward compatible).
- **Required override for correctness**: implementations that back ingest
  (starting with `InMemoryCheckpointStore` via
  `ConcurrentHashMap.putIfAbsent`) MUST provide an **atomic** claim:
  concurrent callers for the same absent key must have exactly one observe
  `true`. A future database adapter maps this to `INSERT` (unique constraint
  on `(kind, id)`).
- Used by `IngestService` as the duplicate-claim primitive (research.md D2):
  the written value IS the processed record, so claim and record are one
  atomic step (FR-021/FR-022 crash windows).

## New key domains (internal to ingest encoding)

`CheckpointKind` extended additively:

| Kind | Key `id` | Value | Writer |
|------|----------|-------|--------|
| `PUBLISH` (phase 1, new use) | publishId | `productId US toVersion US publishedAtEpochMilli` | IngestService claim (writeIfAbsent) |
| `WITHDRAW` | withdrawId | `productId US version US status US matchedPublishId-or-empty US withdrawnAtEpochMilli` | IngestService claim; status later overwritten on resolve (write, LWW) |
| `PRODUCT_INGEST` | productId | decimal long sequence | IngestService (read-modify-write; observational) |
| `PENDING_WITHDRAW` | `productId US version` | LF-joined withdraw ids | IngestService defer/resolve |
| `PRODUCT_PUBLISHES` | productId | LF-joined publish ids | IngestService append (idempotent) |

`US` = ASCII unit separator (U+001F); `LF` = newline. Encoding/decoding lives
exclusively in `IngestProgressRepository` (data-model.md). Natural keys are
assumed delimiter-free (UUID-like) — disclosed in research.md.

## Semantics preserved from phase 1

- Missing key -> `Optional.empty()` (distinguishable from stored empty string).
- Null key / blank id / null value on write -> rejected.
- Thread-safe; concurrent writers to the same key via `write` are a caller bug
  (LWW). Concurrent claims via `writeIfAbsent` are safe and required.

## Contract test vectors (v2 additions)

1. `writeIfAbsent` on absent key -> `true`; read returns value.
2. `writeIfAbsent` same key again (any value) -> `false`; stored value
   unchanged (first claim wins).
3. Concurrent `writeIfAbsent` hammer on one absent key -> exactly one `true`
   across all callers; final value equals the winning write.
4. Default-method compatibility: an anonymous `CheckpointStore` that
   implements only `read`/`write` still compiles and `writeIfAbsent` returns
   correct (non-atomic) results sequentially.
5. Phase-1 vectors (write/read, missing key, kind isolation, restart-over-
   shared-map) still pass unchanged.
