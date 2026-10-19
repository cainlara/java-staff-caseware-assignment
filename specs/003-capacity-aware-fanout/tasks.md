---

description: "Task list template for feature implementation"
---

# Tasks: Capacity-Aware Fan-Out Engine

**Input**: Design documents from `/specs/003-capacity-aware-fanout/`

**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/

**Tests**: Tests ARE requested — spec FR-072 mandates test-first, deterministic coverage (latches, fake clock, seeded jitter, test-controlled runner, no sleep-based sync) and FR-073 requires all tests inside the single `mvn verify` gate. Test tasks appear before their implementation tasks in every phase.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **Single project (this repo)**: `src/main/java/com/example/fanout/`, `src/test/java/com/example/fanout/` at repository root (Maven project root)
- Extend the existing single Maven module in place; no new modules, no new dependencies (plan.md Technical Context, Constitution VII)

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Package scaffolding and additive port extensions every later phase needs

- [X] T001 Create fan-out package directories `src/main/java/com/example/fanout/fanout/` and `src/test/java/com/example/fanout/fanout/` per plan.md Project Structure
- [X] T002 [P] Extend `src/main/java/com/example/fanout/ports/CheckpointKind.java` with additive constants `FANOUT_CURSOR` and `PRODUCT_WITHDRAWN_VERSION`; phase-1/2 constants unchanged (Constitution VII)
- [X] T003 [P] Add additive method `Optional<PendingIndexRow> find(String engagementId, String offeredVersion, PendingIndexState state)` to `src/main/java/com/example/fanout/ports/IndexWriter.java` per contracts/index-writer.md (read-only, null/blank rejected)
- [X] T004 [P] Extend `src/test/java/com/example/fanout/fakes/RecordingFanOutMetrics.java` with helpers to assert the mandated fan-out counter/timer allowlist from contracts/fan-out-metrics.md (`fanout.chunks.started`, `fanout.chunks.completed`, `fanout.rows.resolved`, `fanout.retry`, `fanout.dlq`, `fanout.backpressure`, `fanout.duplicate.run`, `fanout.cas.reapply`, `fanout.downstream.inflight.hwm`, timer `fanout.publish_to_pending`)

**Checkpoint**: additive ports ready — foundational phase can start

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: New domain records, ports, fakes, and typed cursor adapter — MUST complete before ANY user story

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [X] T005 [P] Create domain enums in `src/main/java/com/example/fanout/domain/`: `FanOutRunKind.java` (`PUBLISH`, `WITHDRAW_SUPERSEDE`), `FanOutRunStatus.java` (`RUNNING`, `COMPLETE`), `FanOutOutcomeStatus.java` (`COMPLETED`, `COMPLETED_EMPTY`, `SKIPPED_DUPLICATE`, `SKIPPED_IN_PROGRESS`, `FAILED_DEAD_LETTERED`), `DeadLetterUnitKind.java` (`CHUNK_WRITE`, `ELIGIBILITY_STREAM`, `DOWNSTREAM_CALL`, `CURSOR_WRITE`, `SUPERSEDE_PLAN`) per data-model.md
- [X] T006 [P] Create `src/main/java/com/example/fanout/domain/FanOutCursor.java` record per data-model.md: kind/eventId/productId/status/totalChunks (`>= 0`)/lastCompletedChunkIndex (`>= -1`); invariant `lastCompletedChunkIndex < totalChunks` when `totalChunks > 0`; `COMPLETE` implies `lastCompletedChunkIndex == totalChunks - 1` or `totalChunks == 0`
- [X] T007 [P] Create `src/main/java/com/example/fanout/domain/DeadLetterRecord.java` record per data-model.md: eventId (non-blank), productId (non-blank), chunkIndex (`>= -1`), unitKind (non-null), attemptCount (`>= 1`), lastFailureType (non-blank), lastFailureMessage (non-blank allowed empty, never engagement content), recordedAt (non-null)
- [X] T008 [P] Create `src/main/java/com/example/fanout/domain/FanOutConfig.java` record per data-model.md: chunkSize 500 (`>= 1`), poolBound 16 (`>= 1`), firmInFlightCap 2 (`>= 1`), retryMaxAttempts 5 (`>= 1`), retryBaseDelay 100 ms (positive), retryMaxDelay 30 s (`>= base`), jitterFactorMin 0.5 (`>= 0.0`, `< max`), jitterFactorMax 1.5 (`> min`); provide `FanOutConfig.defaults()`
- [X] T009 [P] Create `src/main/java/com/example/fanout/domain/FanOutRunOutcome.java` record (status `FanOutOutcomeStatus`, eventId non-blank, chunkCount `>= 0`, rowsResolved `long >= 0`, deadLetterCount `>= 0`) per data-model.md (depends: T005)
- [X] T010 Reshape `src/main/java/com/example/fanout/domain/FanOutTask.java` to data-model.md fields (eventId, productId, kind `FanOutRunKind`, chunkIndex `>= 0`, firmId, engagements `List<EngagementRef>` defensively copied unmodifiable, non-empty); identity `(kind, eventId, productId, chunkIndex)`; update `src/test/java/com/example/fanout/domain/FanOutTaskTest.java` (record is referenced only by its own test — safe to reshape) (depends: T005)
- [X] T011 [P] Create port `src/main/java/com/example/fanout/ports/EngagementEligibilitySource.java`: `Stream<EngagementRef> streamEligible(String productId, String publishId)` and `Stream<EngagementRef> streamOffering(String productId, String version)` per contracts/engagement-eligibility-source.md (ordering: firm id asc, engagement id asc; metadata only)
- [X] T012 [P] Create port `src/main/java/com/example/fanout/ports/DeadLetterStore.java`: `void store(DeadLetterRecord)` append-only, `List<DeadLetterRecord> byEvent(String eventId)` in store order per contracts/dead-letter-store.md
- [X] T013 Create `src/main/java/com/example/fanout/fanout/FanOutCursorRepository.java` typed adapter over CheckpointStore per data-model.md: `claimRun` (writeIfAbsent RUNNING), `readRun`, `advance` (last-write-wins RUNNING), `complete`, `markWithdrawn` (append-if-absent, synchronized), `withdrawnVersions`, `ingestedPublishes` (reuse phase-2 PRODUCT_PUBLISHES decoding); cursor key `kind + US + eventId + US + productId` (US = U+001F); value `status US totalChunks US lastCompletedChunkIndex`; corrupt encoding throws (depends: T002, T006)
- [X] T014 [P] Create `src/main/java/com/example/fanout/fanout/BackoffWaiter.java` interface: real sleeper implementation and test-controlled immediate-advance implementation (research.md D5)
- [X] T015 [P] Create `src/main/java/com/example/fanout/fanout/TaskRunner.java` interface for test-controlled bounded-pool chunk execution (research.md D5)
- [X] T016 [P] Create `src/main/java/com/example/fanout/fanout/JitterSource.java` interface for injectable deterministic jitter (research.md D5)
- [X] T017 [P] Create `src/test/java/com/example/fanout/fakes/FakeEligibilitySource.java`: in-memory lists keyed `(productId, publishId)` / `(productId, version)`, deterministic sort in contract order, scale-generator seeding helper for 40,000-engagement tests, `failNextStream(n)` failure injection per contracts/engagement-eligibility-source.md (depends: T011)
- [X] T018 [P] Create `src/test/java/com/example/fanout/fakes/FakeDeadLetterStore.java`: synchronized list, `store` appends, `byEvent` filters, hooks `all()`, `count()` per contracts/dead-letter-store.md (depends: T012)
- [X] T019 [P] Create test fakes in `src/test/java/com/example/fanout/fakes/`: `FakeClock.java` (manual advance), `SeededJitter.java` (deterministic jitter in [0.5, 1.5)), `TestTaskRunner.java` (test-controlled execution), `FailingIndexWriter.java` (scripted transient failures wrapping `InMemoryIndexWriter`)
- [X] T020 [P] Create domain validation tests in `src/test/java/com/example/fanout/domain/`: `FanOutCursorTest.java`, `DeadLetterRecordTest.java`, `FanOutConfigTest.java`, `FanOutRunOutcomeTest.java` — invalid construction rejected per data-model.md (depends: T006, T007, T008, T009)
- [X] T021 [P] Extend `src/test/java/com/example/fanout/ports/CheckpointStoreContractTest.java` with additive vectors from contracts/checkpoint-store.md: concurrent claim yields exactly one `true`, advance/complete round-trip, COMPLETE distinguishes duplicate-skip from resume, marker append-if-absent idempotent, missing keys read empty (depends: T002, T013)
- [X] T022 [P] Create `src/test/java/com/example/fanout/ports/DeadLetterStoreContractTest.java` with contract vectors 1-4 from contracts/dead-letter-store.md (depends: T012, T018)
- [X] T023 Run `mvn verify` from repository root — all existing tests plus new foundational tests green before any user story work (depends: T005–T022)

**Checkpoint**: Foundation ready — user story implementation can now begin in parallel

---

## Phase 3: User Story 1 - Publish fans out to pending rows without touching the downstream (Priority: P1) 🎯 MVP

**Goal**: A publish trigger becomes one pending-index row per eligible engagement, written from metadata only, with zero downstream engagement-load calls and idempotent replay.

**Independent Test**: Deliver one publish trigger for a product with 1,000 eligible engagements; assert 1,000 pending rows appear through the index writer, the downstream call log records zero calls, and re-running the whole fan-out for the same publish changes no row.

**Note**: The fair scheduler is implemented here because `FanOutEngine` cannot execute chunks without it (contract fan-out-engine.md step 4); US2 owns the fairness verification suite (FR-012) and any scheduler fixes it forces.

### Tests for User Story 1 (FIRST — must fail before implementation)

- [X] T024 [P] [US1] Add contract test vectors 1-6 (find read-your-writes, absent -> empty, no mutation, stale -> find -> merge -> re-upsert, concurrent-writer convergence, zero downstream) to `src/test/java/com/example/fanout/ports/IndexWriterContractTest.java` (depends: T003)
- [X] T025 [P] [US1] Write `src/test/java/com/example/fanout/fanout/FanOutEnginePublishTest.java`: 1,000 eligible -> exactly 1,000 PENDING rows, 2 chunks of 500, cursor COMPLETE, downstream call log empty (SC-001); zero-eligible publish -> `COMPLETED_EMPTY`, zero writes, cursor COMPLETE (FR-004); ~20,000-row publish completes within 60 s test-scale budget (SC-010)
- [X] T026 [P] [US1] Write `src/test/java/com/example/fanout/fanout/FanOutEngineIdempotencyTest.java`: replay of completed publish -> `SKIPPED_DUPLICATE`, row set unchanged (SC-004); duplicate chunk execution (sequential and concurrent via `TestTaskRunner`) -> single-execution row set, one cursor advance (FR-052/FR-053); CAS stale-rejection re-apply converges and increments `fanout.cas.reapply` (FR-021)

### Implementation for User Story 1

- [X] T027 [US1] Implement `src/main/java/com/example/fanout/fanout/FairShardScheduler.java` per research.md D4/data-model.md FairShardState: `Map<firmId, Deque<FanOutTask>>` FIFO, `TreeSet` pending-firm scan with rotating cursor, `Semaphore(firmInFlightCap)` per firm, total in-flight bounded by `poolBound` (depends: T010)
- [X] T028 [US1] Implement `src/main/java/com/example/fanout/fanout/FanOutEngine.java` publish path per contracts/fan-out-engine.md: validate trigger; stream + materialize eligibility in R1 order; partition into positional chunks of `chunkSize`; zero chunks -> claim + COMPLETE + `COMPLETED_EMPTY`; claim cursor (`writeIfAbsent` RUNNING); claim false + COMPLETE -> `SKIPPED_DUPLICATE` with `fanout.duplicate.run`; schedule remaining chunks via `FairShardScheduler` on `TaskRunner`; per engagement upsert `PendingIndexRow` (state PENDING, offeredVersion = publish to-version, publishIds = {publishId}, fresh `updatedAt` from injected clock) through `IndexWriter`; on `REJECTED_STALE` re-read via `find`, merge, re-upsert once (`fanout.cas.reapply`); advance cursor after every write of a chunk resolves; rewrite COMPLETE at end; emit `fanout.chunks.started/completed`, `fanout.rows.resolved`, timer `fanout.publish_to_pending`; NO reference to `DownstreamLoadClient` or `CapacityBudget` (depends: T013, T027)
- [X] T029 [US1] Implement the deterministic merge helper for the R4 re-apply cycle in `src/main/java/com/example/fanout/fanout/FanOutEngine.java` (or a package-private class in `src/main/java/com/example/fanout/fanout/`) per data-model.md "Deterministic merge on re-apply": intent fields from I, `decision`/`pinnedRanges`/`summaryKey` preserved from stored row for publish-kind writes, `publishIds` union, `rowVersion` = stored + 1, `updatedAt` = I's timestamp (depends: T028)
- [X] T030 [US1] Run `mvn verify`; fix until T024-T026 pass: downstream call log empty in every publish test, row-set assertions green, no sleep-based synchronization anywhere (depends: T027, T028, T029)

**Checkpoint**: At this point, User Story 1 is fully functional and testable independently — MVP candidate

---

## Phase 4: User Story 2 - Skewed firms are served fairly (Priority: P1)

**Goal**: A 40,000-engagement firm cannot monopolize the worker pool; median-40 firms finish while the giant firm is still running, with per-firm caps and the pool ceiling held at every observation point.

**Independent Test**: Simulate a publish spanning a 40,000-engagement firm and 500 median-40 firms; assert no firm ever exceeds its in-flight cap and every median firm completes while the giant firm is still incomplete.

### Tests for User Story 2 (FIRST)

- [X] T031 [P] [US2] Write `src/test/java/com/example/fanout/fanout/FairShardSchedulerTest.java`: per-firm in-flight never exceeds `firmInFlightCap` at any observation; round-robin interleave in firm-id order (firms at cap skipped, not queued ahead); total in-flight never exceeds `poolBound` (SC-009, FR-011); deterministic interleaving via `TestTaskRunner`
- [X] T032 [P] [US2] Write `src/test/java/com/example/fanout/fanout/LargePublishSimulationTest.java`: seed `FakeEligibilitySource` with one 40,000-engagement firm + 500 median-40 firms; run publish; assert 40,000 rows present exactly once, all median firms complete while giant firm still incomplete (SC-005, FR-012), caps/ceiling held throughout, downstream call log empty

### Implementation for User Story 2

- [X] T033 [US2] Fix/extend `src/main/java/com/example/fanout/fanout/FairShardScheduler.java` until T031 and T032 pass: rotating round-robin cursor per research.md D4, per-firm semaphore acquisition, cap-skip semantics (skip cycle, never queue ahead), starvation-freedom for median firms (depends: T031, T032)

**Checkpoint**: User Stories 1 AND 2 both work independently

---

## Phase 5: User Story 3 - Crash mid-publish resumes with no gaps and no duplicates (Priority: P1)

**Goal**: Kill mid-publish, restart against the same checkpoint store, resume at the first incomplete chunk — identical final row set, no duplicates, corrupt cursor fails loudly to dead-letter.

**Independent Test**: Run a fan-out with a fault injected after N chunks, restart against the same store; assert the resumed run starts at chunk N+1, the final row set equals a fault-free run's row set exactly, and no chunk produced duplicate rows.

### Tests for User Story 3 (FIRST)

- [X] T034 [P] [US3] Write `src/test/java/com/example/fanout/fanout/FanOutEngineCrashResumeTest.java`: fault injected after N chunks (scripted `FailingIndexWriter` / `TestTaskRunner` abort), engine re-entered against same `InMemoryCheckpointStore` -> resume at chunk N+1, chunks 0..N-1 not re-processed, final rows identical to fault-free run (SC-003, FR-051); crash between chunk writes and cursor advance -> chunk re-runs, identical row set (FR-052); duplicate concurrent chunk -> single cursor advance (FR-053); completed cursor records chunk count and last completed chunk (US3 scenario 4); corrupt cursor encoding -> loud failure dead-lettered as `CURSOR_WRITE` + alert, never silent restart from zero

### Implementation for User Story 3

- [X] T035 [US3] Implement resume continuation in `src/main/java/com/example/fanout/fanout/FanOutEngine.java`: claim false + RUNNING -> `readRun`, resume from `lastCompletedChunkIndex + 1` over the deterministic positional stream (FR-051); live-run detection -> `SKIPPED_IN_PROGRESS`; corrupt/unknown cursor encoding -> `DeadLetterRecord(CURSOR_WRITE)` + `fanout.dlq` increment, run ends `FAILED_DEAD_LETTERED` (depends: T034)

**Checkpoint**: All P1 stories independently functional

---

## Phase 6: User Story 4 - Transient failures retry with bounds, then dead-letter with an alert (Priority: P2)

**Goal**: Bounded exponential backoff + jitter on injectable clock for every failed unit; exhaustion stores a full-identity dead-letter record and increments the alert counter — never a silent drop; off-path downstream calls gated by permit limit C.

**Independent Test**: Writer fails k < budget times then succeeds -> attempts and jittered delays within bounds under fake clock; failures >= budget -> unit in dead-letter store with identity, alert counter incremented, zero lost / zero duplicated rows after replay; latch-held downstream with C = 3 -> exactly 3 in flight ever.

### Tests for User Story 4 (FIRST)

- [X] T036 [P] [US4] Write `src/test/java/com/example/fanout/fanout/RetryExecutorTest.java` with `FakeClock` + `SeededJitter`: attempts <= configured budget, delays grow exponentially from base, capped at max, every delay within computed jitter bound in [0.5, 1.5) (SC-006); exhaustion -> `DeadLetterRecord` with full work identity + `fanout.dlq` incremented, run outcome `FAILED_DEAD_LETTERED` (SC-007); replayed unit converges to first-attempt-success state (FR-043); zero sleep-based sync
- [X] T037 [P] [US4] Write `src/test/java/com/example/fanout/fanout/PermitGuardedDownstreamTest.java` and create `src/test/java/com/example/fanout/fakes/LatchDownstreamClient.java` (latch-blocked, exact in-flight counter + atomic max, `releaseAll()`, `failNext(n)`): C = 3 with 10 guarded calls -> exactly 3 in flight, max-tracker = 3 (SC-002); transient failure -> permit released before backoff, final success, hwm <= C; exhaustion -> `DOWNSTREAM_CALL` dead-letter + `fanout.dlq` (FR-041); permit exhaustion -> `fanout.backpressure` increments, no unbounded buffering (FR-031); quiescence -> `available() == C`

### Implementation for User Story 4

- [X] T038 [US4] Implement `src/main/java/com/example/fanout/fanout/RetryExecutor.java` per research.md D8/contract R6: bounded exponential backoff from `retryBaseDelay` to `retryMaxDelay`, jitter factor in [jitterFactorMin, jitterFactorMax), time via injected `Clock` + `BackoffWaiter` + `JitterSource`; on exhaustion write `DeadLetterRecord` via `DeadLetterStore` and increment `fanout.dlq`; emit `fanout.retry` per attempt (depends: T014, T016, T019)
- [X] T039 [US4] Implement `src/main/java/com/example/fanout/fanout/PermitGuardedDownstream.java` per contracts/permit-guard.md: `tryAcquire` loop with `fanout.backpressure` + `BackoffWaiter` pause on false; `client.loadEngagement(ref)` inside try/finally with exactly-once `release`; `fanout.downstream.inflight.hwm` on new peak; retry exhaustion -> `DeadLetterRecord(DOWNSTREAM_CALL)` + `fanout.dlq`; `inflight()` accessor; hot-path prohibition (depends: T038, T037)
- [X] T040 [US4] Wire `RetryExecutor` into `src/main/java/com/example/fanout/fanout/FanOutEngine.java` chunk-write path and eligibility-stream read: transient failure -> bounded retries -> on exhaustion `DeadLetterRecord(CHUNK_WRITE or ELIGIBILITY_STREAM)` + `fanout.dlq`; dead-lettered chunk still advances the cursor so the run terminates; any dead-lettered unit -> outcome `FAILED_DEAD_LETTERED`; extend `src/test/java/com/example/fanout/fanout/FanOutEnginePublishTest.java` / `FanOutEngineIdempotencyTest.java` assertions as needed for metric allowlist (depends: T036, T038)

**Checkpoint**: failure paths recoverable, off-path rate limit enforced

---

## Phase 7: User Story 5 - Withdrawn version supersedes pending offers (Priority: P2)

**Goal**: Rows offering a withdrawn version move to `CREATED` or re-offer the next eligible non-withdrawn version; recorded decisions preserved byte-for-byte; same chunking/fairness/checkpoint/retry machinery; zero downstream calls.

**Independent Test**: Publish offering version V with declined-and-pinned rows; withdraw V; assert every V-offering row now offers the next non-withdrawn version (or returns to `CREATED` when none exists), decline/pinned records survive untouched, downstream call log still empty.

### Tests for User Story 5 (FIRST)

- [X] T041 [P] [US5] Write `src/test/java/com/example/fanout/fanout/WithdrawSupersedeTest.java`: V -> next eligible version N per R7 rule (greatest `(publishedAt, publishId)` among non-withdrawn publishes strictly newer than V's publish, no version-string arithmetic); no next version -> `CREATED`; `decision`/`pinnedRanges`/`summaryKey` preserved byte-identical (SC-008, FR-061); zero downstream calls (FR-032); supersede uses cursor + chunks + fairness machinery (FR-062); withdraw with no rows offering V -> `COMPLETED_EMPTY` with cursor (FR-063); crashed supersede resumes per same cursor rules; duplicate withdraw trigger idempotent via marker append-if-absent

### Implementation for User Story 5

- [X] T042 [US5] Implement withdraw supersede in `src/main/java/com/example/fanout/fanout/FanOutEngine.java`: `runWithdrawSupersede(trigger)` entry; first `FanOutCursorRepository.markWithdrawn(productId, version)` (before any write, durable across crashes); plan via `streamOffering(productId, V)` + `ingestedPublishes(productId)` minus withdrawn versions (R7 ordering via phase-2 `EffectiveVersion` rule); per-row intended write preserves `decision`/`pinnedRanges`/`summaryKey` from stored row, sets `offeredVersion` = N or `state` = CREATED, adds supersede identity to `publishIds`, CAS via `find` + merge; same chunking/checkpoint/retry path as publish (depends: T041)

**Checkpoint**: All five user stories independently functional

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: Suite-wide invariants and gates that affect multiple stories

- [X] T043 [P] Run self-containment checks from `specs/003-capacity-aware-fanout/quickstart.md`: `grep -rniE 'TODO: see (the )?(spec|doc|pdf)|/docs/|sharepoint|intake doc' src pom.xml` expects `self-contained: OK`; hot-path grep `grep -rn 'DownstreamLoadClient\|CapacityBudget' src/main/java/com/example/fanout/fanout` scoped to `FanOutEngine.java`/`FairShardScheduler.java`/`RetryExecutor.java`/`FanOutCursorRepository.java` expects zero hits
- [X] T044 [P] Add suite-wide invariant assertions in `src/test/java/com/example/fanout/fanout/`: every publish/withdraw test asserts downstream call log empty (SC-001); metrics allowlist assertion via `RecordingFanOutMetrics` — hot-path tests emit no `fanout.backpressure` / `fanout.downstream.inflight.hwm` names; no engagement content in any metric/log argument (FR-070)
- [X] T045 Run full gate `mvn verify` from repository root — BUILD SUCCESS, zero failures, zero sleep-based synchronization in suite (FR-072, FR-073, quickstart.md Primary gate)
- [X] T046 Code cleanup pass across `src/main/java/com/example/fanout/fanout/` and new domain/ports files: remove dead code, confirm additive-only public API (Constitution VII), confirm no new dependencies in `pom.xml`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion - BLOCKS all user stories
- **User Stories (Phase 3+)**: All depend on Foundational phase completion
  - US1 (P1) first — MVP
  - US2 (P1) and US3 (P1) depend on US1 engine existing (FairShardScheduler built in US1, resume logic extends the engine)
  - US4 (P2) depends on US1 (wires RetryExecutor into engine write path); independent of US2/US3
  - US5 (P2) depends on US1 (same engine + CAS re-apply machinery); independent of US2/US3/US4
- **Polish (Final Phase)**: Depends on all desired user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: After Foundational — no dependencies on other stories
- **User Story 2 (P1)**: After US1 (scheduler class delivered in US1); fairness tests may force scheduler fixes
- **User Story 3 (P1)**: After US1 (cursor claim/advance delivered in US1; adds resume + corrupt-cursor handling)
- **User Story 4 (P2)**: After US1; parallel with US2, US3, US5
- **User Story 5 (P2)**: After US1; parallel with US2, US3, US4

### Within Each User Story

- Tests written FIRST and FAIL before implementation (FR-072)
- Records/enums before ports; ports before fakes; fakes before tests; tests before implementation
- Core implementation before integration; story complete before moving to next priority

### Parallel Opportunities

- All Setup `[P]` tasks (T002, T003, T004) run in parallel — different files
- Most Foundational `[P]` tasks run in parallel (T005–T009, T011, T012, T014–T022 minus stated depends)
- US1 tests T024, T025, T026 all parallel before implementation starts
- US2 tests T031, T032 parallel
- US4 tests T036, T037 parallel
- After Foundational: US2, US3, US4, US5 test authoring can proceed in parallel; implementation order US1 -> {US2, US3} / {US4, US5}
- Polish T043, T044 parallel

---

## Parallel Example: User Story 1

```bash
# Launch all tests for User Story 1 together (FIRST):
Task: "Add find/CAS contract vectors to src/test/java/com/example/fanout/ports/IndexWriterContractTest.java"
Task: "Write src/test/java/com/example/fanout/fanout/FanOutEnginePublishTest.java"
Task: "Write src/test/java/com/example/fanout/fanout/FanOutEngineIdempotencyTest.java"

# Then implementation (sequential where dependent):
Task: "Implement src/main/java/com/example/fanout/fanout/FairShardScheduler.java"
Task: "Implement src/main/java/com/example/fanout/fanout/FanOutEngine.java publish path"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational (CRITICAL - blocks all stories)
3. Complete Phase 3: User Story 1 (tests first, then engine)
4. **STOP and VALIDATE**: `mvn verify` green; 1,000-row publish, zero downstream calls, idempotent replay
5. Deploy/demo if ready

### Incremental Delivery

1. Setup + Foundational -> foundation ready
2. Add US1 -> test independently -> Deploy/Demo (MVP!)
3. Add US2 -> fairness suite green at 40k scale -> Deploy/Demo
4. Add US3 -> crash-resume green -> Deploy/Demo
5. Add US4 -> retry/DLQ/permit gates green -> Deploy/Demo
6. Add US5 -> withdraw supersede green -> Deploy/Demo
7. Polish -> full quickstart validation -> hand-off

### Parallel Team Strategy

With multiple developers:

1. Team completes Setup + Foundational together
2. After Foundational: Developer A runs US1 end-to-end (MVP), Developer B authors US2/US3 tests, Developer C authors US4/US5 tests
3. After US1 lands: US2/US3 (engine mechanics) and US4/US5 (failure + withdraw paths) integrate independently

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Each user story is independently completable and testable
- Tests must fail before implementing (FR-072 test-first)
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
- Determinism rules (FR-072): latches, fake clock, seeded jitter, test-controlled runner — no `Thread.sleep`, no wall-clock waits
- Out of scope (spec): backfill, feature flags, real adapters, replay tooling, alert routing, UI, Spring wiring — do not build
