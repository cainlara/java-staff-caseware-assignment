# Implementation Plan: Capacity-Aware Fan-Out Engine

**Branch**: `fanout_engine` | **Date**: 2026-10-18 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/003-capacity-aware-fanout/spec.md`

## Summary

Add the fan-out layer to the existing `fanout-worker`: a `FanOutEngine` that
consumes phase-2 `FanOutTrigger`s and turns each resolved publish (and each
withdraw supersede) into Pending Update Index rows. Correctness approach:
eligibility streamed from index metadata through a new
`EngagementEligibilitySource` port (never engagement loads, never the
downstream engagement-load client — zero calls on every hot path, asserted
per test); one ordered materialization per run split into positional chunks
(recorded in a per-run cursor in the external `CheckpointStore` — kill
mid-run, resume at first incomplete chunk, no gaps, no duplicate rows);
per-firm round-robin scheduling with a per-firm in-flight cap so a
40,000-engagement firm cannot starve median-40 firms; all writes through the
existing `IndexWriter` port with row-version compare-and-set plus an additive
`find` for the re-read/re-apply cycle; every downstream call (off-path only)
gated by the existing `CapacityBudget` (C = 20, configurable, backpressure by
paused admission, never unbounded buffering) via a `PermitGuardedDownstream`
wrapper; bounded exponential backoff with jitter on an injectable clock,
exhaustion to a new `DeadLetterStore` port with an alert metric — never a
silent drop. Withdrawn versions: rows offering them move to `CREATED` or
re-offer the next eligible non-withdrawn version; recorded decisions preserved
as history. Test-first, deterministic (latches, fake clocks, test-controlled
task runner, no sleep-based sync). No backfill, rollout flags, real
adapters, or UI.

## Technical Context

**Language/Version**: Java 21 (LTS)

**Primary Dependencies**: Spring Boot 3.5.x (project parent); JUnit 5, Spring
Boot Test (test scope). No new runtime dependencies this phase.

**Storage**: Existing in-memory `CheckpointStore` (string-valued, `(kind,
id)` keys), extended additively with two kinds: `FANOUT_CURSOR` (per-run
chunk cursor) and `PRODUCT_WITHDRAWN_VERSION` (withdrawn-version marker set by
the engine). Existing `IndexWriter` fake gains the additive `find` read.
Real adapters remain a later phase.

**Testing**: JUnit 5, plain unit tests against fakes; single gate `mvn
verify`. Deterministic: latches, fake clock, seeded jitter, test-controlled
task runner, latch-blocking downstream fake — no sleep-based synchronization,
no external systems.

**Target Platform**: Linux server (JVM 21); platform-agnostic code.

**Project Type**: Single Maven module library-style worker (no UI, no web
endpoints); Spring Boot as build/container foundation only. Trigger-to-engine
wiring is a trivial consumer hookup deferred to a later phase; the engine is
entered via its own method in tests.

**Performance Goals**: Publish hot path (metadata-only) for a typical
~20,000-engagement publish completes far inside the 60 s publish-to-pending
p99 budget at test scale (SC-010); work is dominated by in-memory index
writes across a bounded pool, with zero downstream calls.

**Constraints**: At-least-once triggers and duplicated chunk execution must be
absorbed (idempotent writes + cursor claim). Hot path never calls the
engagement-load client and never acquires capacity permits (asserted zero in
every publish/withdraw test). Any off-path downstream use is limited to C
in-flight by the same permit gate. Logs/metrics identifiers and measurements
only. All artifacts self-contained.

**Scale/Scope**: One publish fans out to ~20,000 engagements (largest firm
~40,000; ~800,000 engagements / 40 products / 4,000 firms). This phase's code
scope: 2 new ports + fakes, 1 additive read method on `IndexWriter`, 2
additive checkpoint kinds, ~6 new domain records/enums, 1 engine + fair
scheduler + retry executor + permit guard + cursor repository, and a
deterministic test suite covering the nine spec invariants (including a
simulated 40,000-engagement publish). Small, reviewable surface; no
dependencies added.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Gate result | Evidence in this plan |
|-----------|-------------|------------------------|
| I. Data Ownership & Module Boundaries | PASS | Engine owns only its cursors and scheduling state; reads index metadata via a port; writes index rows via the existing port; engagement content is unrepresentable (`EngagementRef` is metadata-only); no cross-system queries. |
| II. Idempotent & Resilient Event Handling | PASS | This phase's core: duplicate triggers absorbed by cursor claim; chunk replay absorbed by `IndexWriter` idempotency + CAS re-apply (R4); retry exhaustion → dead-letter + alert, never silent drop (R6); at-least-once assumed throughout. |
| III. Capacity-Aware Fan-Out | PASS | This phase's core: chunking + bounded pool + per-firm caps (R2/R3), `CapacityBudget` permit gate with paused admission on exhaustion (R5), zero hot-path downstream calls, resumable cursor outside process memory (R2). Cost/capacity arithmetic in research.md D10. |
| IV. Cost-Aware Design with Documented Arithmetic | PASS | research.md D10 carries full arithmetic: ~20k metadata refs materialized transiently (~8 MB), ~20k index writes per publish, zero downstream calls on ~40 publishes/week, storage bounded by 800k metadata rows (~0.5 GB worst case); run cost far under USD 5,000/month; no inference spend. |
| V. Confidentiality & Data Residency | PASS | Payloads, checkpoints, metrics, and logs carry identifiers/measurements only (`EngagementRef`, cursor values, dead-letter records contain no content); every test asserts the downstream call log empty on hot paths; region field stays opaque, enforcement deferred. |
| VI. Observability & SLOs | PASS | `FanOutMetrics` gains mandated counters (chunks started/completed, retries, dead-letter alerts, backpressure pauses, in-flight high-water) and a publish-to-pending timer (FR-071); freshness slice measurable now at test scale (SC-010); stalled-run detection deferred. |
| VII. Safe Evolution of Live Systems | PASS | All changes additive and backward compatible: new port + fake, new checkpoint-kind constants, new default-free `find` method added to `IndexWriter` with the in-memory fake implementing it, new engine package — no existing signature broken. |
| VIII. Honest Tradeoffs & Assumptions | PASS | research.md discloses: eligibility materialization vs streaming tradeoff, cursor claim race across instances, the withdrawn-version marker written by the engine (not ingest), challenged `find` requirement on `IndexWriter`, deliberately omitted Spring wiring/reconciler/real adapters, and assumption impact if wrong. |

**Gate verdict**: PASS — no violations; Complexity Tracking table stays empty.

**Post-design re-check (after Phase 1 contracts)**: PASS — contracts keep the
hot-path/permit separation (permit gate is a wrapper only reachable off-path),
cursor schema is delimiter-consistent with phase 2, `find` is additive, and
D10 arithmetic in research.md closes Principle IV. No new violations.

## Project Structure

### Documentation (this feature)

```text
specs/003-capacity-aware-fanout/
├── plan.md              # This file (/speckit.plan output)
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   ├── engagement-eligibility-source.md   # NEW port + fake
│   ├── fan-out-engine.md                  # engine entry, run semantics, config
│   ├── dead-letter-store.md               # NEW port + record + fake
│   ├── permit-guard.md                    # PermitGuardedDownstream (off-path gate)
│   ├── index-writer.md                    # v2: additive find()
│   ├── checkpoint-store.md                # v2: FANOUT_CURSOR + PRODUCT_WITHDRAWN_VERSION
│   └── fan-out-metrics.md                 # v2: mandated counter/timer names
├── checklists/
│   └── requirements.md  # From /speckit.specify
├── spec.md
└── tasks.md             # Phase 2 output (/speckit.tasks — NOT created here)
```

### Source Code (repository root)

```text
# repository root (Maven project root) — extends the phase-1/2 layout
pom.xml
src/
    ├── main/
    │   ├── java/com/example/fanout/
    │   │   ├── domain/              # phase-1/2 records + new this phase
    │   │   │   ├── EngagementRef.java           (existing)
    │   │   │   ├── PendingIndexRow.java         (existing)
    │   │   │   ├── FanOutTrigger.java           (existing)
    │   │   │   ├── FanOutTask.java              (NEW — chunk work unit)
    │   │   │   ├── FanOutCursor.java            (NEW — per-run progress)
    │   │   │   ├── FanOutRunOutcome.java        (NEW — run result)
    │   │   │   ├── DeadLetterRecord.java        (NEW — exhausted-unit identity)
    │   │   │   └── FanOutConfig.java            (NEW — tunables record)
    │   │   ├── ingest/              # phase-2 (unchanged)
    │   │   ├── fanout/              # NEW package
    │   │   │   ├── FanOutEngine.java            (NEW — core behavior)
    │   │   │   ├── FairShardScheduler.java      (NEW — per-firm round-robin)
    │   │   │   ├── RetryExecutor.java           (NEW — backoff + jitter + DLQ)
    │   │   │   ├── BackoffWaiter.java           (NEW — interface: real sleeper / test advance)
    │   │   │   ├── PermitGuardedDownstream.java (NEW — off-path permit gate; unused by hot path)
    │   │   │   └── FanOutCursorRepository.java  (NEW — typed adapter over CheckpointStore)
    │   │   ├── ports/               # phase-1/2 ports + new this phase
    │   │   │   ├── EngagementEligibilitySource.java (NEW)
    │   │   │   ├── DeadLetterStore.java            (NEW)
    │   │   │   ├── IndexWriter.java                (EXTEND: additive find())
    │   │   │   ├── CheckpointKind.java             (EXTEND: FANOUT_CURSOR, PRODUCT_WITHDRAWN_VERSION)
    │   │   │   ├── CapacityBudget.java             (existing, unchanged)
    │   │   │   └── DownstreamLoadClient.java       (existing, unchanged)
    │   │   └── adapters/inmemory/
    │   │       ├── InMemoryCheckpointStore.java    (existing, unchanged)
    │   │       └── NoOpFanOutMetrics.java          (existing, unchanged)
    │   └── resources/
    └── test/
        ├── java/com/example/fanout/
        │   ├── fanout/               # NEW test package — one class per spec story
        │   │   ├── FanOutEnginePublishTest.java          (US1: hot path, zero downstream)
        │   │   ├── FanOutEngineIdempotencyTest.java      (duplicate chunk, CAS re-apply)
        │   │   ├── FairShardSchedulerTest.java           (US2: fairness, caps, ceiling)
        │   │   ├── FanOutEngineCrashResumeTest.java      (US3: cursor resume)
        │   │   ├── RetryExecutorTest.java                (US4: bounds, jitter, DLQ)
        │   │   ├── PermitGuardedDownstreamTest.java      (US4: C-limit, backpressure)
        │   │   ├── WithdrawSupersedeTest.java            (US5: CREATED/re-offer + history)
        │   │   └── LargePublishSimulationTest.java       (40k-engagement invariant)
        │   ├── ports/               # extended contract tests
        │   │   ├── IndexWriterContractTest.java          (EXTEND: find vectors)
        │   │   └── DeadLetterStoreContractTest.java      (NEW)
        │   ├── domain/              # new-record validation tests
        │   └── fakes/               # phase-1/2 fakes reused; new:
        │       ├── FakeEligibilitySource.java    (NEW)
        │       ├── FakeDeadLetterStore.java      (NEW)
        │       ├── FakeClock.java                (NEW — manual advance)
        │       ├── SeededJitter.java             (NEW — deterministic jitter)
        │       ├── TestTaskRunner.java           (NEW — test-controlled execution)
        │       ├── LatchDownstreamClient.java    (NEW — latch-blocked, counts in-flight)
        │       └── FailingIndexWriter.java       (NEW — scripted transient failures)
        └── resources/
```

**Structure Decision**: Extend the existing single Maven module in place. New
behavior lives in a dedicated `fanout` package (engine, fair scheduler, retry,
permit guard, cursor repository), keeping `domain`/`ports` additive-only per
Constitution VII. Fakes stay in test sources (phase-1/2 convention for
client-side fakes; only placeholder adapters ship in main). All engine
behavior tests live under `fanout/`, one class per spec user story, plus the
scale simulation class.

## Complexity Tracking

> Empty — Constitution Check passed with no violations.
