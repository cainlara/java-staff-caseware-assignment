# Tasks: Fan-Out Worker Scaffold & Contracts

**Input**: Design documents from `/specs/001-fanout-worker-scaffold/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: Included — the feature specification explicitly requires contract tests
and validation tests (spec FR-031, FR-032, FR-033; US1/US2/US3 independent-test
criteria). Tests are written before the implementation they pin.

**Organization**: Tasks grouped by user story (US1 = verified foundation build,
US2 = validated immutable data contracts, US3 = boundary interfaces with pinned
idempotency contracts) so each story is independently completable and testable.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

Single Maven module (artifactId `fanout-worker`) rooted at the repository root
(per plan.md Structure Decision). Main sources under
`src/main/java/com/example/fanout/`, tests under
`src/test/java/com/example/fanout/`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project initialization and build skeleton

- [X] T001 Create `pom.xml` per plan.md: parent
  `spring-boot-starter-parent` 3.5.x, `<java.version>21</java.version>`,
  artifactId `fanout-worker`, groupId `com.example`, dependency
  `spring-boot-starter-test` (test scope). No other dependencies.
- [X] T002 [P] Create main package directories
  `src/main/java/com/example/fanout/{domain,ports,adapters/inmemory}/`
  and `src/main/resources/`
- [X] T003 [P] Create test package directories
  `src/test/java/com/example/fanout/{domain,ports,fakes}/`
  and `src/test/resources/`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Prove the build skeleton works before any story work

**⚠️ CRITICAL**: No user story work can begin until this phase completes

- [X] T004 Run `mvn -q verify` at repository root — must report BUILD SUCCESS
  with zero tests (baseline green; no external services contacted)

**Checkpoint**: Build skeleton verified — user story implementation can begin

---

## Phase 3: User Story 1 - Verified foundation build (Priority: P1) 🎯 MVP

**Goal**: A clean checkout builds and verifies with zero failures, is
self-contained, and contains no out-of-scope behavior — the entry ticket for all
later work (spec US1, FR-001, FR-003).

**Independent Test**: Run `mvn verify` at repository root on a clean machine;
completes with zero failures, no external systems required; grep checks confirm
self-containment and no out-of-scope behavior.

### Implementation for User Story 1

- [X] T005 [US1] Run full verification `mvn verify` at repository root and
  confirm BUILD SUCCESS with zero failures as the US1 baseline (FR-001, SC-001)
- [X] T006 [US1] Run self-containment check per quickstart.md
  (`grep -rniE 'TODO: see (the )?(spec|doc|pdf)|/docs/|sharepoint|intake doc' src pom.xml`
  at repository root) — expect `self-contained: OK` (FR-003, SC-008)
- [X] T007 [US1] Run out-of-scope leak check per quickstart.md
  (`grep -rniE 'backfill.*implement|@Scheduled|RetryTemplate|RateLimiter|KafkaListener|RabbitListener' src`
  at repository root) — expect `no leaks: OK` (spec Out of Scope)
- [X] T008 [US1] Create `README.md`: one paragraph naming the
  component (template-publish fan-out worker scaffold), scope (contracts only,
  no fan-out behavior), and the verification command `mvn verify`

**Checkpoint**: US1 independently satisfied — verified, self-contained,
leak-free skeleton

---

## Phase 4: User Story 2 - Validated immutable data contracts (Priority: P2)

**Goal**: Six immutable records with construction-time validation: null/blank
natural keys rejected, collection fields defensively copied, pending rows carry
metadata identifiers only (spec US2, FR-010–FR-018, data-model.md).

**Independent Test**: Domain test suite (`src/test/java/com/example/fanout/domain/`)
passes: every invalid natural-key input throws at construction; valid records
are unchangeable and immune to external collection mutation.

### Tests for User Story 2 ⚠️ (write FIRST — must fail before implementation)

- [X] T009 [P] [US2] Create `TemplatePublishedTest.java` in
  `src/test/java/com/example/fanout/domain/`: null/blank
  `publishId`, `productId`, `fromVersion`, `toVersion` and null `publishedAt`
  each rejected with IllegalArgumentException; valid instance constructs
- [X] T010 [P] [US2] Create `TemplateWithdrawnTest.java` in
  `src/test/java/com/example/fanout/domain/`: null/blank
  `withdrawId`, `productId`, `version` and null `withdrawAt` rejected
- [X] T011 [P] [US2] Create `EngagementRefTest.java` in
  `src/test/java/com/example/fanout/domain/`: null/blank
  `engagementId`, `firmId`, `productId`, `currentVersion`, `region` rejected
- [X] T012 [P] [US2] Create `PendingIndexRowTest.java` in
  `src/test/java/com/example/fanout/domain/`: null/blank
  `engagementId`, `firmId`, `productId`, `currentVersion`, `offeredVersion`
  rejected; null `state` rejected; null `publishIds` rejected; empty
  `publishIds` rejected ("mandatory, non-null, non-empty"); blank element in
  `publishIds` or `pinnedRanges` rejected; `rowVersion < 1` rejected; null
  `updatedAt` rejected; null `decision` and null `summaryKey` ALLOWED; input
  collections mutated after construction do not change stored fields
  (defensive copy); returned collections unmodifiable
- [X] T013 [P] [US2] Create `FanOutTaskTest.java` in
  `src/test/java/com/example/fanout/domain/`: null/blank
  `publishId`, `batchId` rejected; `batchIndex < 0` rejected; `batchSize < 1`
  rejected
- [X] T014 [P] [US2] Create `PublishOutcomeTest.java` in
  `src/test/java/com/example/fanout/domain/`: null/blank
  `publishId` rejected; `rowsWritten`/`rowsSkipped`/`failures` < 0 rejected;
  null `status` rejected

### Implementation for User Story 2

- [X] T015 [P] [US2] Create immutable record `TemplatePublished.java` in
  `src/main/java/com/example/fanout/domain/` with compact
  constructor validation (fields and rules per data-model.md "TemplatePublished")
- [X] T016 [P] [US2] Create immutable record `TemplateWithdrawn.java` in
  `src/main/java/com/example/fanout/domain/` (rules per
  data-model.md "TemplateWithdrawn")
- [X] T017 [P] [US2] Create immutable record `EngagementRef.java` in
  `src/main/java/com/example/fanout/domain/` (rules per
  data-model.md "EngagementRef"; `region` opaque non-blank id)
- [X] T018 [P] [US2] Create enum `PendingIndexState.java` in
  `src/main/java/com/example/fanout/domain/` with constants
  `PENDING, APPLIED, DECLINED, CREATED`
- [X] T019 [US2] Create immutable record `PendingIndexRow.java` in
  `src/main/java/com/example/fanout/domain/` (depends on T018):
  fields exactly per data-model.md; `publishIds` stored via `Set.copyOf`,
  `pinnedRanges` via `List.copyOf`; metadata identifiers only — no
  content-typed fields (FR-016)
- [X] T020 [P] [US2] Create immutable record `FanOutTask.java` in
  `src/main/java/com/example/fanout/domain/` (rules per
  data-model.md "FanOutTask")
- [X] T021 [P] [US2] Create enum `PublishStatus.java` in
  `src/main/java/com/example/fanout/domain/` with constants
  `IN_PROGRESS, COMPLETED, PARTIAL, FAILED`
- [X] T022 [US2] Create immutable record `PublishOutcome.java` in
  `src/main/java/com/example/fanout/domain/` (depends on T021;
  rules per data-model.md "PublishOutcome")

**Checkpoint**: US2 independently satisfied — domain suite green; records
reject all invalid natural keys (SC-002)

---

## Phase 5: User Story 3 - Boundary interfaces with pinned idempotency contracts (Priority: P3)

**Goal**: Seven port interfaces with Javadoc idempotency contracts, in-memory/
fake implementations of every port, and contract tests pinning each port's
idempotency key and validation behavior (spec US3, FR-020–FR-033, contracts/*).

**Independent Test**: Port contract suite
(`src/test/java/com/example/fanout/ports/`) passes with no real
infrastructure: duplicate index write is a no-op, stale CAS rejected,
checkpoints readable across a simulated restart, capacity never exceeds C,
downstream fake honors latency/failure config, metrics no-op and content-free.

### Tests for User Story 3 ⚠️ (write FIRST — must fail before implementation)

- [X] T023 [P] [US3] Create `IndexWriterContractTest.java` in
  `src/test/java/com/example/fanout/ports/` per
  contracts/index-writer.md: duplicate identical write -> second result
  `SKIPPED_DUPLICATE` and stored state identical to single-write state;
  stale `rowVersion` -> `REJECTED_STALE` and stored state unchanged; rows with
  same `(engagementId, offeredVersion, state)` target same logical row
- [X] T024 [P] [US3] Create `CheckpointStoreContractTest.java` in
  `src/test/java/com/example/fanout/ports/` per
  contracts/checkpoint-store.md: write-then-read round trip; never-written key
  -> `Optional.empty()` distinguishable from stored `""`; PUBLISH and
  BACKFILL_BATCH keys with same id do not collide; simulated restart (fresh
  store instance over same backing map) returns prior value unchanged;
  overwrite -> latest value wins
- [X] T025 [P] [US3] Create `CapacityBudgetContractTest.java` in
  `src/test/java/com/example/fanout/ports/` per
  contracts/capacity-budget.md: C=3 -> three acquires succeed, fourth
  `tryAcquire()` false, `available()` 0; release then acquire succeeds;
  `release()` without held permit throws; concurrent hammer never exceeds C;
  non-default C respected (C=1, C=50)
- [X] T026 [P] [US3] Create `DownstreamLoadClientContractTest.java` in
  `src/test/java/com/example/fanout/ports/` per
  contracts/downstream-load-client.md: fake with small configured latency
  honors it (elapsed-time bounds); fake default simulated latency ~60 s
  asserted on configuration WITHOUT sleeping; injected failure throws then
  succeeds after reset; null `EngagementRef` rejected
- [X] T027 [P] [US3] Create `FeatureFlagsContractTest.java` in
  `src/test/java/com/example/fanout/ports/` per
  contracts/feature-flags.md: unknown product id -> false; enable/disable flip
  without redeploy; firm gate independent of product gate; halt switch
  toggle observable; null/blank id rejected
- [X] T028 [P] [US3] Create `FanOutMetricsContractTest.java` in
  `src/test/java/com/example/fanout/ports/` per
  contracts/fanout-metrics.md: no-op impl accepts valid calls and records
  nothing; null/blank name, null duration, negative delta rejected; recording
  fake captures names/tags for assertions
- [X] T029 [P] [US3] Create `EventSourceContractTest.java` in
  `src/test/java/com/example/fanout/ports/` per
  contracts/event-source.md: empty poll -> `Optional.empty()` without throw;
  duplicate events carry equal natural keys (dedup on publishId/withdrawId
  possible)

### Implementation for User Story 3

- [X] T030 [P] [US3] Create interface `EventSource.java` in
  `src/main/java/com/example/fanout/ports/` with methods
  `Optional<TemplatePublished> pollPublish()` and
  `Optional<TemplateWithdrawn> pollWithdraw()`; Javadoc: at-least-once
  delivery, idempotency key `publishId`/`withdrawId` scoped by `productId`
- [X] T031 [P] [US3] Create enum `WriteResult.java` in
  `src/main/java/com/example/fanout/ports/` with constants
  `WRITTEN, SKIPPED_DUPLICATE, REJECTED_STALE`
- [X] T032 [US3] Create interface `IndexWriter.java` in
  `src/main/java/com/example/fanout/ports/` (depends on T031):
  `WriteResult upsert(PendingIndexRow row)`; Javadoc: idempotent on
  `(engagementId, offeredVersion, state)`, CAS on `rowVersion`, hot path only,
  no engagement content (FR-021, FR-027, FR-041)
- [X] T033 [P] [US3] Create interface `DownstreamLoadClient.java` in
  `src/main/java/com/example/fanout/ports/`:
  `String loadEngagement(EngagementRef ref)`; Javadoc: off hot path only,
  ~1 min real latency, requires `CapacityBudget` permit, read-only
- [X] T034 [P] [US3] Create record `CheckpointKey.java` and enum
  `CheckpointKind.java` (`PUBLISH, BACKFILL_BATCH`) in
  `src/main/java/com/example/fanout/ports/`: key =
  `(kind, id)`, id mandatory non-blank
- [X] T035 [US3] Create interface `CheckpointStore.java` in
  `src/main/java/com/example/fanout/ports/` (depends on T034):
  `Optional<String> read(CheckpointKey key)`, `void write(CheckpointKey key,
  String value)`; Javadoc: must survive process restart; missing key =
  `Optional.empty()` distinct from stored empty string; last-write-wins per key
- [X] T036 [US3] Create `InMemoryCheckpointStore.java` in
  `src/main/java/com/example/fanout/adapters/inmemory/`
  (depends on T035): `ConcurrentHashMap`-backed; exposes backing-map access
  (package-visible or constructor injection of map) so contract tests can
  simulate restart by constructing a new instance over the same map; Javadoc
  notes durability NOT provided by this placeholder (FR-023)
- [X] T037 [P] [US3] Create interface `CapacityBudget.java` in
  `src/main/java/com/example/fanout/ports/`:
  `boolean tryAcquire()`, `void release()`, `int available()`; Javadoc: max
  in-flight C default 20 configurable; release without permit = illegal state;
  off-hot-path loads only (FR-024)
- [X] T038 [P] [US3] Create interface `FeatureFlags.java` in
  `src/main/java/com/example/fanout/ports/`:
  `boolean enabled(String productId)`, `boolean enabledFirm(String firmId)`,
  `boolean haltSwitch()`; Javadoc: unknown ids default false; halt dominates
- [X] T039 [P] [US3] Create interface `FanOutMetrics.java` in
  `src/main/java/com/example/fanout/ports/`:
  `void increment(String name, long delta, String... tags)`,
  `void recordTime(String name, Duration duration, String... tags)`;
  Javadoc: engagement content NEVER permitted in name/tags/delta/duration
  (FR-026, FR-040)
- [X] T040 [P] [US3] Create `InMemoryIndexWriter.java` in
  `src/test/java/com/example/fanout/fakes/` (depends on T032):
  `ConcurrentHashMap` keyed by `(engagementId, offeredVersion, state)`;
  `compute`-based CAS — stale incoming `rowVersion` -> `REJECTED_STALE`,
  identical effective state -> `SKIPPED_DUPLICATE`, else `WRITTEN`
  with incremented version
- [X] T041 [P] [US3] Create `FakeDownstreamLoadClient.java` in
  `src/test/java/com/example/fanout/fakes/` (depends on T033):
  configurable `Duration latency` (default 60 s simulated), configurable
  failure injection (fail-next-N or predicate), returns non-blank version id
- [X] T042 [P] [US3] Create `FakeEventSource.java` in
  `src/test/java/com/example/fanout/fakes/` (depends on T030):
  scriptable queues of `TemplatePublished`/`TemplateWithdrawn`, supports
  duplicate enqueue, empty poll -> `Optional.empty()`
- [X] T043 [P] [US3] Create `FakeCapacityBudget.java` in
  `src/test/java/com/example/fanout/fakes/` (depends on T037):
  semaphore-style impl with configurable C (default 20); `release()` without
  permit throws
- [X] T044 [P] [US3] Create `FakeFeatureFlags.java` in
  `src/test/java/com/example/fanout/fakes/` (depends on T038):
  mutable maps for product/firm gates, default false, halt toggle
- [X] T045 [P] [US3] Create `RecordingFanOutMetrics.java` in
  `src/test/java/com/example/fanout/fakes/` (depends on T039):
  captures (name, delta, tags) and (name, duration, tags) tuples for
  assertion

**Checkpoint**: US3 independently satisfied — every port has a contract test
pinned against its fake (SC-003, SC-004, SC-005, SC-007)

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Whole-scaffold verification and cross-story invariants

- [X] T046 Run full `mvn verify` at repository root — all domain + contract
  tests green, zero failures (SC-001)
- [X] T047 [P] Audit Javadoc of every interface in
  `src/main/java/com/example/fanout/ports/` against
  `specs/001-fanout-worker-scaffold/contracts/*.md`: each documents
  idempotency key, consistency expectation, and hot-path rules (FR-027)
- [X] T048 [P] Re-run quickstart.md self-containment and out-of-scope leak
  greps at repository root — both clean (SC-006, SC-008)
- [X] T049 [P] Confirm `PendingIndexRow.java` fields are metadata identifiers
  only (no content-typed fields) and `FanOutMetrics` Javadoc carries the
  no-engagement-content invariant (FR-016, FR-040)
- [X] T050 [US1] Extend `README.md` with a short contract
  summary table (7 ports, idempotency keys) and pointer to
  `specs/001-fanout-worker-scaffold/contracts/`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately
- **Foundational (Phase 2)**: Depends on Setup — BLOCKS all user stories
- **User Story 1 (Phase 3)**: Depends on Foundational
- **User Story 2 (Phase 4)**: Depends on Foundational; independent of US1 beyond
  the build existing
- **User Story 3 (Phase 5)**: Depends on Foundational; contract tests reference
  domain records (T023 uses `PendingIndexRow` from T019, T029/T030 use event
  records from T015/T016, T026 uses `EngagementRef` from T017) — so complete
  US2 before US3, or at minimum T015–T019 before T023/T026/T029
- **Polish (Phase 6)**: Depends on all desired stories complete

### User Story Dependencies

- **US1 (P1)**: After Foundational; no dependencies on other stories
- **US2 (P2)**: After Foundational; independent
- **US3 (P3)**: After Foundational + domain records from US2 (records are the
  payloads in port signatures)

### Within Each User Story

- Tests written FIRST and failing (compile-red acceptable) before implementation
- Records before ports that reference them
- Interfaces before fakes that implement them
- Fakes before contract tests that exercise them (or same PR; tests stay red
  until both land)
- Story complete before moving to next priority

### Parallel Opportunities

- T002, T003 (directory scaffolding) — parallel
- T006, T007 (independent grep checks) — parallel
- T009–T014 (all US2 domain tests, distinct files) — parallel
- T015–T018, T020, T021 (domain records/enum files, distinct files) — parallel
- T023–T029 (all US3 contract tests, distinct files) — parallel
- T030, T031, T033–T039, T040–T045 (distinct port/fake files) — parallel,
  respecting interface-before-fake compile edges within a branch
- T047–T049 (polish audits, distinct files) — parallel

---

## Parallel Example: User Story 2

```text
Task: T009 TemplatePublishedTest.java
Task: T010 TemplateWithdrawnTest.java
Task: T011 EngagementRefTest.java
Task: T012 PendingIndexRowTest.java
Task: T013 FanOutTaskTest.java
Task: T014 PublishOutcomeTest.java
(all six test files, different files, no dependencies — launch together)

Then, after tests exist:
Task: T015 TemplatePublished.java
Task: T016 TemplateWithdrawn.java
Task: T017 EngagementRef.java
Task: T018 PendingIndexState.java
Task: T020 FanOutTask.java
Task: T021 PublishStatus.java
(records/enums, different files — launch together; T019/T022 after T018/T021)
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (T001–T003)
2. Complete Phase 2: Foundational (T004 — green baseline)
3. Complete Phase 3: User Story 1 (T005–T008)
4. **STOP and VALIDATE**: `mvn verify` green + both grep checks clean
5. Skeleton is deliverable as a verified empty foundation

### Incremental Delivery

1. Setup + Foundational → build skeleton verified
2. Add US1 → independently verified skeleton (MVP checkpoint)
3. Add US2 → domain validation suite green — immutable contracts deliverable
4. Add US3 → port contract suite green — full scaffold deliverable, `mvn verify`
   is the single gate
5. Polish → audits + README, whole-scaffold re-verification

### Parallel Team Strategy

1. Team completes Setup + Foundational together (fast)
2. Developer A: US1 verification + README; Developer B: US2 domain tests then
   records
3. Once US2 records land: Developer B (or C) takes US3 ports/fakes/contract
   tests — the largest phase; contract tests T023–T029 parallelize across
   people

---

## Notes

- Every task names its exact target file path.
- Tests are included because the feature specification explicitly requires
  contract tests and validation behavior pinned by tests (FR-031–FR-033).
- TDD order within stories: tests first (red), then implementation (green).
- Commit after each task or logical group.
- Stop at any checkpoint to validate that story independently.
- Out-of-scope guard: do not add scheduling, retries, rate limiting, backfill
  behavior, event consumption loops, real adapters, or content application —
  quickstart leak grep (T048) enforces this.
