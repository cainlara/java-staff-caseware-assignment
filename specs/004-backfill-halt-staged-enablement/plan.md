# Implementation Plan: Backfill, Halt, and Staged Enablement

**Branch**: `enabling_backfill` | **Date**: 2026-10-19 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/004-backfill-halt-staged-enablement/spec.md`

## Summary

Extend the live fan-out worker with an incremental backfill path (enumerate →
permit-gated downstream version capture → idempotent pending-row write →
external per-firm checkpoint), a global halt switch observed at every
admission point, per-product/per-firm staged-enablement gates on both paths,
and a disjoint budget split (backfill share B_backfill + real-time repair
share B_repair under parent C = 20) so real-time publishes never queue behind
27.8 days of backfill. Test-first and deterministic: seeded random-kill
property, halt latency, resume-no-duplicates, flag-flip-mid-publish, and a
real-time-vs-backfill independence proof — all through the existing ports,
fakes, and `mvn verify`.

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 3.5.10 parent POM (test starter only —
the domain, ports, engines, and fakes are framework-free POJOs; **no new
dependencies for this feature**)

**Storage**: `CheckpointStore` port (in-memory fake now; external durability a
later phase). Phase 1 already reserved `CheckpointKind.BACKFILL_BATCH`; the
backfill cursor uses it. No new storage system.

**Testing**: JUnit 5 + AssertJ via `spring-boot-starter-test`; deterministic
fakes (seeded RNG, latches, controllable clock — no sleep-based sync).
Single verification command: `mvn verify`.

**Target Platform**: JVM server process (the fan-out worker)

**Project Type**: Library/worker with hexagonal layout
(`domain/`, `ports/`, engine packages, `adapters/`, test `fakes/`)

**Performance Goals**: publish-to-pending p99 < 60 s preserved while backfill
runs; backfill pacing = arithmetic knob (≈ 27.8 days at B_backfill = 20,
≈ 30.9 days at 18 for ~800,000 one-minute loads); halt stops admission within
one observation bound (poll default 1 s) and drains within ~2 minutes

**Constraints**: in-flight loads ≤ B_backfill; B_backfill + B_repair ≤ C = 20
enforced at startup; zero downstream calls and zero permit acquisitions on the
publish hot path; identifier-only logs/metrics/dead-letter/checkpoint payloads;
no maintenance window — everything haltable and resumable without redeploy

**Scale/Scope**: ~800,000 pre-feature engagements, ~4,000 firms (largest
~40,000, median ~40), ~20,000 rows per publish; single backfill runner
assumed (idempotent writes are the safety net for duplicates)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | Principle | Verdict | Evidence |
|---|-----------|---------|----------|
| I | Data Ownership & Module Boundaries | PASS | New `EngagementEnumerator` port returns identifier-only refs; backfill writes only through the existing `IndexWriter` port; no reads into other teams' storage; no template content applied. |
| II | Idempotent & Resilient Event Handling | PASS | Row writes reuse the `(engagementId, offeredVersion, state)` idempotency key + row-version CAS; external per-firm cursor checkpoints; exhausted units dead-letter with full identity and alert — never silent drop (spec B4/B7/B8). |
| III | Capacity-Aware Fan-Out | PASS | Every backfill load acquires a permit from the B_backfill share first; in-flight ≤ share; paused admission (no admitted-but-waiting buffering); checkpoints outside process memory; halt drains and persists. |
| IV | Cost-Aware Design with Documented Arithmetic | PASS | Documented in research D5/plan: 800,000 × 1 min / 20 ≈ 27.8 days; / 18 ≈ 30.9 days; B_backfill is the pacing knob; storage = ≤ 1 row per engagement through the existing writer; no new cost centers; within the USD 5,000/month assumption (compute is permit-bound). |
| V | Confidentiality & Data Residency | PASS | Enumerator refs, captured version strings, checkpoints, dead-letter identities, and all counters are identifiers/measurements only; engagement content never enters the new paths (spec FR-054). |
| VI | Observability & SLOs | PASS | Counters/gauges per FR-053 (admissions, rejections by reason, permit HWM per share, halt observations, drain wall-time); freshness timer unchanged; correctness never depends on metric values. |
| VII | Safe Evolution of Live Systems | PASS | Halt switch + staged per-product/per-firm gates change live behavior without redeploy; backfill is incremental and resumable; no breaking change to delivered ports or checkpoint schemas (all changes additive; see research D6 for the one spec-assumption reconciliation on flag defaults). |
| VIII | Honest Tradeoffs & Assumptions | PASS | research.md states decisions, alternatives, and known weaknesses (single-runner, ≤ 1 batch redundant loads, per-firm sequential batch execution, halt-drain bound as alerting target). |
| IX | Java Import and Type Reference Conventions | PASS | Code-style rule for the implementation phase (simple names + explicit imports, no fully qualified inline names, no wildcards); enforced by review at implementation, no plan-time artifact affected. |

**Gate result**: PASS — no violations, no Complexity Tracking entries required.

*Post-Phase-1 re-check*: design artifacts (data-model.md, contracts/) introduce
no new schemas on delivered entities beyond the additive changes already
assessed (enum values, optional constructor parameters with delegating
defaults, composite string encodings reusing the delivered delimiter
discipline). All additive changes preserve backward compatibility across
release (constitution VII expand-only rule). Gate remains PASS.

## Project Structure

### Documentation (this feature)

```text
specs/004-backfill-halt-staged-enablement/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   ├── engagement-enumerator.md
│   ├── backfill-worker.md
│   ├── backfill-checkpoint.md
│   ├── capacity-shares.md
│   ├── admission-gates.md
│   └── backfill-dlq-identity.md
├── spec.md              # Input (phase 4 specification)
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 output (/speckit.tasks — NOT created here)
```

### Source Code (repository root)

```text
src/
├── main/java/com/example/fanout/
│   ├── domain/                          # additive only
│   │   ├── EnumeratedEngagement.java    # NEW — enumerator element (ids only)
│   │   ├── EnumerationBatch.java        # NEW — ordered batch + next cursor
│   │   ├── DeadLetterUnitKind.java      # + BACKFILL_ENGAGEMENT, BACKFILL_BATCH, BACKFILL_CURSOR
│   │   └── FanOutOutcomeStatus.java     # + HALTED, GATED (additive enum values)
│   ├── ports/
│   │   └── EngagementEnumerator.java    # NEW port (B1)
│   ├── backfill/
│   │   ├── BackfillWorker.java          # NEW — enumerate→load→write→checkpoint (B2)
│   │   ├── BackfillScheduler.java       # NEW — fair per-firm batch admission (B3)
│   │   ├── BackfillCursorRepository.java# NEW — BACKFILL_BATCH checkpoint codec (B4)
│   │   ├── BackfillConfig.java          # NEW — all knobs + share validation (FR-021)
│   │   ├── CapacityShares.java          # NEW — (C, B_backfill, B_repair) partition
│   │   └── BackfillOutcome.java         # NEW — status + progress counts
│   ├── fanout/
│   │   ├── AdmissionGate.java           # NEW — halt + product/firm gate seam (B6/B9)
│   │   ├── RetryPolicy.java             # NEW — extracted retry knobs (shared)
│   │   ├── RetryExecutor.java           # MOD — accepts RetryPolicy (source-compatible ctor kept)
│   │   ├── FairShardScheduler.java      # MOD — optional admission predicate; gated-exit result
│   │   ├── FanOutEngine.java            # MOD — FeatureFlags wiring; HALTED/GATED outcomes
│   │   ├── PermitGuardedDownstream.java # MOD — halt-abort probe in acquire loop (FR-031)
│   │   └── TaskRunner.java              # unchanged (a second fixedPool instance = backfill pool)
│   └── adapters/inmemory/               # unchanged
└── test/java/com/example/fanout/
    ├── fakes/
    │   ├── SeededEngagementEnumerator.java  # NEW — seeded, failure-injectable, restartable
    │   ├── LatchDownstreamClient.java       # EXISTING — reuse for saturation/independence
    │   └── (existing fakes reused: FakeCapacityBudget, FakeFeatureFlags,
    │        InMemoryIndexWriter, RecordingFanOutMetrics, FakeClock, ...)
    ├── backfill/
    │   ├── BackfillWorkerTest.java           # happy path, no-op, effective-version rule
    │   ├── BackfillWorkerCrashResumeTest.java# random-kill property (SC-001/003)
    │   ├── BackfillHaltTest.java             # halt latency + drain (SC-002)
    │   ├── BackfillFairnessTest.java         # 40k + 500×40 distribution (SC-006)
    │   ├── BackfillDeadLetterTest.java       # poisoned engagement (SC-009)
    │   └── CapacitySharesTest.java           # partition validation + repair independence (SC-005)
    ├── fanout/
    │   ├── AdmissionGateTest.java
    │   ├── FanOutEngineFlagFlipTest.java     # flag-flip-mid-publish (SC-007)
    │   ├── FanOutEngineHaltTest.java         # halt on fan-out path (SC-002)
    │   ├── RealTimeBackfillIndependenceTest.java # SC-004
    │   └── (existing engine tests gain gate wiring via defaults where needed)
    └── ports/
        └── EngagementEnumeratorContractTest.java # port contract pin (B1)
```

**Structure Decision**: single-project hexagonal layout unchanged; all new
behavior lands in a new `backfill` package plus additive modifications in
`fanout` (admission gating) and one new port. Two `TaskRunner` instances
(fan-out pool + backfill pool) keep the concurrency budgets disjoint (FR-020).
No changes to ingestion (phase 2) or the publish hot path's data access.

## Complexity Tracking

No constitution violations — table intentionally empty.
