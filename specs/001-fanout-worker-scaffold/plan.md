# Implementation Plan: Fan-Out Worker Scaffold & Contracts

**Branch**: `001-fanout-worker-scaffold` | **Date**: 2026-10-18 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/001-fanout-worker-scaffold/spec.md`

## Summary

Deliver the verified foundation of the template-publish fan-out worker: a Maven
project `fanout-worker` (Java 21, Spring Boot 3.5.x parent, JUnit 5 + Spring Boot
Test) containing immutable validated domain records, seven port interfaces with
documented idempotency keys, in-memory/fake implementations of every port, and
contract tests that pin idempotency and validation behavior. No fan-out behavior:
no event consumption, scheduling, retries, rate limiting, backfill, real
infrastructure adapters, metrics implementation, UI, or content application.
Correctness approach: all state-changing contracts are keyed on stable identities
(at-least-once bus assumed) and guarded by compare-and-set on `row_version`; the
publish hot path is documented as index-only (metadata rows, never engagement
content); telemetry contracts accept identifiers and measurements only.

## Technical Context

**Language/Version**: Java 21 (LTS)

**Primary Dependencies**: Spring Boot 3.5.x (project parent); JUnit 5, Spring
Boot Test (test scope). No other runtime dependencies required this phase.

**Storage**: None external. In-memory `Map`-backed checkpoint store and index
writer fakes only; interface documents durability requirement (survive process
restart) for later real adapters.

**Testing**: JUnit 5 with Spring Boot Test where applicable; plain JUnit
contract tests for ports against in-memory fakes. Single gate: `mvn verify`.

**Target Platform**: Linux server (JVM 21); platform-agnostic code.

**Project Type**: Single Maven module library-style worker (no UI, no web
endpoints this phase); Spring Boot used as container/build foundation only.

**Performance Goals**: Not measurable this phase (no fan-out runs). Carried
forward for later phases: publish-to-pending p99 < 60 s at ~20,000 engagement
rows per publish; downstream load ~1 min/call bounded by C = 20 in-flight.

**Constraints**: At-least-once event bus; every consumer idempotent. Publish hot
path touches Pending Update Index metadata rows only — never engagement content.
Logs/metrics must never contain engagement content. No maintenance window.
Capacity budget C = 20 (configurable placeholder). All artifacts self-contained.

**Scale/Scope**: Context numbers: 4,000 firms, 800,000 active engagements,
40 products, median firm ~40 engagements, largest ~40,000, ~20,000 engagement
rows per publish. This phase's code scope: 6 records, 1 enum, 7 ports, ~7 fakes,
contract test suite, build files. Small, reviewable surface.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Gate result | Evidence in this plan |
|-----------|-------------|------------------------|
| I. Data Ownership & Module Boundaries | PASS | Worker owns only pending-index metadata rows; engagement content never crosses the boundary; ports are the only interaction surface. |
| II. Idempotent & Resilient Event Handling | PASS | Every port documents its idempotency key; `IndexWriter` idempotent on (engagement_id, offered_version, state) with CAS on row_version; contract tests pin duplicate-submission convergence. Event *handling logic* is out of scope this phase. |
| III. Capacity-Aware Fan-Out | PASS | `CapacityBudget` port with acquire/release, max in-flight C configurable (default 20); behavior this phase is contract + fake only; scheduling/backpressure deferred to later phases by spec. |
| IV. Cost-Aware Design with Documented Arithmetic | PASS (deferred) | Scaffold has negligible run cost (build-time only, zero infra). Full arithmetic required at design document for fan-out phase; noted as carried-forward obligation, recorded in research.md. |
| V. Confidentiality & Data Residency | PASS | `PendingIndexRow` holds metadata identifiers only; `FanOutMetrics` contract forbids engagement content; `region` on `EngagementRef` is an opaque identifier; residency enforcement deferred with later adapters. |
| VI. Observability & SLOs | PASS (deferred) | `FanOutMetrics` port defines counter/timer hooks (no-op now); SLO measurement deferred because no fan-out runs this phase (explicitly out of scope in spec). |
| VII. Safe Evolution of Live Systems | PASS | No live behavior deployed; contracts designed for backward-compatible evolution (CAS row_version, halt switch, per-product/per-firm flags) so later phases need no breaking changes. |
| VIII. Honest Tradeoffs & Assumptions | PASS | research.md records assumptions, riskiest element, deliberate omissions, and the one challenged requirement (C=20 vs. 60 s freshness arithmetic). |

**Gate verdict**: PASS — no violations, Complexity Tracking table stays empty.

## Project Structure

### Documentation (this feature)

```text
specs/001-fanout-worker-scaffold/
├── plan.md              # This file (/speckit.plan output)
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output (port contracts)
│   ├── event-source.md
│   ├── index-writer.md
│   ├── downstream-load-client.md
│   ├── checkpoint-store.md
│   ├── capacity-budget.md
│   ├── feature-flags.md
│   └── fanout-metrics.md
├── checklists/
│   └── requirements.md  # From /speckit.specify
├── spec.md
└── tasks.md             # Phase 2 output (/speckit.tasks — NOT created here)
```

### Source Code (repository root)

```text
# repository root (Maven project root)
pom.xml
src/
    ├── main/
    │   ├── java/com/example/fanout/
    │   │   ├── domain/          # Records + state enum + validation
    │   │   │   ├── TemplatePublished.java
    │   │   │   ├── TemplateWithdrawn.java
    │   │   │   ├── EngagementRef.java
    │   │   │   ├── PendingIndexRow.java
    │   │   │   ├── PendingIndexState.java
    │   │   │   ├── FanOutTask.java
    │   │   │   └── PublishOutcome.java
    │   │   ├── ports/           # Interfaces, Javadoc idempotency contracts
    │   │   │   ├── EventSource.java
    │   │   │   ├── IndexWriter.java
    │   │   │   ├── DownstreamLoadClient.java
    │   │   │   ├── CheckpointStore.java
    │   │   │   ├── CapacityBudget.java
    │   │   │   ├── FeatureFlags.java
    │   │   │   └── FanOutMetrics.java
    │   │   └── adapters/inmemory/   # In-memory main-source impls
    │   │       └── InMemoryCheckpointStore.java
    │   └── resources/
    └── test/
        ├── java/com/example/fanout/
        │   ├── domain/          # Record validation tests
        │   ├── ports/           # Contract tests per port
        │   └── fakes/           # Test doubles (writer, downstream, budget,
        │                         #   flags, metrics, event source)
        └── resources/
```

**Structure Decision**: Single Maven module (artifactId `fanout-worker`) rooted
at the repository root — `pom.xml` and `src/` live directly in the project root
(spec requires project named `fanout-worker`, `mvn verify` at the repository
root). Main
source holds domain records, ports, and the one durable-requirement placeholder
(`InMemoryCheckpointStore` — spec mandates in-memory impl "for now", so it ships
in main, not test). All other fakes live under test sources. Contract tests sit
under `src/test/java/.../ports/`, one test class per port plus per-record
validation tests under `domain/`.

## Complexity Tracking

> Empty — Constitution Check passed with no violations.
