# Implementation Plan: Fan-Out Observability, README & Compliance

**Branch**: `observability` | **Date**: 2026-10-19 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/005-fanout-observability-compliance/spec.md`

## Summary

Finish the fan-out worker (phases 1–4 already deliver contracts, idempotent
ingest, the capacity-aware engine, and resumable backfill) with a real
Micrometer implementation of the `FanOutMetrics` port (ingest lag, duplicates,
progress/rate, in-flight gauge, retries, DLQ depth, checkpoint lag,
per-publish freshness histogram, backfill progress), whitelist-validated
structured JSON logging with a build-enforced confidentiality test, an
alert-hooks interface (freshness burn, coverage breach, DLQ non-empty,
backfill stalled, downstream saturation) with documented thresholds and
runtime-changeable evaluation windows, a 40,000-engagement end-to-end
observability demo test, a self-contained two-page root README (tradeoffs,
known weaknesses, C = 20 placeholder, cost arithmetic, run instructions), and
a constitution compliance pass whose every gap is an explicit TODO. Ports
change only additively (default methods, delegating constructors); no new
runtime framework, no external infrastructure, single verify command
`mvn verify`.

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 3.5.10 parent POM (test starter only —
domain/ports/engines are framework-free POJOs). **One new compile dependency:
`io.micrometer:micrometer-core`** (adapter + `SimpleMeterRegistry` for
tests/demo). Structured JSON logging is a framework-free port + hand-rolled
flat-JSON writer (no logback/logstash dependency; see research D6).

**Storage**: unchanged — `CheckpointStore`, `DeadLetterStore` (additive
`count()`), `IndexWriter`. No new storage system; alert state is derived, not
persisted.

**Testing**: JUnit 5 + AssertJ via `spring-boot-starter-test`; deterministic
fakes (`FakeClock`, `RecordingFanOutMetrics`, latches — no sleeps). New:
`MicrometerFanOutMetrics` tested against `SimpleMeterRegistry`; log-capture
fake; alert evaluator driven by injected clock. Single command: `mvn verify`.

**Target Platform**: JVM server process (the fan-out worker)

**Project Type**: Library/worker with hexagonal layout (`domain/`, `ports/`,
engine packages, `adapters/`, test `fakes/`)

**Performance Goals**: freshness SLO publish-to-pending p99 < 60 s measurable
per publish; alert evaluation must not add work to the hot path; metric
emission is O(1) per event/chunk; logging at chunk/batch boundaries only (≤
~40 log lines per 20k-row publish; engagement_id only on error/DLQ paths) —
never per-engagement at hot-path volume.

**Constraints**: identifiers-only telemetry (hard rule); no per-engagement
metric dimensions (cardinality/cost); in-flight ≤ C on every path (alerts,
not code, own saturation response); no unbounded buffers (log/metric volume
bounded by chunk cadence); alert thresholds/windows changeable without
redeploy; total run cost ≤ USD 5,000/month (worker ≈ USD 150/month baseline) —
arithmetic stated in README.

**Scale/Scope**: ~4,000 firms, ~800,000 engagements, ~20,000 rows/publish,
~40 publishes/week, C = 20 placeholder; ~25 metric names; ~40 fresh
publish_id-tagged timer series/week (backend TTL out of scope, noted in
README cost section).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | Principle | Verdict | Evidence |
|---|-----------|---------|----------|
| I | Data Ownership & Module Boundaries | PASS | New code adds ports/adapters only (`FanOutMetrics` impl, `StructuredLogSink`, `AlertSink`); reads no other team's storage; alert evaluator reads only worker-owned cursor/DLQ/metric state; index rows untouched in shape. |
| II | Idempotent & Resilient Event Handling | PASS | Observability is read-only side-channel; correctness never depends on metric/log/alert values (port javadoc invariant, now enforced by demo: duplicate delivery increments `ingest.duplicate` and leaves rows unchanged). |
| III | Capacity-Aware Fan-Out | PASS | No new admission path; in-flight gauge and saturation alert observe the existing permit guard; evaluator and logging add no downstream calls and no buffering. |
| IV | Cost-Aware Design with Documented Arithmetic | PASS | `micrometer-core` only (no hosted metrics bill in-scope); metric series bounded (~25 names, product/firm tags ≤ 4k×small, publish_id only on freshness timer ≈ 40/week); log volume chunk-bounded; README states arithmetic vs USD 5,000/month; zero inference. |
| V | Confidentiality & Data Residency | PASS | Whitelist decorator rejects unknown log fields at the boundary; metric tag allowlist excludes engagement_id; test suite fails build on content-shaped payloads; no region-crossing path added. |
| VI | Observability & SLOs | PASS | This feature IS the observability principle: all required measurements, 5 alert conditions with thresholds/windows, freshness p99 against 60 s. |
| VII | Safe Evolution of Live Systems | PASS | Port changes additive (default `gauge`, default `count`, delegating `Clock` ctor); alert thresholds runtime-changeable (FR-014); no checkpoint schema change; halt/staged flags untouched. |
| VIII | Honest Tradeoffs & Assumptions | PASS | research.md records decisions/alternatives/weaknesses; README names riskiest element, known weaknesses, C = 20 placeholder; compliance pass records every gap as explicit TODO. |
| IX | Java Import and Type Reference Conventions | PASS | Implementation-phase code rule (simple names + explicit imports); enforced at review; no plan-time artifact affected. |

**Gate result**: PASS — no violations, no Complexity Tracking entries required.

*Post-Phase-1 re-check*: design artifacts add one port method with a default
implementation (`FanOutMetrics.gauge`), one store method (`DeadLetterStore.count`),
one delegating constructor (`IngestService` + `Clock`), one read-only accessor
(`PermitGuardedDownstream.inFlight`), and new leaf types (`AlertCondition`,
`AlertThresholds`, `AlertEvaluation`, `LogEvent`). All additive/expand-only;
in-repo implementors updated in the same release; checkpoint and index-row
schemas unchanged. Gate remains PASS.

## Project Structure

### Documentation (this feature)

```text
specs/005-fanout-observability-compliance/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   ├── micrometer-fan-out-metrics.md
│   ├── structured-logging.md
│   ├── alert-hooks.md
│   └── e2e-observability-demo.md
├── spec.md              # Input (phase 5 specification)
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 output (/speckit.tasks — NOT created here)
```

Compliance-pass evidence artifact (created at implementation, referenced by
plan/research): `docs/compliance.md` + README "Constitution compliance"
section with the TODO register.

### Source Code (repository root)

```text
pom.xml                                   # + io.micrometer:micrometer-core (compile)
README.md                                 # REWRITE — two-page self-contained component README
docs/
└── compliance.md                         # NEW — per-principle pass + explicit TODO register
src/main/java/com/example/fanout/
├── ports/
│   ├── FanOutMetrics.java                # + gauge(String,double,String...) default no-op
│   ├── DeadLetterStore.java              # + count() (depth source for alerts/DLQ gauge)
│   ├── StructuredLogSink.java            # NEW port — whitelisted identifier log events
│   └── AlertSink.java                    # NEW port — fire/clear alert notifications
├── observability/
│   ├── LogLevel.java                     # NEW
│   ├── LogEvent.java                     # NEW — event name + identifier map + instant
│   ├── IdentifierField.java              # NEW — whitelist enum (publish_id, product_id, ...)
│   ├── WhitelistValidatingLogSink.java   # NEW — rejects unknown fields (FR-005)
│   ├── AlertCondition.java               # NEW — 5 conditions
│   ├── AlertThresholds.java              # NEW — thresholds + windows record
│   ├── AlertEvaluation.java              # NEW — condition + active/cleared + identifiers
│   ├── AlertEvaluator.java               # NEW — pure, clock-injected, caller-driven
│   └── AlertThresholdProvider.java       # NEW port — runtime-changeable thresholds (FR-014)
├── adapters/
│   ├── micrometer/
│   │   └── MicrometerFanOutMetrics.java  # NEW — port impl: counters/timers/gauges + p99 snapshot
│   ├── logging/
│   │   └── JsonLineStructuredLogSink.java# NEW — hand-rolled flat-JSON writer (FR-004)
│   └── inmemory/
│       ├── NoOpFanOutMetrics.java        # + gauge override (still validates, records nothing)
│       ├── InMemoryAlertSink.java        # NEW — recording sink for tests/demo
│       └── InMemoryStructuredLogSink.java# NEW — capturing sink for confidentiality test
├── ingest/
│   └── IngestService.java                # + Clock (delegating ctor); ingest.lag timer; tag-pair fix
├── fanout/
│   ├── FanOutEngine.java                 # freshness timer from publishedAt; checkpoint-lag gauge; log hooks
│   ├── PermitGuardedDownstream.java      # + inFlight() accessor; downstream completion counter
│   └── RetryExecutor.java                # + retry/DLQ log hooks (metrics already emitted)
└── backfill/
    └── BackfillWorker.java               # + progress gauge, stall-source timestamps, log hooks
src/test/java/com/example/fanout/
├── observability/
│   ├── MicrometerFanOutMetricsTest.java  # NEW
│   ├── WhitelistValidatingLogSinkTest.java
│   ├── JsonLineStructuredLogSinkTest.java
│   ├── LogConfidentialityTest.java       # NEW — suite-wide capture, no unexpected fields/content
│   ├── AlertEvaluatorTest.java           # NEW — 5 conditions fire + clear, FakeClock windows
│   └── AlertThresholdProviderTest.java
├── fanout/
│   └── EndToEndObservabilityDemoTest.java# NEW — 40,000-engagement demo (FR-008)
└── (existing fakes updated: RecordingFanOutMetrics.gauge capture; FakeDeadLetterStore.count;
     fakes for threshold provider / log capture)
```

**Structure Decision**: hexagonal layout unchanged. New cross-cutting package
`observability/` holds framework-free alert/log types; Micrometer and JSON
adapters live under `adapters/` so the engines keep depending only on ports.
Existing `RecordingFanOutMetrics` fake gains gauge capture; existing contract
tests gain the additive names in their allowlists.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

None — gate passed with zero violations.
