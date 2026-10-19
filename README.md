# fanout-worker

Template-publish fan-out worker for pending-template-update indicators. A
product team publishes a template version; this worker fans that publish out
to the affected engagement files (~40 to ~40,000 per firm, up to 40,000 rows
per publish), writing **Pending Update Index** rows (metadata only). The event
bus is at-least-once; every consumer is idempotent. The hot path never touches
engagement content.

## What it does

1. **Ingest** (`IngestService`): claims each `TemplatePublished` /
   `TemplateWithdrawn` in an external `CheckpointStore` (the claim *is* the
   processed record), resolves deferred withdraws, emits one in-process
   trigger per new event.
2. **Fan out** (`FanOutEngine`): materializes the eligible engagement set in
   contract order, partitions it into positional chunks (default 500), runs
   them through a fair-shard scheduler with live admission gates, writes
   idempotent `PENDING` rows through a CAS `IndexWriter`, and advances an
   external chunk cursor after every chunk so a crash resumes from the
   durable prefix.
3. **Backfill** (`BackfillWorker`): incremental, rate-limited catch-up for
   engagement files that pre-date the feature — per-firm cursors, one
   downstream load per engagement behind the shared capacity budget, batch
   checkpoints.
4. **Withdraw supersede**: a durable withdrawn-version marker precedes any
   supersede write; affected rows move to the next effective version (or
   `CREATED`) with history preserved.
5. **Observability** (this feature): Micrometer metrics, whitelist-validated
   structured logs, and an edge-triggered alert evaluator — identifiers and
   measurements only, never engagement content.

## Tradeoffs (named, deliberate)

- **Index-only hot path**: publish fan-out writes metadata rows only. Zero
  downstream engagement loads on the hot path (asserted per test). The
  consequence: a pending indicator can exist before any content-derived work
  (summary, diff) — a later phase.
- **Capacity budget + rate limiter, C = 20**: all downstream loads (backfill,
  revalidation) pass one counted permit pool (`CapacityBudget`) inside
  `PermitGuardedDownstream`; pauses on exhaustion, abandons on halt.
  **C = 20 is a placeholder assumption pending downstream-owner
  confirmation** (TODO register, `docs/compliance.md` #1). The engagement-load
  service is owned by another team at ~1 minute per call.
- **Chunk-cursor external checkpoints**: progress truth lives in
  `CheckpointStore`, not process memory. Crash-safe and resumable; the cost
  is a rewrite per chunk advance and one extra store dependency on the hot
  path.
- **Ancestry-range decline pins**: declined decisions pin a specific template
  version or ancestry range so accumulated updates resolve deterministically;
  supersede preserves decision/pinnedRanges from the stored row.
- **Separate real-time and backfill pools**: publish fan-out never competes
  with backfill for capacity (backfill takes a configured share of C);
  halt/staged enablement can pause either side without redeploy.
- **At-least-once + idempotency over exactly-once**: duplicate publishes,
  duplicate triggers, and duplicate runs are counted and dropped, never
  errors; row writes are CAS-idempotent on `(engagementId, offeredVersion,
  state)`.

## Known weaknesses

- No real `MeterRegistry` binding/export (demo uses `SimpleMeterRegistry`);
  no logback/SLF4J bridge for `StructuredLogSink` (JSON-line sink writes to
  an injected `Appendable`).
- `AlertEvaluator` is caller-driven; no runtime scheduler tick ships yet.
- No identifier-only trace spans (TODO register).
- No DLQ replay tool (FR-011 is store + alert only).
- Percentile-histogram buckets on `fanout.publish_to_pending` populate after
  the registry's time-window rotation; the freshness p99 exposed to alerts
  comes from the adapter-side ring, which is authoritative.

**Riskiest element**: the fair-shard scheduler + permit-guard interaction
under mixed real-time/backfill load — fairness across firms, halt
abandonment, and capacity shares all meet in one control loop; reasoning and
evidence in `docs/compliance.md`.

## Cost arithmetic (constitution IV)

Scale: 4,000 firms · 800,000 active engagement files · 40 products ·
~1 update per product per week (~40 publishes/week).

| Item | Arithmetic | Monthly estimate |
|------|------------|------------------|
| Worker compute (this component) | existing baseline | ~USD 150 |
| Freshness metric series | ~40 active publishes/week → ~1,600 series/year at `publish_id` cardinality; all other meters at `product_id`/`firm_id`/enum cardinality; **zero** `engagement_id` tags | < USD 5 (time-series backend retention/TTL is the backend's cost model, named honestly) |
| Checkpoint/index storage | 40 publishes/week × 40,000 rows × ~200 B row metadata, append/CAS rewrite per chunk (80 rewrites/publish) | < USD 10 |
| Structured logs | ≤ 4×chunks + constant ≈ 340 events/publish × 40/week ≈ 14k events/week, identifier-only lines (~150 B) | < USD 2 |
| LLM/inference | none in this component | USD 0 |
| **Total** | worker baseline + observability overhead | **~USD 167 ≪ USD 5,000 budget** |

Where cost and freshness conflict: freshness is bought with a per-publish
histogram (bounded by publish rate), not per-engagement timing (an 800,000×
cardinality bomb and a confidentiality footgun — rejected, research D8).

## Run

```bash
mvn verify                                          # sole verification gate
mvn test -Dtest=EndToEndObservabilityDemoTest       # runnable observability demo (40k rows)
```

## Telemetry reference

**Metrics** (tags: `product_id`, `firm_id`, `publish_id` on freshness/checkpoint
meters only, enum-value reason tags; never `engagement_id`):

- Ingest: `ingest.published`, `ingest.withdrawn`, `ingest.resolved`,
  `ingest.deferred`, `ingest.duplicate` (counters), `ingest.lag` (timer).
- Fan-out: `fanout.chunks.started`, `fanout.chunks.completed`,
  `fanout.rows.resolved`, `fanout.cas.reapply`, `fanout.duplicate.run`,
  `fanout.dlq`, `fanout.retry`, `fanout.backpressure`,
  `fanout.downstream.completed` (counters); `fanout.publish_to_pending`
  (timer, p99 freshness, tagged `publish_id`+`product_id`);
  `fanout.checkpoint.lag.chunks`, `fanout.downstream.inflight`,
  `fanout.dlq.depth` (gauges); `fanout.downstream.inflight.hwm`;
  `fanout.metrics.dropped`; admission rejects
  `fanout.admit.reject.{halt,product,firm}`.
- Backfill: `backfill.batches.admitted`, `backfill.batches.completed`,
  `backfill.engagements.written`, `backfill.engagements.noop`,
  `backfill.engagements.dlq`, `backfill.halt.abandoned` (counters),
  `backfill.halt.drain` (timer); repair share mirrors the permit meters.

**Structured logs** — whitelist fields only (`IdentifierField` enum is the
whitelist): `publish_id`, `product_id`, `firm_id`, `region`, `chunk_id`,
`state`, plus `engagement_id` on `fanout.retry` / `fanout.dlq` error paths
only. Values ≤ 128 chars, no CR/LF/NUL, no free-text shape; violations throw
before a sink sees them (`WhitelistValidatingLogSink`). Events:
`ingest.claimed|duplicate|deferred`, `fanout.run.started|completed`,
`fanout.chunk.started|completed`, `fanout.retry`, `fanout.dlq`,
`fanout.supersede.marked`, `backfill.batch.admitted|completed`,
`backfill.halt.observed`, `alert.fired.<condition>`,
`alert.cleared.<condition>`. Volume: chunk/batch cadence, never per-row.

**Alerts** (`AlertEvaluator`, edge-triggered, thresholds hot-swappable via
`AlertThresholdProvider`):

| Condition | Fires when | Default limit / window |
|-----------|------------|------------------------|
| `FRESHNESS_BURN` | ring p99 of `fanout.publish_to_pending` > limit (no data never fires) | 60 s / 5 m |
| `COVERAGE_BREACH` | non-COMPLETE run with `publishedAt` older than limit | 24 h / 10 m |
| `DLQ_NON_EMPTY` | `DeadLetterStore.count() > 0` | — / 1 m |
| `BACKFILL_STALLED` | enabled ∧ ¬halted ∧ newest checkpoint older than limit (empty history fires after the limit) | 15 m / 5 m |
| `DOWNSTREAM_SATURATION` | in-flight == C over the window with zero completions (≥ 2 spanning samples) | — / 2 m |

## Constitution compliance (condensed)

| # | Principle | Verdict |
|---|-----------|---------|
| I | Data ownership & boundaries | Pass — ports only; no engagement-store access |
| II | Idempotent & resilient events | Pass — claim/cursor/CAS idempotency; observability is a read-only side channel |
| III | Capacity-aware fan-out | Pass — chunk-cursors, permit guard C=20, backpressure pause |
| IV | Cost-aware design | Pass — arithmetic above; ~USD 167 ≪ 5,000 |
| V | Confidentiality & residency | Pass — whitelist logs, no content tags, region markers only |
| VI | Observability & SLOs | Pass — freshness/coverage SLOs measured, five alerts |
| VII | Safe evolution | Pass — additive ctors, expand-only schemas, staged gates/halt |
| VIII | Honest tradeoffs | Pass — weaknesses + riskiest element + TODO register |
| IX | Import conventions | Pass — simple names + explicit imports (grep-audited) |

Open gaps are tracked as a numbered TODO register in `docs/compliance.md`
(C=20 confirmation, SLF4J bridge, alert tick, registry export, trace spans,
DLQ replay tool).

Further reading (not required): `docs/design.md`, `docs/diagrams/`,
`specs/005-fanout-observability-compliance/` (spec, contracts, research).
