---

description: "Task list template for feature implementation"
---

# Tasks: Backfill, Halt, and Staged Enablement

**Input**: Design documents from `/specs/004-backfill-halt-staged-enablement/`

**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/, quickstart.md

**Tests**: INCLUDED — explicitly requested by the feature specification (FR-055: tests written first, deterministic — seeded RNG, latches, controllable clock, no sleep-based synchronization; FR-056: all tests run under the single verification command `mvn verify`).

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

Single project (hexagonal layout, per plan.md): `src/main/java/`, `src/test/java/` at repository root, package root `com/example/fanout/`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Confirm baseline and create package scaffolding for the new backfill code

- [X] T001 Run baseline `mvn verify` from repository root to confirm the pre-change build is green
- [X] T002 Create package directories src/main/java/com/example/fanout/backfill/ and src/test/java/com/example/fanout/backfill/ per plan.md Project Structure
- [X] T003 [P] Verify pom.xml gains zero new dependencies for this feature (plan.md constraint; `spring-boot-starter-test` only)

**Checkpoint**: Baseline green, structure ready

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core additive domain values, ports, control seam, and configuration that MUST be complete before ANY user story can be implemented

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [X] T004 [P] Create EnumeratedEngagement record in src/main/java/com/example/fanout/domain/EnumeratedEngagement.java — fields engagementId/firmId/productId each validated "non-blank"; identifiers only, never engagement content or a version (data-model.md)
- [X] T005 [P] Create EnumerationBatch record in src/main/java/com/example/fanout/domain/EnumerationBatch.java — invariants: items "size ≥ 1 (never empty — a done batch carries no items)", "ordered by engagementId ascending", nextCursor "empty iff `done`; else = last item's engagementId", "`done == false` ⇒ `items.size() <= limit` and `nextCursor` present", "`done == true` ⇒ `items` empty" (data-model.md)
- [X] T006 [P] Add BACKFILL_ENGAGEMENT, BACKFILL_BATCH, BACKFILL_CURSOR to DeadLetterUnitKind in src/main/java/com/example/fanout/domain/DeadLetterUnitKind.java (additive enum values, contracts/backfill-dlq-identity.md)
- [X] T007 [P] Add HALTED and GATED to FanOutOutcomeStatus in src/main/java/com/example/fanout/domain/FanOutOutcomeStatus.java — both re-invokable: re-invoking with the same trigger resumes from the cursor (data-model.md)
- [X] T008 [P] Create RetryPolicy record in src/main/java/com/example/fanout/fanout/RetryPolicy.java — validation "maxAttempts (≥ 1), baseDelay (> 0), maxDelay (≥ baseDelay), jitterFactorMin (≥ 0), jitterFactorMax (> jitterFactorMin)" (data-model.md, research D10)
- [X] T009 Add RetryPolicy constructor to RetryExecutor in src/main/java/com/example/fanout/fanout/RetryExecutor.java; the existing FanOutConfig constructor delegates to it — source-compatible, delivered tests unchanged (depends T008)
- [X] T010 [P] Create EngagementEnumerator port in src/main/java/com/example/fanout/ports/EngagementEnumerator.java — `EnumerationBatch nextBatch(String customerDatabaseId, Optional<String> afterEngagementId, int limit)`; null/blank database id or `limit < 1` rejected with IllegalArgumentException (contracts/engagement-enumerator.md)
- [X] T011 Create SeededEngagementEnumerator fake in src/test/java/com/example/fanout/fakes/SeededEngagementEnumerator.java — deterministic seed dataset, `failNextCalls(n)`, `failAlways()`, `failAfterBatches(k)`, restart from any cursor, call log of (databaseId, afterCursor, limit) (contracts/engagement-enumerator.md, depends T010)
- [X] T012 [P] Write AdmissionGateTest FIRST (expected red) in src/test/java/com/example/fanout/fanout/AdmissionGateTest.java — verdict precedence and rejection counters per contracts/admission-gates.md
- [X] T013 Create AdmissionGate in src/main/java/com/example/fanout/fanout/AdmissionGate.java — verdict precedence normative: "`haltSwitch()` true ⇒ `HALTED`; else product flag false ⇒ `GATED_PRODUCT`; else firm flag false ⇒ `GATED_FIRM`; else `ADMIT`"; constructor for fanout counters, static `forBackfill` for backfill counters; every non-ADMIT increments the matching `{fanout|backfill}.admit.reject.{halt,product,firm}` counter with identifier-only tags (depends T012)
- [X] T014 [P] Create CapacityShares record in src/main/java/com/example/fanout/backfill/CapacityShares.java — compact constructor "parentC >= 1, backfill >= 1, repair >= 0, backfill + repair <= parentC (else IllegalArgumentException at startup, FR-021)"; `backfillBudget()` and `repairBudget()` return two independent CapacityBudget instances, no shared wait queue (contracts/capacity-shares.md)
- [X] T015 [P] Create BackfillConfig record in src/main/java/com/example/fanout/backfill/BackfillConfig.java — defaults batchSize=100 (≥ 1), firmInFlightCap=1 (≥ 1), parentC=20 (≥ 1), bBackfill=18 (≥ 1), bRepair=2 (≥ 0), haltPollInterval=1 s (> 0), haltDrainBound=2 min (> 0), retryPolicy=RetryPolicy(5, 100 ms, 30 s, 0.5, 1.5); rejects "`bBackfill + bRepair ≤ parentC`" violation at construction (data-model.md, depends T008)
- [X] T016 Create AdmissionHaltedException in src/main/java/com/example/fanout/fanout/AdmissionHaltedException.java (unchecked, non-transient, never dead-lettered) and add injectable `BooleanSupplier haltProbe` overload to PermitGuardedDownstream in src/main/java/com/example/fanout/fanout/PermitGuardedDownstream.java — probe checked once before the first acquire and on each failed-acquire pause; throws while holding no permit; existing constructor delegates `() -> false` (contracts/capacity-shares.md, research D9)
- [X] T017 [P] Create BackfillDeadLetterIdentity codec in src/main/java/com/example/fanout/backfill/BackfillDeadLetterIdentity.java — encode/decode: BACKFILL_ENGAGEMENT `eventId` = `bf US firmId US batchIndex US engagementId` (chunkIndex ≥ 0), BACKFILL_BATCH omits trailing engagement segment, BACKFILL_CURSOR uses `chunkIndex = -1` with no engagement segment; decoding a non-`bf` eventId or malformed segment count throws IllegalStateException (contracts/backfill-dlq-identity.md, depends T006)

**Checkpoint**: Foundation ready — user story implementation can now begin in parallel

---

## Phase 3: User Story 1 - Backfill creates pending rows for pre-feature engagements (Priority: P1) 🎯 MVP

**Goal**: Incremental backfill enumerates engagements, captures versions under backfill-share permits, writes exactly one idempotent pending row per stale engagement, checkpoints per firm

**Independent Test**: enumerate a seeded fake of 5,000 engagements across 50 firms; run backfill to completion; assert exactly the expected row set, every row created exactly once, every downstream load under a permit, observed in-flight never exceeded the backfill share, and engagements already at the effective version produced no row and no failure (spec US1 Independent Test)

### Tests for User Story 1 ⚠️ (test-first per FR-055)

- [X] T018 [P] [US1] Contract test for EngagementEnumerator invariants 1–7 (order, stability, resumability, cursor, size, content prohibition, error handling) in src/test/java/com/example/fanout/ports/EngagementEnumeratorContractTest.java
- [X] T019 [P] [US1] BackfillWorkerTest (write FIRST, expect red) in src/test/java/com/example/fanout/backfill/BackfillWorkerTest.java — 5,000 seeded engagements / 50 firms via SeededEngagementEnumerator; assert exact expected row set, per-engagement create-count exactly 1 in InMemoryIndexWriter audit, in-flight loads ≤ configured backfill share at every sample, no-op (no row, no failure) when captured version equals effective to-version, zero rows but checkpointing for a product with no effective version (FR-006/FR-007, SC-008)
- [X] T020 [P] [US1] Extend RecordingFanOutMetrics in src/test/java/com/example/fanout/fakes/RecordingFanOutMetrics.java with backfill counters per FR-053: `backfill.batches.{admitted,completed}`, `backfill.engagements.{written,noop,dlq}`, `backfill.admit.reject.{halt,product,firm}`, `backfill.halt.abandoned`, `backfill.halt.drain` timer, `backfill.permit.park`, `backfill.downstream.inflight.hwm`, `fanout.dlq` tagged `unit_kind=BACKFILL_*`

### Implementation for User Story 1

- [X] T021 [US1] Create BackfillCursorRepository in src/main/java/com/example/fanout/backfill/BackfillCursorRepository.java — `CheckpointKind.BACKFILL_BATCH` one key per firm; value `status US lastResolvedEngagementId` (US = U+001F; status RUNNING|COMPLETE); operations read/advance/complete/startCursor; missing key = start of enumeration; corrupt value throws IllegalStateException; `startCursor` monotonic; COMPLETE never rewritten (contracts/backfill-checkpoint.md, research D2)
- [X] T022 [US1] Create BackfillOutcome record in src/main/java/com/example/fanout/backfill/BackfillOutcome.java — BackfillStatus `COMPLETED`, `HALTED`, `FAILED_DEAD_LETTERED`; fields firmsRemaining, engagementsResolved, rowsWritten, noOps, deadLettered (data-model.md)
- [X] T023 [US1] Implement BackfillWorker pipeline in src/main/java/com/example/fanout/backfill/BackfillWorker.java — per engagement in order: AdmissionGate check; permit from the backfill-share budget via PermitGuardedDownstream (the ONLY downstream call path) with halt probe; capture captured-version string; compare by string equality to `EffectiveVersion.selectEffective(FanOutCursorRepository.ingestedPublishes(productId))` minus `withdrawnVersions(productId)` — equal or no effective version ⇒ NOOP; else upsert PENDING row via IndexWriter per data-model.md backfill recipe (currentVersion = captured, offeredVersion = effective to-version, publishIds = {effective publish id}, rowVersion 1); release permit exactly once in finally; per firm: enumerate batches from cursor, resolve every engagement, advance cursor only after the whole batch resolves; dedicated `TaskRunner.fixedPool(bBackfill)`; BackfillOutcome.run() entry point (depends T021, T022, T014, T015, T013, T009, T011)
- [X] T024 [US1] Wire backfill dead-letter path in src/main/java/com/example/fanout/backfill/BackfillWorker.java — retry exhaustion stores DeadLetterRecord encoded by BackfillDeadLetterIdentity with full identifier-only work identity and increments `fanout.dlq` (unit_kind=BACKFILL_*); a dead-lettered BACKFILL_ENGAGEMENT counts as resolved so its batch still checkpoints; a dead-lettered BACKFILL_BATCH does NOT advance the cursor; corrupt cursor ⇒ BACKFILL_CURSOR dead-letter + alert, no writes (depends T023, T017, T006)

**Checkpoint**: At this point, User Story 1 should be fully functional and testable independently — `mvn verify` green for US1 tests

---

## Phase 4: User Story 2 - Interruption and restart never duplicate or drop rows (Priority: P1)

**Goal**: Random kills at arbitrary points followed by restart always yield the fault-free row set — exactly N rows, each created once

**Independent Test**: run backfill over N = 3,000 engagements with a seeded RNG that kills the run k ∈ [1, 7] times at random admission/load/write/checkpoint points; after the final restart assert exactly N rows, per-engagement create-count exactly one, InMemoryIndexWriter audit log shows no other mutation, per-firm checkpoints complete (spec US2 Independent Test)

> **Note**: this story builds on the US1 worker; write the crash test FIRST and let it drive resume fixes

### Tests for User Story 2 (test-first)

- [X] T025 [P] [US2] Create SimulatedProcessDeath fake in src/test/java/com/example/fanout/fakes/SimulatedProcessDeath.java — non-transient RuntimeException subclass that RetryExecutor does not catch (research D12)
- [X] T026 [P] [US2] BackfillWorkerCrashResumeTest (write FIRST, expect red) in src/test/java/com/example/fanout/backfill/BackfillWorkerCrashResumeTest.java — seeded RNG kill points (after k-th batch completion plus mid-batch at admission/load/write/checkpoint), k ∈ [1, 7] over a fixed seed table, N = 3,000; restart = new BackfillWorker over the same InMemoryCheckpointStore + InMemoryIndexWriter; assert final row set byte-identical to fault-free run, per-engagement create-count == 1, no other mutations in the writer audit, per-firm checkpoints complete; includes the kill-between-last-write-and-cursor-advance case asserting duplicate-skips only (SC-001, SC-003, depends T025)

### Implementation for User Story 2

- [X] T027 [US2] Fix resume gaps in src/main/java/com/example/fanout/backfill/BackfillWorker.java exposed by the crash test — resume from first incomplete batch per firm, at most one re-executed batch per firm, idempotent writes absorb redundant loads, cursor advances only after every engagement in the batch reached a terminal outcome (depends T026)

**Checkpoint**: User Stories 1 AND 2 both work — crash/restart property green

---

## Phase 5: User Story 3 - Halt stops all admission within minutes; resume is clean (Priority: P1)

**Goal**: Global halt flag observed at every admission point on both paths; backfill yields first; drain completes within the halt-drain bound; resume from persisted checkpoints is exact-once

**Independent Test**: saturate backfill with blocked fake loads; set the halt flag; assert zero new admissions after observation on the controllable clock, all in-flight loads complete, completed-batch checkpoints persisted, all permits released, drain wall-time within the configured bound; clear the flag, restart, assert the run completes with the exact fault-free row set (spec US3 Independent Test)

### Tests for User Story 3 (test-first)

- [X] T028 [P] [US3] BackfillHaltTest (write FIRST, expect red) in src/test/java/com/example/fanout/backfill/BackfillHaltTest.java — LatchDownstreamClient saturates B_backfill; set FakeFeatureFlags halt; assert zero new batch/engagement/permit admissions after observation, drain completes within `haltDrainBound` (~2 min default), `budget.available() == capacity` (all permits released), completed-batch checkpoints persisted; clear flag, re-run, assert fault-free row set (SC-002, SC-003, FR-030–FR-034)
- [X] T029 [P] [US3] FanOutEngineHaltTest (write FIRST, expect red) in src/test/java/com/example/fanout/fanout/FanOutEngineHaltTest.java — halt set mid-publish with pending chunks ⇒ outcome `HALTED`, cursor persists the completed prefix, no trigger dropped; clear halt, re-invoke same trigger ⇒ publish completes (spec US3 acceptance 5, SC-002)

### Implementation for User Story 3

- [X] T030 [US3] Add optional admission predicate and `SchedulerResult.AWAITING_ADMISSION` to FairShardScheduler in src/main/java/com/example/fanout/fanout/FairShardScheduler.java — existing two-arg constructor delegates with `task -> true` (delivered tests unchanged); a firm whose pending head chunk is non-ADMIT is skipped for the scan (same treatment as at-cap: skipped, never queued ahead); `runAll` terminates when nothing is in flight and no pending head is ADMIT (contracts/admission-gates.md, research D8)
- [X] T031 [US3] Wire AdmissionGate into FanOutEngine in src/main/java/com/example/fanout/fanout/FanOutEngine.java — live haltSwitch check at run and chunk admission; all chunks done ⇒ COMPLETED (unchanged); halted with pending chunks ⇒ outcome `HALTED`; rejection counters emitted (depends T030, T013)
- [X] T032 [US3] Implement backfill halt protocol in src/main/java/com/example/fanout/backfill/BackfillWorker.java — live halt check at run, batch, and engagement admission; halt ⇒ outcome HALTED, no further admissions; blocked permit waiters abandon via the PermitGuardedDownstream haltProbe, count `backfill.halt.abandoned`, hold nothing; drain wall-time measured and emitted as `backfill.halt.drain` timer; in-flight loads drain under normal rules (depends T028)

**Checkpoint**: Halt works on both paths with clean resume

---

## Phase 6: User Story 4 - Real-time publishes never queue behind backfill (Priority: P1)

**Goal**: Disjoint pools and disjoint permit shares — a saturated backfill share cannot delay the publish hot path (p99 < 60 s preserved)

**Independent Test**: block fake downstream loads until all backfill-share permits are held; run a publish at ~20,000-engagement scale; assert it completes inside the freshness budget, acquires zero permits, makes zero downstream calls; concurrently a repair-share waiter acquires a repair-share permit while every backfill permit is held; assert parent-C accounting never exceeds C (spec US4 Independent Test)

### Tests for User Story 4 (test-first)

- [X] T033 [P] [US4] CapacitySharesTest (write FIRST, expect red) in src/test/java/com/example/fanout/backfill/CapacitySharesTest.java — startup rejects `bBackfill + bRepair > parentC` with IllegalArgumentException (FR-021); repair waiter acquires from B_repair while all B_backfill permits are held (FR-022); in-flight per share ≤ share and total ≤ parentC at every observation (SC-005); B_repair = 0 ⇒ repair-path caller fails fast with a distinct counter while backfill and fan-out are unaffected; documented pacing arithmetic 800,000 × 1 min / 20 ≈ 27.8 days, / 18 ≈ 30.9 days (SC-008)
- [X] T034 [P] [US4] RealTimeBackfillIndependenceTest (write FIRST, expect red) in src/test/java/com/example/fanout/fanout/RealTimeBackfillIndependenceTest.java — LatchDownstreamClient holds all B_backfill permits; run ~20,000-engagement publish on the fan-out TaskRunner; assert COMPLETED inside the freshness budget on FakeClock, fake downstream call log contains only the blocked backfill calls (zero attributable to the publish), zero permit acquisitions on the hot path, fan-out throughput unchanged vs backfill-free baseline (SC-004, FR-023, FR-024)

### Implementation for User Story 4

- [X] T035 [US4] Enforce structural independence in src/main/java/com/example/fanout/backfill/BackfillWorker.java and wiring — worker constructed only with `CapacityShares.backfillBudget()` and a dedicated `TaskRunner.fixedPool(bBackfill)`; no dependency on the fan-out pool or repair-share budget; `FanOutEngine` gains no PermitGuardedDownstream/budget dependency (asserted by construction plus the T034 call-log assertions) (depends T033, T023)

**Checkpoint**: Real-time independence proven — US1–US4 all green

---

## Phase 7: User Story 5 - Fair enumeration: the giant firm cannot monopolize backfill (Priority: P2)

**Goal**: Round-robin per-firm batch admission with a per-firm in-flight cap — median firms complete while the 40,000-engagement firm is still early in its backlog

**Independent Test**: fake one 40,000-engagement firm plus 500 median-40 firms; run backfill with a small share; assert at every observation no firm exceeds its in-flight engagement cap and every median firm completes while the giant firm is still incomplete (spec US5 Independent Test)

### Tests for User Story 5 (test-first)

- [X] T036 [P] [US5] BackfillFairnessTest (write FIRST, expect red) in src/test/java/com/example/fanout/backfill/BackfillFairnessTest.java — distribution: one 40,000-engagement firm + 500 median-40 firms, small bBackfill; assert at every observation point no firm exceeds its in-flight engagement cap, each cycle admits at most one batch per firm in rotating firm-id order, and 100% of median firms reach a complete checkpoint while the giant firm's checkpoint is incomplete (FR-010–FR-012, SC-006)

### Implementation for User Story 5

- [X] T037 [US5] Create BackfillScheduler in src/main/java/com/example/fanout/backfill/BackfillScheduler.java — demand-driven round-robin over per-firm batch streams (NOT a pre-materialized chunk list): per-firm next cursor + completion flag, active-firm set with rotating scan cursor, one `Semaphore(firmInFlightCap)` per firm, batch admission gated on `AdmissionGate` forBackfill + per-firm permit + non-blocking budget tryAcquire; each cycle every firm with remaining work contributes at most one batch, in firm-id order, firms rotate; a firm at its cap is skipped for the cycle, never queued ahead (contracts/backfill-worker.md, research D7)
- [X] T038 [US5] Integrate BackfillWorker with BackfillScheduler in src/main/java/com/example/fanout/backfill/BackfillWorker.java — worker delegates batch admission to the scheduler; engagements execute sequentially on the batch's pool thread (each with its own permit acquire/release); per-firm in-flight engagements ≤ firmInFlightCap by construction (depends T037, T036)

**Checkpoint**: Fairness holds at the stated distribution without regressing US1–US4

---

## Phase 8: User Story 6 - Staged enablement per product and per firm (Priority: P2)

**Goal**: Per-product and per-firm flags gate admission on both paths, live-read at every admission decision, no redeploy; gated work stays behind the cursor and resumes exactly-once

**Independent Test**: run a publish spanning many firms; flip one firm's flag off at chunk m; assert that firm admits no further chunks while others complete; flip back on; assert remaining chunks admitted from the cursor and the final row set is exactly-once complete; repeat on the backfill path with a per-firm and a per-product flag (spec US6 Independent Test)

### Tests for User Story 6 (test-first)

- [X] T039 [P] [US6] FanOutEngineFlagFlipTest (write FIRST, expect red) in src/test/java/com/example/fanout/fanout/FanOutEngineFlagFlipTest.java — multi-firm publish; flip one firm's flag off mid-run via FakeFeatureFlags; assert that firm admits nothing further within one admission check (rejection counters carry the reason), other firms complete, no failure recorded for gated chunks, outcome `GATED`; flip on, re-invoke same trigger ⇒ admitted from the persisted publish cursor, final row set exact-once complete (SC-007, FR-041–FR-044)
- [X] T040 [P] [US6] BackfillFlagFlipTest (write FIRST, expect red) in src/test/java/com/example/fanout/backfill/BackfillFlagFlipTest.java — flip a per-firm flag off mid-backfill: admission of that firm's remaining batches/engagements stops at the next admission check, other firms continue unaffected, gated work is not dead-lettered and not buffered (flag stays off across many cycles ⇒ no unbounded queue; work re-derived from cursor); flip a per-product flag off with the same assertions; re-enable ⇒ resume from checkpoints, exact-once completion (SC-007, FR-040–FR-043)

### Implementation for User Story 6

- [X] T041 [US6] Map GATED outcome in src/main/java/com/example/fanout/fanout/FanOutEngine.java — pending chunks + product/firm gate (haltSwitch false) ⇒ outcome `GATED`; cursor untouched (contiguous-prefix advance already persisted the completed prefix); re-invoking the same trigger resumes via the delivered claim/resume path; deterministic admission decisions matching flag state observed at decision time (depends T039, T031)
- [X] T042 [US6] Engagement-level gate re-check in src/main/java/com/example/fanout/backfill/BackfillWorker.java — AdmissionGate consulted before each permit acquisition inside a batch; GATED ⇒ engagement is not admitted, not dead-lettered, not buffered — it stays behind the batch cursor and is re-derived when the gate opens; firm/product skip leaves other firms unaffected; every non-ADMIT verdict increments the backfill rejection counter with reason (depends T040, T038)

**Checkpoint**: All user stories independently functional

---

## Phase 9: Polish & Cross-Cutting Concerns

**Purpose**: Whole-suite verification, self-containment, and cleanup

- [X] T043 Run the full suite with `mvn verify` from repository root — all phase-4 tests green under the single verification command (FR-056)
- [X] T044 [P] Self-containment and privacy review per SC-010 across src/main/java/com/example/fanout/ and src/test/java/com/example/fanout/ — zero references to external documents, zero engagement content in any log/metric/DLQ field/checkpoint payload, no sleep-based synchronization anywhere (FR-054, FR-055)
- [X] T045 [P] Verify pom.xml still has zero new dependencies and specs/004-backfill-halt-staged-enablement/ has zero external-document references (`git grep`) per quickstart.md "What done looks like"
- [X] T046 Run quickstart.md validation scenarios 1–9 in specs/004-backfill-halt-staged-enablement/quickstart.md and confirm every mapped test class passes
- [X] T047 [P] Java import convention review (constitution IX) across all new/modified files — simple names, explicit imports, no wildcards, no fully qualified inline type names
- [X] T048 Code cleanup — remove dead code, confirm all delivered-port changes are additive and source-compatible (existing constructors/tests unchanged)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion — BLOCKS all user stories
- **User Story 1 (Phase 3)**: Depends on Foundational
- **User Story 2 (Phase 4)**: Depends on US1 (crash/resume test runs the US1 worker)
- **User Story 3 (Phase 5)**: Depends on Foundational; backfill halt tasks build on US1 worker; fan-out halt tasks (T030, T031) only need Foundational
- **User Story 4 (Phase 6)**: Depends on Foundational; T035 touches US1 worker, so run after US1 (independence tests themselves only need Foundational)
- **User Story 5 (Phase 7)**: Depends on US1 (schedules US1's batches) — T036 test can be written any time after Foundational
- **User Story 6 (Phase 8)**: Fan-out side (T039, T041) depends on T030/T031 (US3); backfill side (T040, T042) depends on US1/US5 worker
- **Polish (Phase 9)**: Depends on all desired user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Start after Foundational — no dependencies on other stories
- **User Story 2 (P1)**: Builds on US1 worker (independent test exercises its crash/resume behavior)
- **User Story 3 (P1)**: Fan-out halt independent of US1; backfill halt needs US1 worker
- **User Story 4 (P1)**: Tests need Foundational only; wiring check touches US1 worker
- **User Story 5 (P2)**: Needs US1 worker + Foundational scheduler inputs
- **User Story 6 (P2)**: Fan-out side needs US3 wiring; backfill side needs US1 + US5

### Within Each User Story

- Tests written FIRST and FAIL before implementation (FR-055)
- Domain records before services; services before worker wiring
- Core implementation before integration
- Story complete before moving to the next priority story

### Parallel Opportunities

- Setup: T003 parallel with T001/T002
- Foundational: T004–T008, T010, T012, T014, T015, T017 all touch different files — full parallel after T001
- US1: T018, T019, T020 parallel (different test files); T021/T022 parallel after
- US2: T025, T026 parallel
- US3: T028, T029 parallel; fan-out tasks T030/T031 parallel with backfill task T032
- US4: T033, T034 fully parallel
- US5: T036 parallel with all other story work
- US6: T039, T040 fully parallel
- After Foundational: US1, US3 fan-out side, US4 tests, US5 test-writing can all proceed concurrently

---

## Parallel Example: User Story 1

```bash
# Launch all tests for User Story 1 together (test-first):
Task: "Contract test for EngagementEnumerator in src/test/java/com/example/fanout/ports/EngagementEnumeratorContractTest.java"
Task: "BackfillWorkerTest in src/test/java/com/example/fanout/backfill/BackfillWorkerTest.java"
Task: "Extend RecordingFanOutMetrics in src/test/java/com/example/fanout/fakes/RecordingFanOutMetrics.java"

# Then implementation (T021, T022 parallel — different files):
Task: "BackfillCursorRepository in src/main/java/com/example/fanout/backfill/BackfillCursorRepository.java"
Task: "BackfillOutcome in src/main/java/com/example/fanout/backfill/BackfillOutcome.java"
```

## Parallel Example: Foundational

```bash
Task: "EnumeratedEngagement in src/main/java/com/example/fanout/domain/EnumeratedEngagement.java"
Task: "EnumerationBatch in src/main/java/com/example/fanout/domain/EnumerationBatch.java"
Task: "DeadLetterUnitKind additive values in src/main/java/com/example/fanout/domain/DeadLetterUnitKind.java"
Task: "RetryPolicy in src/main/java/com/example/fanout/fanout/RetryPolicy.java"
Task: "EngagementEnumerator port in src/main/java/com/example/fanout/ports/EngagementEnumerator.java"
Task: "CapacityShares in src/main/java/com/example/fanout/backfill/CapacityShares.java"
Task: "BackfillConfig in src/main/java/com/example/fanout/backfill/BackfillConfig.java"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational (CRITICAL — blocks all stories)
3. Complete Phase 3: User Story 1
4. **STOP and VALIDATE**: `mvn verify` — test US1 independently (row set exact, create-count 1, share bound held)
5. Deploy/demo if ready

### Incremental Delivery

1. Setup + Foundational → foundation ready
2. Add User Story 1 → Test independently → Deploy/Demo (MVP!)
3. Add User Story 2 → crash property green → Deploy/Demo (safe to interrupt)
4. Add User Story 3 → halt green → Deploy/Demo (operator safety lever live)
5. Add User Story 4 → independence green → Deploy/Demo (SLO protected)
6. Add User Story 5 → fairness green → Deploy/Demo
7. Add User Story 6 → staged rollout possible → final

### Parallel Team Strategy

With multiple developers:

1. Team completes Setup + Foundational together
2. Once Foundational is done:
   - Developer A: User Story 1 (P1 MVP)
   - Developer B: US3 fan-out halt side (T030, T031, T029) + US4 tests (T033, T034)
   - Developer C: US5/US6 tests (T036, T039, T040) — written red, ready to drive later implementation
3. Stories complete and integrate in priority order

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Each user story is independently completable and testable
- Verify tests fail before implementing (FR-055)
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
- Avoid: vague tasks, same-file conflicts, cross-story dependencies that break independence
- Flag defaults per research D6: opt-in for BOTH paths — tests seed FakeFeatureFlags with live products/firms; backfill requires explicit per-firm and per-product enablement
- All new log lines, metric tags, dead-letter fields, and checkpoint payloads carry identifiers and measurements only (FR-054, constitution V)
