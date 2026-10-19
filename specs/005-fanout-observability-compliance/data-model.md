# Data Model: Fan-Out Observability, README & Compliance

Phase 1 design. All types are framework-free Java records/enums in the
existing hexagonal layout. Schema changes are additive only (constitution
VII: expand across at least one release).

## New value types

### `IdentifierField` (enum) — `observability/`

The log-field whitelist (spec FR-004). Closed set; the type system is the
enforcement mechanism.

| Value | Emitted on | Notes |
|-------|------------|-------|
| `publish_id` | ingest, run, chunk, freshness, alerts | bus publish identity |
| `product_id` | everywhere | ≤ ~40 distinct |
| `firm_id` | admission, backfill, DLQ, alerts | ≤ ~4,000 distinct |
| `engagement_id` | **error/DLQ/retry paths only** | never on hot-path success logs; never a metric tag |
| `region` | row-shaped events, alerts | residency marker |
| `chunk_id` | chunk start/complete, checkpoint lag | positional chunk index (int as string) |
| `state` | state transitions | `pending`, `declined`, `superseded`, `dead_lettered`, `halted`, `complete`, alert `active`/`cleared` |

### `LogLevel` (enum) — `observability/`

`INFO`, `WARN`, `ERROR`. No DEBUG/TRACE (hot-path volume discipline).

### `LogEvent` (record) — `observability/`

| Field | Type | Validation |
|-------|------|------------|
| `level` | `LogLevel` | non-null |
| `event` | `String` | non-blank, dotted lower_snake (`ingest.claimed`, `fanout.chunk.completed`, `alert.fired`, …) |
| `at` | `Instant` | non-null (injected clock) |
| `identifiers` | `Map<IdentifierField, String>` | non-null map; values non-blank; content-shaped values rejected (length ≤ 128, no CR/LF, printable) |

Invariant: `LogEvent` cannot carry a non-whitelisted key (enum-typed map).
Invariant: no `LogEvent` field holds engagement content — identifiers and
measurements only (hard rule, spec FR-013).

### `AlertCondition` (enum) — `observability/`

| Value | Fires when | Threshold (default) | Evaluation window (default) |
|-------|------------|---------------------|-----------------------------|
| `FRESHNESS_BURN` | per-publish freshness p99 > 60 s | `Duration.ofSeconds(60)` | 5 min (ring of recent samples) |
| `COVERAGE_BREACH` | run not COMPLETE and age since `publishedAt` > 24 h | `Duration.ofHours(24)` | 10 min tick |
| `DLQ_NON_EMPTY` | `DeadLetterStore.count() > 0` | depth ≥ 1 | 1 min tick |
| `BACKFILL_STALLED` | backfill enabled, not halted, newest checkpoint older than window | `Duration.ofMinutes(15)` | 5 min tick |
| `DOWNSTREAM_SATURATION` | in-flight == C and zero completions over window | C (from `CapacityBudget`/config, default 20) | 2 min |

### `AlertThresholds` (record) — `observability/`

| Field | Type | Default |
|-------|------|---------|
| `freshnessP99Limit` | `Duration` | 60 s |
| `freshnessWindow` | `Duration` | 5 min |
| `coverageAgeLimit` | `Duration` | 24 h |
| `coverageWindow` | `Duration` | 10 min |
| `dlqWindow` | `Duration` | 1 min |
| `backfillStallLimit` | `Duration` | 15 min |
| `backfillWindow` | `Duration` | 5 min |
| `saturationWindow` | `Duration` | 2 min |

Validation: all durations positive; `coverageAgeLimit` ≥ 1 h (guard against
misconfiguring the coverage SLO into noise). Runtime-swappable via
`AlertThresholdProvider` port (FR-014) — no redeploy.

### `AlertEvaluation` (record) — `observability/`

| Field | Type | Notes |
|-------|------|-------|
| `condition` | `AlertCondition` | |
| `active` | `boolean` | true = fire, false = cleared |
| `at` | `Instant` | evaluation instant |
| `identifiers` | `Map<IdentifierField, String>` | affected publish/product/firm ids; detail measurements (depth, lag) carried as plain string values on measurement-safe keys in the sink detail, not as fields |

### `AlertSink` (port) — `ports/`

```
void fire(AlertCondition condition, Instant at, Map<IdentifierField, String> identifiers);
void clear(AlertCondition condition, Instant at, Map<IdentifierField, String> identifiers);
```
Implementations: `InMemoryAlertSink` (records fire/clear order for tests;
deduplicates repeats of the same active edge — evaluator only emits edges).
Confidentiality: identifiers only; content never reaches the sink.

### `StructuredLogSink` (port) — `ports/`

```
void log(LogLevel level, String event, Instant at, Map<IdentifierField, String> identifiers);
```
Implementations: `WhitelistValidatingLogSink` (decorator, value guards),
`JsonLineStructuredLogSink` (flat JSON line to injected `Appendable`),
`InMemoryStructuredLogSink` (test capture).

### `AlertThresholdProvider` (port) — `ports/`

```
AlertThresholds current();
```
Implementation for tests/demo: `InMemoryAlertThresholdProvider` (settable).
Runtime shell (out of scope) binds this to config; contract documented in
`contracts/alert-hooks.md`.

## Additive changes to existing types

### `FanOutMetrics` (port)

```java
/** Optional gauge hook: sets the current value of a named gauge. Default no-op. */
default void gauge(String name, double value, String... tags) { }
```
- `NoOpFanOutMetrics`: override validates args, records nothing.
- `RecordingFanOutMetrics`: override records `GaugeCall` entries;
  `gaugeNames()` accessor for allowlist tests.
- `MicrometerFanOutMetrics`: binds/updates strongly-referenced gauge holders.

Mandated emission names added by this feature (allowlist deltas):

| Name | Kind | Tags | Emitter |
|------|------|------|---------|
| `ingest.lag` | timer | `product_id` | `IngestService` |
| `fanout.downstream.inflight` | gauge | `product_id` | permit guard (bound to `inFlight()`) |
| `fanout.downstream.completed` | counter | `product_id` | permit guard `finally` |
| `fanout.checkpoint.lag.chunks` | gauge | `product_id`, `publish_id` | engine, chunk completion + resume |
| `fanout.publish_to_pending` | timer | `product_id`, `publish_id` | engine (base changed to `publishedAt`, see D4) |
| `fanout.dlq.depth` | gauge | (none) | best-effort, bound to store count |

### `DeadLetterStore` (port)

```java
/** Current number of stored records (depth). */
long count();
```
`FakeDeadLetterStore` implements via internal list size. Contract test adds:
store n records → `count() == n`; empty store → 0 (alert sees real zero,
spec edge case).

### `PermitGuardedDownstream`

- `int inFlight()` — read-only accessor over the existing `AtomicInteger`
  (gauge binding source; no behavior change).
- Emit `fanout.downstream.completed` once per permit-held call completion in
  the existing `finally` (success or failure) — pairs with the gauge for the
  saturation alert.

### `IngestService`

- New constructor parameter `Clock clock` with delegating
  `IngestService(progress, triggers, metrics)` → `Clock.systemUTC()`
  (existing call sites unchanged).
- New timer `ingest.lag` = `clock.instant() - event.publishedAt()` at claim
  success (publish and withdraw paths; withdraw uses `withdrawnAt`).
- Tag fix (D12): `"product_id", id` pairs replace `"product_id=" + id`.

### `FanOutEngine`

- `recordPublishTimer` measures from `IngestedPublish.publishedAt` (already
  loaded) instead of the run-start instant; tags add `publish_id`.
- After each persisted chunk advance (and on resume when `startChunk > 0`):
  `metrics.gauge("fanout.checkpoint.lag.chunks", remaining, "product_id", …,
  "publish_id", …)`; on COMPLETE emit lag 0 once (so dashboards see the run
  finish, then the holder can be dropped by the adapter's run-scope cache).

## Entity relationship (observability view)

```
TemplatePublished ──ingest──▶ FanOutTrigger ──▶ FanOutEngine run
      │                            │                  │
      │ publishedAt                │ eventId          ├─▶ FanOutCursor (chunk lag source)
      ▼                            ▼                  ├─▶ DeadLetterStore (depth source)
 IngestedPublish             StructuredLogSink        ├─▶ FanOutMetrics (Micrometer adapter)
 (checkpoint: source           (whitelist)             │      ├─ timers: ingest.lag,
  of publishedAt for                                    │      │   fanout.publish_to_pending
  freshness base)                                      │      ├─ gauges: inflight, checkpoint.lag,
                                                       │      │   dlq.depth
                                                       │      └─ counters: chunks.*, retry, dlq,
                                                       │          downstream.completed, duplicate.*
                                                       ▼
                                                AlertEvaluator ──edges──▶ AlertSink
                                                 (reads timer snapshot,
                                                  cursor repo, DLQ count,
                                                  backfill cursor, thresholds)
```

No state flows backward: metrics/logs/alerts never feed correctness paths
(ports' javadoc invariant; demo asserts duplicate delivery is idempotent
regardless of observability).

## State transitions (alert lifecycle)

```
(condition false) --evaluate--> no edge (stays cleared)
(condition false, was active) --evaluate--> CLEAR edge
(condition true, was cleared) --evaluate--> FIRE edge
(condition true, stays) --evaluate--> no edge (no alert spam)
```
Evaluator-internal "was active" set resets on process restart → next true
condition re-fires (safe; notification-side effect only).

## Validation rules summary

- Metric names non-blank; deltas ≥ 0; tags even-length key/value pairs; no
  `engagement_id` tag key (adapter + contract test reject).
- Log identifiers: enum keys only; values non-blank, ≤ 128 chars, no CR/LF.
- Thresholds: positive durations; coverage age ≥ 1 h.
- `DeadLetterStore.count()` ≥ 0; `PermitGuardedDownstream.inFlight()` in
  `[0, C]` at all times (invariant asserted by existing permit tests +
  demo high-water check).
