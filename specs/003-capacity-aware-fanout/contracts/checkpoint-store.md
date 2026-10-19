# Contract: CheckpointStore (v2 — fan-out kinds and value schemas)

**Component**: `com.example.fanout.ports.CheckpointStore` (phase-1 port,
extended by two new `CheckpointKind` constants — additive, Constitution VII)

Phase-1 semantics stand unchanged: string values, `(kind, id)` keys,
last-write-wins `write`, atomic `writeIfAbsent` (phase-2), `Optional.empty`
for missing keys, durability obligation for real adapters.

## Added kinds

```
FANOUT_CURSOR
PRODUCT_WITHDRAWN_VERSION
```

## FANOUT_CURSOR (engine-owned; data-model.md)

**Key id**: `kind + US + eventId + US + productId` (US = U+001F; `kind` is
the `FanOutRunKind` token `PUBLISH` or `WITHDRAW_SUPERSEDE`; `eventId` is the
publish or withdraw id).

**Value schema**:

```
status (RUNNING | COMPLETE) US totalChunks US lastCompletedChunkIndex
```

`lastCompletedChunkIndex` = -1 when none. `totalChunks` >= 0.

**Semantics (normative, spec FR-050…FR-053, R2)**:

- Claim: `writeIfAbsent` with RUNNING and the known total chunk count —
  first claimant owns the run. `false` return: existing value decides
  duplicate-skip (COMPLETE) vs resume/in-progress (RUNNING).
- Advance: last-write-wins rewrite after each fully-resolved chunk; only the
  claimant advances (single-writer convention).
- Complete: rewrite to COMPLETE with the final index.
- Resume: `lastCompletedChunkIndex + 1` is the first chunk to execute; chunk
  indices are positional over the deterministically ordered eligibility
  stream (engagement-eligibility-source.md ordering clause) — no gaps.
- Corrupt/unknown encoding: caller (cursor repository) throws; engine
  dead-letters the `CURSOR_WRITE`/run unit and alerts — never silently
  restarts from zero.

**Atomicity requirement for real adapters**: `writeIfAbsent` maps to an
INSERT against a unique constraint on `(kind, id)` (phase-2 requirement,
unchanged).

## PRODUCT_WITHDRAWN_VERSION (engine-owned marker)

**Key id**: `productId`

**Value schema**: `version1 LF version2 LF ...` (LF = newline; versions
delimiter-free — inherited phase-2 assumption).

**Semantics (normative, spec R7, FR-060)**:

- Written by the engine at supersede-run start via append-if-absent
  (synchronized read-modify-write on the repository; idempotent across
  duplicate triggers).
- Read by the engine when selecting the next eligible non-withdrawn version:
  candidate to-versions present in this list are excluded.
- Never cleared this phase (withdrawn is permanent for the version; pruning
  is later).

## Hot-path / confidentiality (unchanged + extended)

Checkpoint values for both kinds carry identifiers and measurements only —
never engagement content (spec FR-070). `FanOutCursor` values are progress
counters; the marker is a version list.

## Contract test vectors (additive)

1. Cursor claim: concurrent `writeIfAbsent` on one key -> exactly one `true`.
2. Cursor advance rewrite reflects new lastCompleted; read decodes
   round-trip.
3. Cursor COMPLETE read distinguishes duplicate-skip from resume.
4. Marker append-if-absent: same version appended twice -> list unchanged;
   different versions accumulate; order preserved.
5. Missing cursor / missing marker keys read as empty (distinguishable from
   stored empty string — phase-1 rule).
