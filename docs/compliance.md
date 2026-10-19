# Constitution compliance pass — fanout-worker

Feature: `specs/005-fanout-observability-compliance` (fan-out observability,
README, compliance). Verified by the sole gate `mvn verify` from the
repository root. Evidence is file/class-level; test names in parentheses.

## Principles

| # | Principle | Verdict | Evidence |
|---|-----------|---------|----------|
| I | Data Ownership & Module Boundaries | Pass | All cross-system access through ports (`ports/`): `IndexWriter`, `CheckpointStore`, `DeadLetterStore`, `DownstreamLoadClient`, `AlertSink`, `StructuredLogSink`, `FanOutMetrics`. No engagement-store reads/writes anywhere; hot path touches only metadata rows (`FanOutEngine`, contract tests in `FanOutEnginePublishTest`). Template store knowledge limited to event payloads consumed from `EventSource`. |
| II | Idempotent & Resilient Event Handling | Pass | Checkpoint claim is the processed record (`IngestService`, `CheckpointStore.writeIfAbsent`); chunk cursors make runs resumable (`ChunkCursor`/`ExternalCheckpointCursorStore`); CAS reapply idempotency (`FanOutEngine`, `IndexWriter.rowVersion`); duplicates counted not errored (`ingest.duplicate`, `fanout.duplicate.run`); retry exhaustion surfaces as DLQ + `DLQ_NON_EMPTY` alert, never silent drop (`RetryExecutor.storeDeadLetter`, `DeadLetterStore`). |
| III | Capacity-Aware Fan-Out | Pass | Counted permit pool `CapacityBudget` (C default 20) + `PermitGuardedDownstream` (in-flight gauge, abandon-on-halt, backpressure pause); chunked fan-out with positional chunks (`FanOutEngine`); backfill takes a configured share of C, separate real-time/backfill pools (`BackfillWorker`, `FakeCapacityBudget.share`); progress external and resumable (`FanOutEngineChunkCursorTest`). |
| IV | Cost-Aware Design with Documented Arithmetic | Pass | Root `README.md` § Cost arithmetic: worker baseline ~USD 150/mo, observability overhead < USD 20/mo, total ~USD 167 ≪ USD 5,000 budget; per-publish freshness series ~1,600/yr bounded by publish rate; no per-engagement timing (research D8); zero inference in this component. |
| V | Confidentiality & Data Residency | Pass | Whitelist log schema (`IdentifierField` enum; `WhitelistValidatingLogSink` throws on free-text/violations); `LogConfidentialityTest` audits full pipeline for content leakage and `engagement_id` in metric tags; no content in names/tags (`MicrometerFanOutMetricsTest` tag allowlist); region markers only (`region` enum values), no cross-region data movement in this component. |
| VI | Observability & SLOs | Pass | Freshness SLO p99 < 60s measured (`fanout.publish_to_pending` timer + adapter ring, `MicrometerFanOutMetrics.freshnessP99`); coverage SLO per-publish (`fanout.checkpoint.lag.chunks` gauges, non-COMPLETE run scan); structured logs cover ingest/fan-out/retry/DLQ (`LogEvent` emissions); five alert conditions (`AlertEvaluatorTest`: freshness, coverage, DLQ, backfill stall, saturation). |
| VII | Safe Evolution of Live Systems | Pass | New capabilities added via additive constructor overloads (existing ctors unchanged); log/metric schemas expand-only (whitelist enum, no renames); staged enablement gates + global halt (`FeatureFlags`, `fanout.admit.reject.*`); backfill incremental/resumable (`BackfillWorker` per-firm cursors, batch checkpoints); no downtime/redeploy needed to pause a side. |
| VIII | Honest Tradeoffs & Assumptions | Pass | README § Tradeoffs + § Known weaknesses; riskiest element named below; C=20 placeholder called out inline (TODO #1); assumptions listed with impact (this register); ambiguity flagged as TODOs, never papered over. |
| IX | Java Import and Type Reference Conventions | Pass | All new/modified files use simple names + explicit imports, no wildcards, no fully-qualified inline names (grep-audited in T046: `grep -RnE 'java\.[a-z]+\.[A-Za-z]+\.[A-Za-z]+' src/ --include='*.java'` clean except `java.time`/`java.util` imports and Javadoc `@link`s). |

## Riskiest element

**Fair-shard scheduler + permit-guard interaction under mixed
real-time/backfill load.** Fairness across firms, halt abandonment, capacity
shares, and backpressure all meet in one control loop
(`FanOutEngine` scheduling + `PermitGuardedDownstream` + `CapacityBudget`).
Evidence: `FanOutEngineFairnessTest`, `FanOutEngineBackpressureTest`,
`PermitGuardedDownstreamTest`, `BackfillWorkerCapacityTest`,
`EndToEndObservabilityDemoTest` (max in-flight never exceeds C). Residual
risk: production C and backfill share are unconfirmed placeholders (TODO #1);
a mis-tuned C shows up first as saturation-alert flapping or hot-path
backpressure, both observable.

## TODO register (owner-facing)

| # | Gap | Why it matters | Proposed owner/next step |
|---|-----|----------------|--------------------------|
| 1 | C = 20 is a placeholder pending downstream-owner confirmation | Wrong C either starves the engagement-load service or under-utilizes it; also drives saturation alert false positives | Engagement-load service owner; confirm published capacity, set `FanOutConfig.poolBound` |
| 2 | No logback/SLF4J bridge for `StructuredLogSink` | Production log pipeline expects SLF4J; demo sinks write to injected `Appendable` only | Platform team; small bridge appender mapping `LogEvent` to MDC/JSON encoder |
| 3 | No runtime scheduler tick for `AlertEvaluator` | Alerts only evaluate when a caller invokes `evaluate()`; no wall-clock periodicity in the shipped demo | Ops; host-scheduled tick (cron/sidecar) or embed in worker main loop |
| 4 | No real `MeterRegistry` binding/export | Metrics stay in-process (`SimpleMeterRegistry`); no scrape/push endpoint | Observability team; bind Prometheus/OTel registry in deployment assembly |
| 5 | No identifier-only trace spans | Constitution VI mentions traces; current scope ships logs + metrics only (spec FR-003 forbids content, spans would be identifiers only) | Add `fanout.publish_to_pending` span around the timer with `publish_id`/`product_id` attributes |
| 6 | No DLQ replay tool | FR-011 is store + alert only; replaying a dead-lettered unit is manual | Future phase; read `DeadLetterStore`, re-drive `RetryExecutor` with same idempotency keys |
| 7 | Percentile-histogram bucket population lags registry rotation | `histogramCounts()` empty until first window rotates; p99 exposed to alerts comes from adapter ring (authoritative) | Documented behavior; no action needed unless backend scrapes raw histogram |
| 8 | Fair-shard + permit-guard interaction under sustained mixed load | Named riskiest element; covered by tests at C=20 but production mix unknown | Revisit after TODO #1; load-test with confirmed C and backfill share |

## Traceability (T048)

FR-001…FR-015 and SC-001…SC-008 from `specs/005-fanout-observability-compliance/requirements.md`
map as follows (task → evidence):

| Requirement / scenario | Task(s) | Evidence |
|------------------------|---------|----------|
| FR-001…FR-003 (metrics object, per-publish histogram, no engagement dims) | T012–T018 | `MicrometerFanOutMetricsTest`, `FanOutEnginePublishTest`, tag allowlist |
| FR-004…FR-005 (structured logs, whitelist) | T019–T024 | `LogEvent`, `WhitelistValidatingLogSinkTest`, `JsonLineStructuredLogSinkTest` |
| FR-006…FR-008 (retry/DLQ/withdraw logs) | T025–T028 | `FanOutEngine`/`RetryExecutor`/`BackfillWorker` emissions, `InMemoryStructuredLogSink` |
| FR-009…FR-010 (README) | T041, T043 | Root `README.md` (8 sections + condensed compliance) |
| FR-011 (DLQ store + alert) | T012 (store count), T030–T038 (DLQ_NON_EMPTY), `DeadLetterStore` | `AlertEvaluatorTest`, `FanOutEngineDLQTest` |
| FR-012 (compliance artifact, no gaps papered over) | T042, T044 | This document + TODO register |
| FR-013…FR-015 (alert conditions, thresholds, edge-trigger) | T030–T038 | `AlertEvaluator`, `AlertEvaluatorTest` (11 tests) |
| SC-001…SC-008 | T012–T018, T039, T045 | Metric assertions, `EndToEndObservabilityDemoTest` (8 assertion groups), green `mvn verify` |
