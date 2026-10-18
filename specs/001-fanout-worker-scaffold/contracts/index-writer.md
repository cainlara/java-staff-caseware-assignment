# Contract: IndexWriter

**Port**: `com.example.fanout.ports.IndexWriter`

Writes pending-index metadata rows. The only writer allowed on the publish hot
path.

## Signature (conceptual)

```
WriteResult upsert(PendingIndexRow row)
```

`WriteResult ∈ { WRITTEN, SKIPPED_DUPLICATE, REJECTED_STALE }`

## Idempotency key

```
(engagementId, offeredVersion, state)
```

- Two rows with the same key are the **same logical row**; re-writing the same
  effective content is a no-op (`SKIPPED_DUPLICATE`), never a duplicate effect.
- `publishIds` participates in "same effective content": a second write that
  only adds publish ids to an existing row is an update, not a duplicate.

## Compare-and-set (CAS) on row_version

- `rowVersion` is a monotonic long, >= 1.
- On insert: writer stores the row with its `rowVersion` (first insert = 1).
- On update: caller MUST have read the current row and presents that
  `rowVersion` in the incoming row. A CAS success requires presenting exactly
  the stored `rowVersion`; if the stored `rowVersion` differs (another writer
  already advanced it, or the presented version is otherwise not current), the
  write MUST be rejected with `REJECTED_STALE` — never merged blindly.
- On `SKIPPED_DUPLICATE` and `REJECTED_STALE`, stored state MUST be unchanged.

## Hot path

- This is the hot-path port: writes touch metadata rows only. Engagement
  content MUST NOT be written, read, or accepted in any field.
- The publish hot path MUST NOT call `DownstreamLoadClient` before or during
  an `upsert`.

## Errors

- Null row or construction-invalid rows cannot exist (domain validation).
- `REJECTED_STALE` is a value, not an exception — expected flow under
  concurrent writers.

## Contract test vectors

1. **Duplicate write**: same row content twice -> first `WRITTEN`, second
   `SKIPPED_DUPLICATE`; stored state identical to single-write state.
2. **Stale CAS**: write row_version=1, concurrently advance to 2 via a valid
   update, then attempt update presenting row_version=1 -> `REJECTED_STALE`;
   stored row still at version 2.
3. **Idempotency key match**: rows differing only in non-key fields but same
   (engagementId, offeredVersion, state) target the same logical row.
4. **Publish-ids merge**: second write with same key + additional publish id
   and correct row_version -> `WRITTEN`, publishIds unioned per design of the
   update (documented per-update semantics in implementation).
5. Rejected/stale writes leave stored bytes unchanged.
