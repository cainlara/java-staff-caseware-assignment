---

description: "Task list for fan-out observability, README & compliance"
---

# Tasks: Fan-Out Observability, README & Compliance

**Input**: Design documents from `/specs/005-fanout-observability-compliance/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: INCLUDED — explicitly requested by the spec (FR-005 build-enforced confidentiality test, FR-008 e2e demo test, SC value-verified measurements; deterministic fakes, FakeClock, no sleep-sync; single gate `mvn verify`).

**Organization**: Tasks grouped by user story for independent implementation and testing.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: User story label (US1–US6); absent in Setup/Foundational/Polish
- Exact file paths in every description

## Path Conventions

Single project, hexagonal layout (plan.md): `src/main/java/com/example/fanout/`, `src/test/java/com/example/fanout/`, new packages `observability/`, `adapters/micrometer/`, `adapters/logging/`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Baseline green + dependency + package scaffolding

- [X] T001 Run baseline `mvn verify` from repository root to confirm the pre-change build is green
- [X] T002 [P] Add `io.micrometer:micrometer-core` (compile scope, version from Spring Boot 3.5.10 parent BOM) to pom.xml — the ONLY new dependency (plan.md Technical Context; research D1)
- [X] T003 [P] Create package directories src/main/java/com/example/fanout/observability/, src/main/java/com/example/fanout/adapters/micrometer/, src/main/java/com/example/fanout/adapters/logging/ and matching test directories under src/test/java/com/example/fanout/

**Checkpoint**: Baseline green; structure ready; no other pom change allowed

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Shared whitelist types, port extensions, and gauge plumbing that ALL stories depend on

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [X] T004 [P] Create IdentifierField enum in src/main/java/com/example/fanout/observability/IdentifierField.java — values EXACTLY the spec whitelist in declaration order: `publish_id`, `product_id`, `firm_id`, `engagement_id`, `region`, `chunk_id`, `state` (data-model.md; this enum IS the whitelist — no other value may exist)
- [X] T005 [P] Create LogLevel enum in src/main/java/com/example/fanout/observability/LogLevel.java — values `INFO`, `WARN`, `ERROR` only; no DEBUG/TRACE (data-model.md volume discipline)
- [X] T006 [P] Create StructuredLogSink port in src/main/java/com/example/fanout/ports/StructuredLogSink.java — `void log(LogLevel level, String event, Instant at, Map<IdentifierField, String> identifiers)`; javadoc hard rule: identifiers and measurements only, never engagement content (contracts/structured-logging.md; depends T004, T005)
- [X] T007 [P] Add default gauge hook to FanOutMetrics in src/main/java/com/example/fanout/ports/FanOutMetrics.java — `default void gauge(String name, double value, String... tags) { }` with javadoc: same confidentiality invariant as increment/recordTime; name non-blank, tags even-length pairs (data-model.md additive changes)
- [X] T008 [P] Override gauge in NoOpFanOutMetrics in src/main/java/com/example/fanout/adapters/inmemory/NoOpFanOutMetrics.java — validate name/tags exactly like increment, record nothing (depends T007)
- [X] T009 [P] Extend RecordingFanOutMetrics in src/test/java/com/example/fanout/fakes/RecordingFanOutMetrics.java — add `record GaugeCall(String name, double value, List<String> tags)`, capture on gauge override, `gaugeNames()` accessor, include gauges in `saw()` (depends T007)
- [X] T010 [P] Add `long count()` to DeadLetterStore in src/main/java/com/example/fanout/ports/DeadLetterStore.java — current depth; append-only stores make depth = stored record count; implement in FakeDeadLetterStore at src/test/java/com/example/fanout/fakes/FakeDeadLetterStore.java (list size) (data-model.md; research D3)
- [X] T011 Extend DeadLetterStoreContractTest in src/test/java/com/example/fanout/ports/DeadLetterStoreContractTest.java — empty store count == 0 (alert sees real zero); after storing n records count == n (depends T010)

**Checkpoint**: Foundational types and port extensions in place — stories can start in parallel

---

## Phase 3: User Story 1 - Measure SLOs with metrics (Priority: P1) 🎯 MVP

**Goal**: Micrometer implementation of FanOutMetrics emits every measurement in spec FR-001/FR-002 with identifier-only, low-cardinality dimensions; SLO freshness is publish→last-index-write; in-flight is a live gauge

**Independent Test**: `MicrometerFanOutMetricsTest` + existing suites under `mvn verify`: simulate publish/retry/DLQ/backfill; assert each mandated meter's value/tags; assert no `engagement_id` metric tag; ring p99 matches manual p99 (spec US1 Independent Test)

### Tests for User Story 1 (test-first)

- [X] T012 [P] [US1] Write MicrometerFanOutMetricsTest FIRST (expect red) in src/test/java/com/example/fanout/observability/MicrometerFanOutMetricsTest.java — meter mapping (counter add, timer record, gauge set), tag-pair parsing, odd-length tags rejected, `engagement_id` tag key throws IllegalArgumentException, name set ⊆ allowlist (phase 3/4 names + additions), ring p99 deterministic under known samples, empty ring → p99 empty (contracts/micrometer-fan-out-metrics.md test vectors 1–4)

### Implementation for User Story 1

- [X] T013 [US1] Create MicrometerFanOutMetrics in src/main/java/com/example/fanout/adapters/micrometer/MicrometerFanOutMetrics.java — ConcurrentHashMap strong-ref meter cache; increment→Counter; recordTime→Timer with publishPercentileHistogram; gauge→Gauge bound to strong holder; freshness ring (N=1024) with `freshnessP99()`; forbidden-tag guard; registry-exception soft-drop counting `fanout.metrics.dropped` (contracts/micrometer-fan-out-metrics.md; research D1/D8/D13; depends T012)
- [X] T014 [P] [US1] Extend IngestService in src/main/java/com/example/fanout/ingest/IngestService.java — add `Clock clock` with delegating constructor `IngestService(progress, triggers, metrics)` → `Clock.systemUTC()`; emit `ingest.lag` timer (publishedAt/withdrawnAt → claim instant, tag `product_id`); normalize ALL six existing emissions to `key, value` tag pairs replacing `"product_id=" + id` strings (research D12; data-model.md)
- [X] T015 [P] [US1] Extend PermitGuardedDownstream in src/main/java/com/example/fanout/fanout/PermitGuardedDownstream.java — add read-only `int inFlight()` accessor; emit `fanout.downstream.completed` counter (tag `product_id`) once per permit-held call in the existing finally next to release (research D2; data-model.md)
- [X] T016 [P] [US1] Extend FanOutEngine in src/main/java/com/example/fanout/fanout/FanOutEngine.java — `recordPublishTimer` measures from `IngestedPublish.publishedAt` (already loaded) instead of run-start instant; tags `product_id` + `publish_id`; emit `fanout.checkpoint.lag.chunks` gauge (`totalChunks - 1 - lastCompletedChunkIndex`) after each persisted chunk advance, on resume when `startChunk > 0`, and value 0 once at COMPLETE (research D4/D5; data-model.md)
- [X] T017 [US1] Update emission allowlists and affected assertions — RecordingFanOutMetrics BACKFILL_* allowlists gain the new names; specs-003 fan-out-metrics contract test in src/test/java/com/example/fanout/ (allowlist additions: `ingest.lag`, `fanout.downstream.inflight`, `fanout.downstream.completed`, `fanout.checkpoint.lag.chunks`, `fanout.metrics.dropped`; freshness timer now publish_id-tagged); fix any IngestService tests asserting the old `k=v` single-string tag form (depends T013–T016)
- [X] T018 [US1] Run `mvn verify` and reconcile timer-base semantic change — tests that set distinguishable `publishedAt` on fixtures assert the new SLO window; keep FakeClock determinism; no sleep-based sync (depends T014, T016, T017)

**Checkpoint**: All FR-001/FR-002/FR-003 measurements live and value-verified — US1 independently demonstrable

---

## Phase 4: User Story 2 - Confidentiality-proof structured logging (Priority: P2)

**Goal**: Every worker log event structured, whitelist-enforced, JSON; build fails on any non-whitelisted field or content-shaped value (FR-004, FR-005, FR-013)

**Independent Test**: `LogConfidentialityTest` fails the build if any captured event violates whitelist/content guards; violating log statement added anywhere in paths under test makes it red (spec US2 Independent Test)

### Tests for User Story 2 (test-first)

- [X] T019 [P] [US2] Write WhitelistValidatingLogSinkTest FIRST (expect red) in src/test/java/com/example/fanout/observability/WhitelistValidatingLogSinkTest.java — every guard throws IllegalArgumentException naming offending event/field: null args, blank event, blank identifier value, value > 128 chars, CR/LF/NUL in value, 3+ consecutive whitespace runs, `engagement_id` on events outside {`fanout.retry`, `fanout.dlq`}; valid event passes through unchanged (contracts/structured-logging.md)
- [X] T020 [P] [US2] Write JsonLineStructuredLogSinkTest FIRST (expect red) in src/test/java/com/example/fanout/observability/JsonLineStructuredLogSinkTest.java — golden lines: key order ts, level, event, then identifiers in enum declaration order; JSON escaping of quote/backslash; one line per event ending \n (contracts/structured-logging.md)

### Implementation for User Story 2

- [X] T021 [P] [US2] Create LogEvent record in src/main/java/com/example/fanout/observability/LogEvent.java — validation verbatim from data-model: level non-null; event non-blank dotted lower_snake; at non-null; identifiers map non-null, values non-blank, content-shaped values rejected (length ≤ 128, no CR/LF/NUL, no 3+ consecutive whitespace runs) (depends T004, T005)
- [X] T022 [US2] Create WhitelistValidatingLogSink in src/main/java/com/example/fanout/observability/WhitelistValidatingLogSink.java — decorator enforcing LogEvent rules + engagement_id error-path-only rule; delegates to wrapped sink (depends T006, T021, T019)
- [X] T023 [P] [US2] Create JsonLineStructuredLogSink in src/main/java/com/example/fanout/adapters/logging/JsonLineStructuredLogSink.java — flat JSON line to injected Appendable; minimal escaper; fixed key order (depends T021, T020)
- [X] T024 [P] [US2] Create InMemoryStructuredLogSink and NoOpStructuredLogSink in src/main/java/com/example/fanout/adapters/inmemory/ — capture list for tests/demo; no-op for delegating constructors (depends T006)
- [X] T025 [US2] Wire optional StructuredLogSink into IngestService in src/main/java/com/example/fanout/ingest/IngestService.java — delegating constructor defaults to NoOpStructuredLogSink (additive, constitution VII); emit `ingest.claimed`, `ingest.duplicate`, `ingest.deferred` with product_id/publish_id (or withdraw_id as event identity via publish_id slot ONLY if schema-safe — otherwise keep withdraw id out of logs and rely on product_id; do NOT add a non-whitelisted field) (depends T022, T024)
- [X] T026 [US2] Wire optional sink into FanOutEngine + RetryExecutor in src/main/java/com/example/fanout/fanout/ — delegating ctor defaults; emit `fanout.run.started`, `fanout.run.completed`, `fanout.chunk.started`, `fanout.chunk.completed` (chunk_id, publish_id, product_id, state), `fanout.retry`, `fanout.dlq` (engagement_id allowed only on retry/DLQ), `fanout.supersede.marked` — chunk cadence only, NEVER per-row on the hot path (contracts/structured-logging.md emission policy; depends T022, T024)
- [X] T027 [US2] Wire optional sink into BackfillWorker in src/main/java/com/example/fanout/backfill/BackfillWorker.java — delegating ctor; emit `backfill.batch.admitted`, `backfill.batch.completed`, `backfill.halt.observed` (firm_id, product_id, state) (depends T022, T024)
- [X] T028 [US2] Write LogConfidentialityTest in src/test/java/com/example/fanout/observability/LogConfidentialityTest.java — drive ingest → publish → retry/DLQ → supersede → backfill paths with WhitelistValidatingLogSink → InMemoryStructuredLogSink; assert every captured event: identifier keys ⊆ whitelist; no content-shaped values; no engagement-content markers from fixtures; hot-path publish of 2,000 rows emits ≤ 4×chunkCount + constant log events (FR-005; contracts test vector 3–4; depends T022–T027)
- [X] T029 [US2] Run `mvn verify`; triage any engine-test breakage from new ctor overloads (delegating defaults must keep existing call sites compiling unchanged) (depends T025–T028)

**Checkpoint**: Build-enforced confidentiality logging live — FR-005 gate green

---

## Phase 5: User Story 3 - Alert hooks with documented thresholds (Priority: P3)

**Goal**: AlertSink + pure AlertEvaluator for all five conditions with default thresholds/windows, edge-triggered fire/clear, runtime-changeable thresholds without redeploy (FR-006, FR-007, FR-014)

**Independent Test**: `AlertEvaluatorTest` drives each of the five conditions true under FakeClock past its window — exactly one FIRE edge with documented identifiers; clears each — exactly one CLEAR edge; intentionally halted backfill never fires stall; threshold hot-swap takes effect next evaluate (spec US3 Independent Test)

### Tests for User Story 3 (test-first)

- [X] T030 [P] [US3] Write AlertEvaluatorTest FIRST (expect red) in src/test/java/com/example/fanout/observability/AlertEvaluatorTest.java — all 9 test vectors from contracts/alert-hooks.md: 5 conditions fire-once/clear-once; freshness p99 boundary at exactly 60 s; coverage only for non-COMPLETE aged runs; DLQ empty=0 no fire; backfill halt exclusion; saturation needs pinned window + zero completions; threshold hot-swap via provider; no-data freshness never fires; emitted identifiers keys ⊆ IdentifierField with no content-shaped values

### Implementation for User Story 3

- [X] T031 [P] [US3] Create AlertCondition enum in src/main/java/com/example/fanout/observability/AlertCondition.java — exactly `FRESHNESS_BURN`, `COVERAGE_BREACH`, `DLQ_NON_EMPTY`, `BACKFILL_STALLED`, `DOWNSTREAM_SATURATION` (data-model.md)
- [X] T032 [P] [US3] Create AlertThresholds record in src/main/java/com/example/fanout/observability/AlertThresholds.java — fields/defaults verbatim: freshnessP99Limit=60s, freshnessWindow=5m, coverageAgeLimit=24h, coverageWindow=10m, dlqWindow=1m, backfillStallLimit=15m, backfillWindow=5m, saturationWindow=2m; validation verbatim: all durations positive; coverageAgeLimit ≥ 1 h (data-model.md)
- [X] T033 [P] [US3] Create AlertEvaluation record in src/main/java/com/example/fanout/observability/AlertEvaluation.java — condition, active, at, identifiers; identifiers keys ⊆ IdentifierField (data-model.md)
- [X] T034 [P] [US3] Create AlertSink port in src/main/java/com/example/fanout/ports/AlertSink.java plus InMemoryAlertSink in src/main/java/com/example/fanout/adapters/inmemory/InMemoryAlertSink.java — fire/clear signatures per contracts/alert-hooks.md; recording implementation for tests/demo (depends T031, T033)
- [X] T035 [P] [US3] Create AlertThresholdProvider port in src/main/java/com/example/fanout/ports/AlertThresholdProvider.java plus InMemoryAlertThresholdProvider in src/main/java/com/example/fanout/adapters/inmemory/ — `AlertThresholds current()`; settable impl proving FR-014 runtime change without redeploy (depends T032)
- [X] T036 [US3] Create AlertEvaluator in src/main/java/com/example/fanout/observability/AlertEvaluator.java — pure, clock-injected, caller-driven `List<AlertEvaluation> evaluate(Instant now)`; injects FreshnessP99 source (MicrometerFanOutMetrics.freshnessP99), FanOutCursorRepository + ingested publishedAt lookup, DeadLetterStore, backfill checkpoint reader + running/halted probes, in-flight supplier, completion-delta source, AlertThresholdProvider, AlertSink; in-memory active set; edge-only emission; halt exclusion; two-sample minimum for saturation (contracts/alert-hooks.md; research D7; depends T030–T035, T013)
- [X] T037 [US3] Emit `alert.fired` / `alert.cleared` structured log events from AlertEvaluator in src/main/java/com/example/fanout/observability/AlertEvaluator.java via optional StructuredLogSink (delegating ctor, default NoOp) with condition name in event string and identifiers map only (depends T036, T024)
- [X] T038 [US3] Run `mvn verify`; confirm all five conditions verified 5/5 fire + clear and no regressions (depends T036, T037)

**Checkpoint**: Alert interface + thresholds + windows delivered and verified — FR-006/FR-007/FR-014 evidence complete

---

## Phase 6: User Story 4 - End-to-end demo proving the engine under observation (Priority: P4)

**Goal**: Single runnable demo: 40,000-engagement TemplatePublished through the REAL engine with Micrometer metrics, whitelist logs, zero spurious alerts — the README's demo (FR-008, FR-015)

**Independent Test**: `mvn test -Dtest=EndToEndObservabilityDemoTest` — 8 assertion groups per contracts/e2e-observability-demo.md, all green, no external infra (spec US4 Independent Test)

### Tests for User Story 4

- [X] T039 [P] [US4] Write EndToEndObservabilityDemoTest in src/test/java/com/example/fanout/fanout/EndToEndObservabilityDemoTest.java — wiring and 8 assertion groups verbatim from contracts/e2e-observability-demo.md: 40,000 rows eligible-set equality; zero hot-path downstream calls; in-flight ≤ C=20 sampled + gauge HWM; freshness timer count==1 tagged publish_id/product_id with FakeClock-deterministic duration and freshnessP99 readable; ingest.lag count==1; chunks 80/80 + rows.resolved total + lag gauge ends 0; all logs whitelisted with O(chunks) volume bound; zero alert fires; duplicate re-delivery increments duplicate counters and leaves rows unchanged (depends T013, T022, T036)
- [X] T040 [US4] Reconcile demo-driven wiring gaps (fixture ctor updates, SimpleMeterRegistry plumbing) and confirm suite runtime stays within quickstart bound (seconds–low minutes, no sleeps) (depends T039)

**Checkpoint**: Demo green — SC-005 evidence (4/4 primary assertions + confidentiality/volume/alert/idempotency extras)

---

## Phase 7: User Story 5 - Self-contained README (Priority: P5)

**Goal**: Root README becomes the ~2-page component doc — everything a maintainer needs with zero external-document dependencies (FR-009, FR-010)

**Independent Test**: README review against FR-009 topic checklist; a new maintainer can run tests + demo from README alone (spec US5 Independent Test)

### Implementation for User Story 5

- [X] T041 [P] [US5] Rewrite root README.md — MUST contain, self-contained (~2 pages): (1) what the component does; (2) tradeoffs named verbatim in spirit: index-only hot path, capacity budget + rate limiter C, chunk-cursor external checkpoints, ancestry-range decline pins, separate real-time and backfill pools, at-least-once + idempotency over exactly-once; (3) known weaknesses; (4) riskiest element named; (5) placeholder assumption C = 20 pending downstream-owner confirmation; (6) cost arithmetic vs USD 5,000/month total and ~USD 150/month worker baseline including metric-series and log-volume math (research D8/D9); (7) how to run tests (`mvn verify`) and the demo (`mvn test -Dtest=EndToEndObservabilityDemoTest`); (8) telemetry reference (metric names, log whitelist fields, five alert conditions + thresholds/windows); no required information behind an external link (links to docs/design.md allowed only as further reading)

**Checkpoint**: README complete — US5 independently reviewable

---

## Phase 8: User Story 6 - Constitution compliance pass with explicit TODOs (Priority: P6)

**Goal**: Every constitution principle reviewed against the whole worker + README; gaps are explicit TODOs, never silent (FR-011, FR-012, SC-007)

**Independent Test**: docs/compliance.md shows a verdict + evidence location for all nine principles and a numbered TODO register; spot-check any gap → it appears in the register (spec US6 Independent Test)

### Implementation for User Story 6

- [X] T042 [P] [US6] Create docs/compliance.md — table of ALL nine constitution principles (I–IX) with verdict + file/class-level evidence; riskiest-element section (name fair-shard scheduler + permit-guard interaction under mixed real-time/backfill load, per research D10); explicit numbered TODO register seeded at minimum with: C=20 confirmation, logback/SLF4J bridge for StructuredLogSink, runtime scheduler tick for AlertEvaluator, real MeterRegistry binding/export, identifier-only trace spans, DLQ replay tool (FR-011; research open items)
- [X] T043 [US6] Add condensed "Constitution compliance" section to root README.md summarizing the nine verdicts and inlining the TODO register headlines so README stays self-contained (depends T041, T042)
- [X] T044 [US6] Final compliance sweep — grep-audit: no engagement content in any telemetry path; no `engagement_id` metric tags; no unbounded buffers (log/metric volume bounds hold); no per-engagement inference anywhere; every gap found appended to docs/compliance.md TODO register with owner-facing wording (FR-012; depends T042)

**Checkpoint**: Compliance evidence complete — SC-007/SC-008 audit trail ready

---

## Phase 9: Polish & Cross-Cutting Concerns

**Purpose**: Single-gate verification and convention enforcement

- [X] T045 Run full `mvn verify` from repository root — sole verification command must be green end to end (quickstart.md §1)
- [X] T046 [P] Java import/type reference convention review (constitution IX) over ALL new and modified files under src/ — simple names + explicit imports, no wildcard imports, no fully qualified inline names; fix any violation
- [X] T047 [P] Re-read quickstart.md against the delivered tree — commands, suite names, and assertion descriptions must match reality; edit quickstart.md if drifted
- [X] T048 Traceability pass — confirm every spec FR-001…FR-015 and SC-001…SC-008 maps to at least one delivered task/evidence artifact; append any missing mapping note to docs/compliance.md (no silent omission, constitution VIII)

---

## Dependencies & Execution Order

**Story dependencies** (most stories independent after Foundational):

```
Phase 1 Setup ──▶ Phase 2 Foundational ──┬──▶ US1 (metrics)      ──┐
                                         ├──▶ US2 (logging)      ──┼──▶ US4 (demo) ──▶ US5 (README) ──▶ US6 (compliance) ──▶ Polish
                                         └──▶ US3 (alerts)       ──┘
```

- US1, US2, US3 are mutually independent once Phase 2 completes (US3 consumes US1's `freshnessP99()` — if strictly serial, do US1 first; otherwise AlertEvaluator test can use a scripted FreshnessP99 supplier and US3 still runs in parallel).
- US4 needs US1+US2+US3 wiring (demo asserts metrics, logs, and zero alert fires).
- US5 (README) documents delivered behavior — implement after US4 for truthful content.
- US6 last before Polish; needs the whole surface to audit.

**Within-story serial chains**:

- US1: T012 → T013 → (T014 ∥ T015 ∥ T016) → T017 → T018
- US2: (T019 ∥ T020) → T021 → T022 → (T023 ∥ T024) → (T025 ∥ T026 ∥ T027) → T028 → T029
- US3: T030 → (T031 ∥ T032 ∥ T033 ∥ T034 ∥ T035) → T036 → T037 → T038
- US4: T039 → T040
- US6: T042 → T043 → T044

## Parallel Execution Examples

**Wave A (after Phase 2, three streams)**:
- Stream 1 (US1): T012 → T013 → T014 ∥ T015 ∥ T016 → T017 → T018
- Stream 2 (US2): T019 ∥ T020 → T021 → T022 → T023 ∥ T024 → T025 ∥ T026 ∥ T027 → T028 → T029
- Stream 3 (US3): T030 → T031 ∥ T032 ∥ T033 ∥ T034 ∥ T035 → T036 → T037 → T038

**Wave B (after Wave A)**: T039 → T040 (US4)

**Wave C (after Wave B)**: T041 (US5) ∥ T042 (US6 start) → T043 → T044 → Polish T045–T048 (T046 ∥ T047 concurrent with T045 gating T048)

**Max parallelism picks**: T014/T015/T016 (different engine files); T023/T024 (different adapters); T031–T035 (five independent observability types).

## Implementation Strategy

**MVP = User Story 1** (metrics + Micrometer adapter + freshness/in-flight correctness). Deliverable alone makes SLOs measurable — the highest-value slice (constitution VI) and a prerequisite signal source for US3's freshness alert.

**Incremental delivery order**: US1 → US2 → US3 (three independently green increments under `mvn verify`) → US4 locks the combined contract → US5 documents reality → US6 audits it → Polish ships.

**Risk management**:
- Timer-base semantic change (T016/T018) is the riskiest single edit — do it early in US1 with FakeClock determinism, never batch it with logging wiring.
- Ctor overloads must remain additive (delegating defaults) so phases 1–4 tests stay untouched — any compile break in existing tests is a design defect, fix the overload not the caller (constitution VII).
- No sleep-based synchronization anywhere; latches + FakeClock only.
- Log volume discipline enforced by assertion (T028/T039), not by review hope.

**Definition of done (feature)**: `mvn verify` green; quickstart.md commands accurate; README self-contained; docs/compliance.md complete with TODO register; every FR/SC traceable (T048).
