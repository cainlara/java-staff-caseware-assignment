# Tasks: Idempotent Event Ingestion & External Checkpointing

**Input**: Design documents from `/specs/002-idempotent-event-ingestion/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/ (all present)

**Tests**: INCLUDED — spec FR-040 mandates test-first coverage (duplicate, delayed, reordering, crash-and-replay, permutation; FR-041 zero engagement-load calls asserted per test).

**Organization**: Tasks are grouped by user story so each story can be implemented and tested independently.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3, US4)
- Include exact file paths in descriptions

## Path Conventions

Single Maven module at repository root (`pom.xml`, `src/`). Paths below are
relative to the repository root. Java package root: `com.example.fanout`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Confirm the phase-1 baseline is green before additive changes

- [X] T001 Run `mvn verify` from the repository root and confirm `BUILD SUCCESS` with zero failures (phase-1 baseline: contracts, fakes, records)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Additive contract extensions and shared types every story depends on

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [X] T002 [P] Extend `CheckpointKind` in `src/main/java/com/example/fanout/ports/CheckpointKind.java` with constants `WITHDRAW`, `PRODUCT_INGEST`, `PENDING_WITHDRAW`, `PRODUCT_PUBLISHES` (keep `PUBLISH`, `BACKFILL_BATCH` unchanged; Javadoc per data-model.md key domains)
- [X] T003 [P] Add `default boolean writeIfAbsent(CheckpointKey key, String value)` to `src/main/java/com/example/fanout/ports/CheckpointStore.java`: returns `true` if written (key absent), `false` if value existed; default = non-atomic `read`-then-`write`; Javadoc states implementations backing ingest MUST provide an atomic claim (DB: unique-constraint INSERT)
- [X] T004 [P] Create enum `com.example.fanout.domain.FanOutTriggerKind` (`PUBLISH`, `WITHDRAW`) in `src/main/java/com/example/fanout/domain/FanOutTriggerKind.java`
- [X] T005 [P] Create record `com.example.fanout.domain.FanOutTrigger` in `src/main/java/com/example/fanout/domain/FanOutTrigger.java` with fields `(kind, eventId, productId, ingestSequence, matchedPublishId)` and compact-constructor validation per data-model.md: kind non-null; eventId/productId non-blank; ingestSequence >= 1; matchedPublishId non-blank iff kind = WITHDRAW, null iff kind = PUBLISH
- [X] T006 [P] Create port `com.example.fanout.ports.FanOutTriggerSink` in `src/main/java/com/example/fanout/ports/FanOutTriggerSink.java` with `void emit(FanOutTrigger trigger)`; Javadoc per contracts/fan-out-trigger.md (at-most-once, consumer idempotency key `(kind, eventId, productId)`, not a durability boundary, identifiers only)
- [X] T007 [P] Create stub `com.example.fanout.adapters.inmemory.RecordingFanOutTriggerSink` in `src/main/java/com/example/fanout/adapters/inmemory/RecordingFanOutTriggerSink.java`: thread-safe recording list, `emitted()` unmodifiable snapshot, `clear()`
- [X] T008 Override `writeIfAbsent` atomically in `src/main/java/com/example/fanout/adapters/inmemory/InMemoryCheckpointStore.java` via `ConcurrentHashMap.putIfAbsent` (depends on T003)
- [X] T009 Extend `src/test/java/com/example/fanout/ports/CheckpointStoreContractTest.java` with v2 vectors per contracts/checkpoint-store.md: absent key -> `true` + value readable; second claim -> `false` + first value wins; concurrent hammer on one key -> exactly one `true`; anonymous `CheckpointStore` implementing only read/write still compiles and sequential `writeIfAbsent` correct (depends on T003, T008)
- [X] T010 [P] Create `src/test/java/com/example/fanout/domain/FanOutTriggerTest.java` asserting T005 validation: valid PUBLISH (matchedPublishId null) constructs; valid WITHDRAW (matchedPublishId set) constructs; blank ids, ingestSequence < 1, wrong matchedPublishId nullability all rejected (depends on T005)
- [X] T011 Implement `com.example.fanout.ingest.IngestProgressRepository` in `src/main/java/com/example/fanout/ingest/IngestProgressRepository.java`: final class wrapping `CheckpointStore`, encoding/decoding the five US/LF-delimited schemas from data-model.md, operations `claimPublish`, `claimWithdraw`, `nextProductSequence`, `addProductPublish`, `deferWithdraw`, `takePendingWithdraws`, `resolveWithdraw`, `productPublishes`, `publish`, `withdraw`, `pendingWithdraws` — all encoding lives here, nowhere else (depends on T002, T003)
- [X] T012 [P] Create `com.example.fanout.ingest.EffectiveVersion` in `src/main/java/com/example/fanout/ingest/EffectiveVersion.java`: static pure functions `selectEffective(List<TemplatePublished>)` and `matchPublish(List<TemplatePublished>, String version)` — max `(publishedAtEpochMilli, publishId)` pair per rule R4; `matchPublish` filters `toVersion == version` then same max-pair, empty if none (depends on phase-1 `TemplatePublished` only)
- [X] T013 [P] Create `com.example.fanout.ingest.IngestStatus` enum (`INGESTED`, `DUPLICATE`, `DEFERRED`, `REJECTED`) and record `com.example.fanout.ingest.IngestOutcome` in `src/main/java/com/example/fanout/ingest/`: fields `(status, eventId, resolvedWithdrawIds)` — eventId non-blank, resolvedWithdrawIds mandatory non-null possibly empty, defensively copied unmodifiable per data-model.md

**Checkpoint**: Foundation ready — all four user stories can now proceed

---

## Phase 3: User Story 1 - Idempotent publish ingestion with a fan-out trigger (Priority: P1) 🎯 MVP

**Goal**: A publish event is validated, claimed atomically in the checkpoint store, appended to per-product ingest order, and emits exactly one fan-out trigger; duplicates ack with no trigger and a duplicate counter increment.

**Independent Test**: Deliver the same publish repeatedly to `IngestService`; observe exactly one checkpoint record, one trigger, `ingest.duplicate` increments per redelivery, zero errors, zero engagement-load calls (`IngestServiceIdempotencyTest`).

### Tests for User Story 1 (test-first — write FIRST, verify they FAIL to compile/run before T015)

- [X] T014 [P] [US1] Create `src/test/java/com/example/fanout/ingest/IngestServiceIdempotencyTest.java` covering: fresh publish -> INGESTED, one PUBLISH trigger via `RecordingFanOutTriggerSink`, `PUBLISH` claim readable, `PRODUCT_PUBLISHES` contains id, sequence assigned; duplicate publish -> DUPLICATE, zero new triggers, `ingest.duplicate` counter +1 (via `RecordingFanOutMetrics`), checkpoint and product list unchanged; null argument -> throws, no checkpoint, no trigger; every test asserts `FakeDownstreamLoadClient.getCallCount() == 0` (FR-030/FR-041) (depends on T002–T013 types; will not compile until T015 exists — expected red state)

### Implementation for User Story 1

- [X] T015 [US1] Implement `com.example.fanout.ingest.IngestService` in `src/main/java/com/example/fanout/ingest/IngestService.java`: final class, constructor-injected `IngestProgressRepository`, `FanOutTriggerSink`, `FanOutMetrics`; method `IngestOutcome ingest(TemplatePublished event)` executing per contracts/ingest-service.md order: null-check -> `claimPublish` (false => DUPLICATE + `ingest.duplicate` + return) -> `nextProductSequence` -> `addProductPublish` -> skip withdraw resolution for now (empty list until T018) -> emit one PUBLISH `FanOutTrigger` -> increment `ingest.published` -> return `(INGESTED, publishId, [])`; no `DownstreamLoadClient` dependency (depends on T011, T012, T013, T006, T007)

**Checkpoint**: US1 fully functional — idempotent publish ingest with trigger and duplicate counter

---

## Phase 4: User Story 2 - Withdraw ingestion with deferral until its publish arrives (Priority: P1)

**Goal**: A withdraw is claimed idempotently; processed immediately when its publish was ingested, otherwise recorded as deferred and auto-resolved (with exactly one WITHDRAW trigger) when the matching publish later arrives.

**Independent Test**: Deliver a withdraw for an uningested (product, version); observe DEFERRED with pending-index listing and zero triggers; deliver the matching publish; observe resolution linkage, one WITHDRAW trigger, pending list cleared (`IngestServiceWithdrawDeferralTest`).

### Tests for User Story 2 (test-first)

- [X] T016 [P] [US2] Create `src/test/java/com/example/fanout/ingest/IngestServiceWithdrawDeferralTest.java` covering: withdraw with no matching publish -> DEFERRED, zero triggers, `WITHDRAW` record status DEFERRED, `PENDING_WITHDRAW` lists withdraw id, `ingest.deferred` counter; then matching publish ingested -> outcome `resolvedWithdrawIds` contains withdraw id, `WITHDRAW` record RESOLVED with matched publish id, exactly one WITHDRAW trigger, pending list cleared, `ingest.resolved` counter; withdraw after its publish -> immediate INGESTED with matchedPublishId trigger; duplicate withdraw (post-defer and post-resolve) -> DUPLICATE, zero new triggers; withdraw whose publish never arrives -> stays DEFERRED, no error; two distinct withdraw ids same version -> each resolved once; duplicate-delivered matching publish still satisfies resolution; every test asserts zero engagement-load calls (depends on T015)

### Implementation for User Story 2

- [X] T017 [US2] Implement withdraw path `IngestOutcome ingest(TemplateWithdrawn event)` in `src/main/java/com/example/fanout/ingest/IngestService.java` per contracts/ingest-service.md: null-check -> `claimWithdraw` (false => DUPLICATE + counter + return) -> load product publishes, `EffectiveVersion.matchPublish(..., version)` -> match found: `resolveWithdraw`, emit WITHDRAW trigger with matchedPublishId, `ingest.withdrawn` + `ingest.resolved`, return INGESTED; no match: `deferWithdraw` (append if absent), `ingest.withdrawn` + `ingest.deferred`, zero triggers, return DEFERRED (depends on T016 red state, T011, T012)
- [X] T018 [US2] Add publish-path deferred-withdraw resolution to `ingest(TemplatePublished)` in `src/main/java/com/example/fanout/ingest/IngestService.java` (step 5 of contract): after `addProductPublish`, `takePendingWithdraws(productId, toVersion)`; for each id, skip if `WITHDRAW` already RESOLVED, else `resolveWithdraw(id, publishId)` and collect; emit one WITHDRAW trigger per resolved id (matchedPublishId = publishId, same product ingestSequence); clear list (inside take); `ingest.resolved` counter; outcome carries `resolvedWithdrawIds` (depends on T017)

**Checkpoint**: US1 and US2 both work — full ingest path with reordering tolerance for withdraws

---

## Phase 5: User Story 3 - Crash recovery driven solely by external checkpoints (Priority: P2)

**Goal**: Kill before claim -> redelivery processes once; kill after claim, before trigger -> redelivery acked as duplicate; two service instances sharing one store exhibit combined idempotency.

**Independent Test**: Fault-inject at both window edges with failing port wrappers; redeliver against the same shared store; assert single-ingest effect on both paths and one trigger total across two instances (`IngestServiceCrashRecoveryTest`, `IngestServiceMultiInstanceTest`).

### Tests for User Story 3 (test-first; fault-injection fakes land first)

- [X] T019 [P] [US3] Create test doubles in `src/test/java/com/example/fanout/fakes/`: `FailingCheckpointStore` (wraps a real `CheckpointStore`, throws `IllegalStateException` on claim writes once armed, pass-through otherwise) and `FailingTriggerSink` (wraps `RecordingFanOutTriggerSink`, throws on `emit` once armed) — armed/disarmed toggles for deterministic crash injection (no production hooks, research.md D7)
- [X] T020 [P] [US3] Create `src/test/java/com/example/fanout/ingest/IngestServiceCrashRecoveryTest.java` covering: kill-before-claim (armed `FailingCheckpointStore`) -> error surfaced, zero triggers, no durable `PUBLISH` record; redelivery on healthy store -> INGESTED once, one trigger (FR-022); kill-after-claim (armed `FailingTriggerSink`) -> error surfaced; `PUBLISH` claim value readable and carries productId + toVersion (FR-024 sufficiency); redelivery -> DUPLICATE, zero additional triggers, `ingest.duplicate` incremented (FR-021); every test asserts zero engagement-load calls (depends on T019, T015)
- [X] T021 [P] [US3] Create `src/test/java/com/example/fanout/ingest/IngestServiceMultiInstanceTest.java`: two `IngestService` instances sharing one `InMemoryCheckpointStore` (same backing map), separate sinks; deliver same publish to instance A then B -> first INGESTED, second DUPLICATE, exactly one trigger total across both sinks (FR-023, SC-007); same scenario for a withdraw (depends on T015, T017)

**Checkpoint**: All three correctness axes testable — idempotency, reordering (withdraw), crash recovery

---

## Phase 6: User Story 4 - Out-of-order publishes retained with recorded ingest order (Priority: P2)

**Goal**: Multiple publishes for one product are all retained with arrival-order recorded; effective version (rule R4) is identical regardless of arrival order; any duplication + reordering of a batch converges to identical functional checkpoint state.

**Independent Test**: Ingest two publishes for one product in both orders; assert both ids retained, sequences match arrival, R4 effective version equal (`IngestServiceReorderingTest`); run all permutations × duplication patterns of a batch and diff normalized checkpoint state (`IngestPermutationTest`).

### Tests for User Story 4 (test-first; assertions only — service behavior from T015/T017/T018 already satisfies R1–R4)

- [X] T022 [P] [US4] Create `src/test/java/com/example/fanout/ingest/IngestServiceReorderingTest.java`: two publishes same product, order (A,B) and (B,A) -> both INGESTED both times, `PRODUCT_PUBLISHES` holds both ids, per-product sequences reflect arrival order, `EffectiveVersion.selectEffective` yields the same publish pair (greatest publishedAt, then publishId) for both orders; also cover republish to same toVersion (both retained; R4 tie-break by publishId); every test asserts zero engagement-load calls (depends on T015, T012)
- [X] T023 [US4] Create `src/test/java/com/example/fanout/ingest/IngestPermutationTest.java`: build a batch of publishes (multi-product, including two same-product publishes with distinct publishedAt) plus a deferred withdraw scenario; enumerate all permutations with injected duplicates (each event delivered 1–3 times); for each run, assert identical functional checkpoint state per FR-014: same processed publish-id set, same `EffectiveVersion` per product, same withdraw resolutions; normalize observational fields (per-product sequence numbers, `PRODUCT_PUBLISHES` arrival order) before comparing; every run asserts zero engagement-load calls (depends on T022, T018)

**Checkpoint**: All four user stories independently functional and cross-validated by permutation convergence

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Whole-suite validation and operator-facing notes

- [X] T024 Run full `mvn verify` from the repository root (zero failures) and the quickstart.md validation greps: self-containment check and ingest-package out-of-scope leak check (`specs/002-idempotent-event-ingestion/quickstart.md`), confirming expected `OK` outputs
- [X] T025 [P] Add a short "Ingestion & Checkpointing" section to `README.md`: duplicate-claim via atomic `writeIfAbsent`, checkpoint-then-trigger crash window (reconciler deferred), deferred-withdraw resolution rule R3, effective version rule R4, zero engagement loads on the ingest path — identifiers only, no external document references

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — baseline verify
- **Foundational (Phase 2)**: Depends on Setup — BLOCKS all user stories
- **User Story 1 (Phase 3)**: Depends on Foundational — no other story dependencies
- **User Story 2 (Phase 4)**: Depends on Foundational + US1 (T015 publish path; resolution hook extends it)
- **User Story 3 (Phase 5)**: Depends on Foundational + US1 (+ US2 for the withdraw multi-instance scenario T021)
- **User Story 4 (Phase 6)**: Depends on Foundational + US1 + US2 (permutation includes withdraw resolutions)
- **Polish (Phase 7)**: Depends on all stories complete

### User Story Dependencies

- **User Story 1 (P1)**: After Foundational — fully independent; MVP increment
- **User Story 2 (P1)**: After US1 — withdraw resolution hook lives in the same service file as the publish path (T015/T018 touch `IngestService.java`; sequence, do not parallelize those two)
- **User Story 3 (P2)**: After US1; T021 additionally needs T017 (withdraw path)
- **User Story 4 (P2)**: After US1 + US2 (convergence assertions include withdraw resolutions)

### Within Each User Story

- Test tasks (red) before implementation tasks (green) — FR-040 test-first
- New types before services; service methods before cross-story hooks
- Story checkpoint before moving to next priority

### Parallel Opportunities

- Foundational: T002–T007, T010, T012, T013 are independent files — run together; then T008/T009/T011
- US1: only T014 (single test file)
- US2: only T016 (single test file); T017 then T018 sequential (same file)
- US3: T019, T020, T021 test files are independent once US1/US2 impls exist — run together
- US4: T022 then T023 (T023 reuses T022's helpers/patterns)
- Polish: T024 and T025 independent — run together

---

## Parallel Example: Foundational (Phase 2)

```bash
# Launch together (different files, no dependencies):
Task: "Extend CheckpointKind in src/main/java/com/example/fanout/ports/CheckpointKind.java"
Task: "Add writeIfAbsent default to src/main/java/com/example/fanout/ports/CheckpointStore.java"
Task: "Create FanOutTriggerKind in src/main/java/com/example/fanout/domain/FanOutTriggerKind.java"
Task: "Create FanOutTrigger in src/main/java/com/example/fanout/domain/FanOutTrigger.java"
Task: "Create FanOutTriggerSink in src/main/java/com/example/fanout/ports/FanOutTriggerSink.java"
Task: "Create RecordingFanOutTriggerSink in src/main/java/com/example/fanout/adapters/inmemory/RecordingFanOutTriggerSink.java"

# Then (depend on the above):
Task: "Override atomic writeIfAbsent in InMemoryCheckpointStore (depends T003)"
Task: "Implement IngestProgressRepository (depends T002, T003)"
```

## Parallel Example: User Story 3

```bash
# After US1+US2 implementation — three independent test/fake files:
Task: "Create FailingCheckpointStore + FailingTriggerSink in src/test/java/com/example/fanout/fakes/"
Task: "Create IngestServiceCrashRecoveryTest in src/test/java/com/example/fanout/ingest/"
Task: "Create IngestServiceMultiInstanceTest in src/test/java/com/example/fanout/ingest/"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: baseline `mvn verify` green
2. Complete Phase 2: Foundational (CRITICAL — blocks all stories)
3. Complete Phase 3: User Story 1 (test-first: T014 red, T015 green)
4. **STOP and VALIDATE**: `mvn verify` green — idempotent publish ingest with trigger + duplicate counter is shippable value
5. Demo: duplicate publish deliveries converge to one trigger

### Incremental Delivery

1. Setup + Foundational → additive-only, phase-1 tests stay green
2. Add US1 → test independently → MVP (idempotent publish ingestion)
3. Add US2 → test independently → withdraw deferral/resolution live
4. Add US3 → crash-window and multi-instance proofs
5. Add US4 → permutation convergence proof over the whole ingest path
6. Polish → full-gate + README

### Notes

- [P] tasks = different files, no dependencies; T015/T017/T018 all touch `IngestService.java` — serialize those three
- Each story phase ends at a checkpoint where `mvn verify` must be green
- Every ingest test asserts `FakeDownstreamLoadClient.getCallCount() == 0` (FR-041) — do not defer this assertion
- No new Maven dependencies; encoding is US/LF-delimited strings inside `IngestProgressRepository` only
- Commit after each task or logical group; stop at any checkpoint to validate a story independently
