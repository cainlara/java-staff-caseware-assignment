# Implementation Plan: Idempotent Event Ingestion & External Checkpointing

**Branch**: `002-idempotent-event-ingestion` | **Date**: 2026-10-18 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/002-idempotent-event-ingestion/spec.md`

## Summary

Add the ingest layer to the existing `fanout-worker`: an `IngestService` that
receives `TemplatePublished` / `TemplateWithdrawn` events, validates them,
records processed state in the external `CheckpointStore` (sole progress truth),
and emits exactly one internal fan-out trigger per newly ingested event via a
new stub port. Correctness approach: idempotency on publish/withdraw ids via an
atomic claim (`writeIfAbsent`, a backward-compatible default method added to the
phase-1 `CheckpointStore` port); withdraw-before-publish events recorded as
deferred and auto-resolved when the matching publish arrives; per-product
ingest order recorded, effective version rule R4 (greatest
published-at/publish-id) independent of arrival order; permutation test proves
any duplication + reordering converges. Zero engagement-load calls on the
ingest path, asserted in every ingest test. No fan-out work, scheduling,
retries, rate limiting, or backfill.

## Technical Context

**Language/Version**: Java 21 (LTS)

**Primary Dependencies**: Spring Boot 3.5.x (project parent); JUnit 5, Spring
Boot Test (test scope). No new runtime dependencies this phase.

**Storage**: Existing in-memory `CheckpointStore` (string-valued, keyed
`(kind, id)`); extended with additive `CheckpointKind` values and an atomic
`writeIfAbsent` default method. Real file/DB adapter remains a later phase; the
interface admits both.

**Testing**: JUnit 5, plain unit tests against fakes; single gate `mvn verify`.
Deterministic, no sleeps, no external systems.

**Target Platform**: Linux server (JVM 21); platform-agnostic code.

**Project Type**: Single Maven module library-style worker (no UI, no web
endpoints); Spring Boot as build/container foundation only.

**Performance Goals**: SC-006 ingest-to-trigger p99 < 1 s (ingest stays far
inside the 60 s publish-to-pending budget). Ingest is metadata-only — no
engagement loads, no I/O beyond the checkpoint map.

**Constraints**: At-least-once bus; ingest idempotent per spec R1/R2. Hot path
must never call the engagement-load dependency (asserted zero calls in all
ingest tests). Checkpoint store is the sole progress record, external to
process memory. Logs/metrics identifiers only. All artifacts self-contained.

**Scale/Scope**: Event volume trivial (~40 publishes/week across 40 products +
withdraws); this phase's code scope: 1 new port + stub, 1 ingest service,
1 typed progress repository over the checkpoint store, ~3 new domain records,
additive checkpoint-extensions, ingest test suite (duplicate, delayed,
reordering, crash-replay, permutation, multi-instance). Small, reviewable
surface.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Gate result | Evidence in this plan |
|-----------|-------------|------------------------|
| I. Data Ownership & Module Boundaries | PASS | Ingest owns only its checkpointed progress and trigger emission; engagement content never enters the path; ports remain the only interaction surface. |
| II. Idempotent & Resilient Event Handling | PASS | This phase's core: idempotency on publish/withdraw ids (R1/R2) via atomic `writeIfAbsent` claim; duplicate/defer/reorder/crash-replay/permutation tests pin it; at-least-once assumed throughout. |
| III. Capacity-Aware Fan-Out | PASS (deferred) | No fan-out this phase; ingest makes zero engagement-load calls (FR-030/FR-041, asserted per test). Capacity budget untouched. |
| IV. Cost-Aware Design with Documented Arithmetic | PASS (deferred) | Ingest run cost ≈ 0 (metadata map operations); full arithmetic stays a fan-out-phase obligation, carried forward in research.md. |
| V. Confidentiality & Data Residency | PASS | Checkpoint values and trigger payloads carry identifiers/timestamps only; metrics identifiers only; no engagement content representable. |
| VI. Observability & SLOs | PASS (partial, deferred) | Duplicate-delivery counter mandated (FR-032); ingest-to-trigger p99 < 1 s (SC-006) measurable now; end-to-end publish-to-pending p99 < 60 s waits for fan-out phase. |
| VII. Safe Evolution of Live Systems | PASS | All changes additive and backward compatible: new `CheckpointKind` constants, new `writeIfAbsent` default method on existing port, new port/service — no existing signature broken; in-memory store keeps working. |
| VIII. Honest Tradeoffs & Assumptions | PASS | research.md discloses the checkpoint-to-trigger crash window (R5), last-write-wins limits for concurrent same-product sequencing, delimiter assumptions for opaque ids, and the challenged requirement (trigger at-most-once vs. lost-trigger reconciliation). |

**Gate verdict**: PASS — no violations; Complexity Tracking table stays empty.

## Project Structure

### Documentation (this feature)

```text
specs/002-idempotent-event-ingestion/
├── plan.md              # This file (/speckit.plan output)
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   ├── ingest-service.md
│   ├── fan-out-trigger.md
│   └── checkpoint-store.md    # v2: additive writeIfAbsent + new kinds + value schemas
├── checklists/
│   └── requirements.md  # From /speckit.specify
├── spec.md
└── tasks.md             # Phase 2 output (/speckit.tasks — NOT created here)
```

### Source Code (repository root)

```text
# repository root (Maven project root) — extends the phase-1 layout
pom.xml
src/
    ├── main/
    │   ├── java/com/example/fanout/
    │   │   ├── domain/              # phase-1 records + new this phase
    │   │   │   ├── TemplatePublished.java      (existing)
    │   │   │   ├── TemplateWithdrawn.java      (existing)
    │   │   │   └── FanOutTrigger.java          (NEW — trigger payload)
    │   │   ├── ingest/              # NEW package
    │   │   │   ├── IngestService.java          (NEW — core behavior)
    │   │   │   ├── IngestOutcome.java          (NEW — INGESTED|DUPLICATE|DEFERRED|REJECTED + resolved ids)
    │   │   │   └── IngestProgressRepository.java (NEW — typed adapter over CheckpointStore)
    │   │   ├── ports/               # phase-1 ports + new this phase
    │   │   │   ├── CheckpointStore.java        (EXTEND: default writeIfAbsent)
    │   │   │   ├── CheckpointKind.java         (EXTEND: WITHDRAW, PRODUCT_INGEST, PENDING_WITHDRAW, PRODUCT_PUBLISHES)
    │   │   │   └── FanOutTriggerSink.java      (NEW — stub port)
    │   │   └── adapters/inmemory/
    │   │       ├── InMemoryCheckpointStore.java (EXTEND: atomic writeIfAbsent override)
    │   │       └── RecordingFanOutTriggerSink.java (NEW — main-source stub, records emissions)
    │   └── resources/
    └── test/
        ├── java/com/example/fanout/
        │   ├── ingest/              # NEW test package
        │   │   ├── IngestServiceIdempotencyTest.java      (duplicate delivery)
        │   │   ├── IngestServiceWithdrawDeferralTest.java (delayed/reordered withdraws)
        │   │   ├── IngestServiceReorderingTest.java       (out-of-order publishes)
        │   │   ├── IngestServiceCrashRecoveryTest.java    (kill before/after checkpoint)
        │   │   ├── IngestServiceMultiInstanceTest.java    (shared store across instances)
        │   │   └── IngestPermutationTest.java             (convergence proof)
        │   ├── ports/               # extended CheckpointStore contract tests
        │   │   └── CheckpointStoreContractTest.java       (EXTEND: writeIfAbsent vectors)
        │   ├── domain/              # FanOutTrigger validation tests
        │   └── fakes/               # phase-1 fakes reused; FailingCheckpointWrite /
        │                            #   FailingTriggerSink helpers added for crash injection
        └── resources/
```

**Structure Decision**: Extend the existing single Maven module in place. New
behavior lives in a dedicated `ingest` package (service + typed repository +
outcome), keeping phase-1 `domain`/`ports` packages stable and additive-only.
`RecordingFanOutTriggerSink` ships in main (like `InMemoryCheckpointStore`) so
the stub exists without test wiring; crash-injection doubles stay in test. All
ingest behavior tests live under `ingest/`, one class per spec user story.

## Complexity Tracking

> Empty — Constitution Check passed with no violations.
