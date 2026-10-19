# Research: Fan-Out Observability, README & Compliance

Phase 0 for spec 005. Every unknown below was resolved from the existing
codebase (phases 1–4), the constitution, and the spec. Decisions follow the
hexagonal, framework-free style already established.

## Context facts (from the codebase, not assumptions)

- `FanOutMetrics` port (`ports/FanOutMetrics.java`) already has
  `increment(name, delta, tags...)` and `recordTime(name, duration, tags...)`
  with a hard confidentiality invariant; implementations today are
  `NoOpFanOutMetrics` (main) and `RecordingFanOutMetrics` (test fake).
- Metric emission sites already exist: ingest (6 names), engine
  (`fanout.chunks.*`, `fanout.rows.resolved`, `fanout.retry`, `fanout.dlq`,
  `fanout.duplicate.run`, `fanout.cas.reapply`,
  `fanout.publish_to_pending` timer), permit guard (park, in-flight HWM),
  admission gate (reject counters), backfill (batch/engagement/halt counters).
- `IngestService` tags are single `k=v` strings (`"product_id=" + id`) while
  every other site uses `key, value` pairs — inconsistent; fixed here (D12).
- `fanout.publish_to_pending` currently measures **trigger receipt → run
  complete** (engine `start` instant), not publish `publishedAt` → last index
  write (D4).
- `PermitGuardedDownstream` tracks `inFlight` as an `AtomicInteger` but only
  emits a high-water-mark **counter** on new peaks — no live gauge, no
  completion counter (D2).
- `DeadLetterStore` has no `count()`; DLQ depth for gauge/alerts needs a
  source (D3).
- `IngestedPublish` checkpoint already carries `publishedAt` — the engine
  already loads it (`cursors.ingestedPublish(eventId)`), so true SLO-window
  freshness needs no trigger/schema change (D4).
- `FanOutCursor` has `totalChunks` + `lastCompletedChunkIndex` — checkpoint
  lag is derivable without new checkpoint fields (D5).
- Existing 40,000-row simulation test (`LargePublishSimulationTest`) proves
  the scale point; the demo reuses its fixture pattern (D11).
- `pom.xml` has no runtime dependencies at all (test starter only); engines
  are POJOs with injected ports (D1, D6).

---

## D1: Micrometer adoption shape

**Decision**: add `io.micrometer:micrometer-core` (compile scope, version
managed by the Spring Boot 3.5.10 parent BOM). New
`adapters/micrometer/MicrometerFanOutMetrics implements FanOutMetrics`
mapping:
- `increment` → `Counter` (created once per name+tags, held in a
  `ConcurrentHashMap` registry cache so Micrometer's weak references cannot
  drop meters);
- `recordTime` → `Timer` with `publishPercentileHistogram(true)` so
  `takeSnapshot().getValueAtPercentile(99)` supports the freshness-burn
  alert;
- new `gauge` (port default method, overridden here) → `Gauge` bound to a
  strongly-referenced `AtomicLong`/`DoubleSupplier` holder from the same
  cache.
Tests and the demo use `SimpleMeterRegistry` (ships inside micrometer-core).
No registry binding, scraping, or hosted backend is in scope (out of scope
per spec assumptions; worker emits, a backend consumes).

**Rationale**: Micrometer is the named expectation in the feature context;
the port already exists, so the adapter is the only Micrometer-aware main
code; strong-reference map is the documented cure for the classic
"gauge collected under GC" failure.

**Alternatives considered**:
- *Drop the port, use MeterRegistry directly in engines*: rejected — breaks
  the framework-free engine style and every existing test fixture.
- *Micrometer Observation API for traces*: rejected for now — spec requires
  traces carry identifiers only but delivers no trace backend; a
  `fanout.span` hook is recorded as TODO, not built (see compliance pass).
- *Dropwizard metrics*: rejected — Micrometer is the ecosystem default on
  Spring Boot and is already implied by the parent POM.

## D2: In-flight gauge and saturation signal

**Decision**: expose a read-only `int inFlight()` accessor on
`PermitGuardedDownstream`; the Micrometer adapter (or engine wiring) binds a
gauge `fanout.downstream.inflight` to that accessor. Keep the existing HWM
counter. Add `fanout.downstream.completed` (increment once per successful or
failed permit-held call, in the existing `finally` next to release) so the
saturation alert can distinguish "pinned at C with zero completions" from
"healthy throughput at C". No new threads.

**Rationale**: the HWM counter cannot answer "is it pinned NOW"; a live gauge
plus a completion counter is the minimal pair for the spec'd alert. Both are
read-only observations — the permit logic itself is untouched (capacity
invariant preserved).

**Alternatives considered**:
- *Derive in-flight from `C - budget.available()`*: rejected —
  `CapacityBudget.available()` is test-oriented and shared across real-time
  and backfill shares; the guard's own counter is the accurate source.
- *Only HWM counter, alert on HWM==C*: rejected — HWM==C is normal at peak
  throughput and would false-positive.

## D3: DLQ depth source

**Decision**: additive `long count()` on `DeadLetterStore` (append-only
stores make depth = number of stored records). In-repo implementors
(`FakeDeadLetterStore`, future real adapter) implement it; contract test
gains a depth case. Micrometer gauge `fanout.dlq.depth` bound to a holder
updated by the engine/guard at store time (they already increment
`fanout.dlq`) — or polled from the store by the alert evaluator. Evaluator
polls the store directly (source of truth); the gauge is best-effort for
dashboards.

**Rationale**: a store-side count cannot drift on crash/replay the way a
decorator-held counter would; append-only semantics make it trivial.
Constitution II: alerts read durable state, not side-channel counters.

**Alternatives considered**:
- *Decorator counting stores*: rejected — count lost on restart unless
  persisted, which would duplicate the store.
- *Only the `fanout.dlq` emission counter as depth*: rejected — that counts
  emissions, not current depth; replay/restart desynchronizes them.

## D4: Freshness definition and timer semantics

**Decision**: two timers.
1. `ingest.lag` (IngestService): `publishedAt` → checkpoint claim time.
   Requires an injectable `Clock` on `IngestService` — additive delegating
   constructor (`this(..., Clock.systemUTC())`).
2. `fanout.publish_to_pending` (engine): change measurement base from
   trigger-receipt `start` to the ingested publish's `publishedAt`
   (already loaded from `IngestedPublish`), measured at cursor COMPLETE /
   empty-run completion — i.e. publish → last index write, the true SLO
   window. Tags: `product_id`, `publish_id` (per-publish histogram, FR-002).
   Existing tests asserting the timer keep passing under `FakeClock` because
   the base shifts deterministically; tests that never set a distinguishable
   `publishedAt` see identical values (fixture timestamps == start instant).

**Rationale**: spec SLO is publish-to-pending; the old base silently omitted
bus+ingest latency. `IngestedPublish.publishedAt` is already durable in the
checkpoint — zero schema change.

**Alternatives considered**:
- *Add `publishedAt` to `FanOutTrigger`*: rejected — record change ripples to
  every constructor/test; the engine already loads `IngestedPublish`.
- *Keep trigger-receipt base and call it freshness*: rejected — dishonest vs
  the SLO (constitution VIII).
- *Measure ingest lag only and add it in the backend*: rejected — backend
  math is out of scope; worker must emit the SLO-ready measurement.

## D5: Checkpoint lag measurement

**Decision**: new port method
`default void gauge(String name, double value, String... tags)` (default
no-op — `NoOpFanOutMetrics` and old fakes compile unchanged;
`RecordingFanOutMetrics` overrides and records). Engine emits
`fanout.checkpoint.lag.chunks` = `totalChunks - 1 - lastCompletedChunkIndex`
after each chunk completion and on resume-from-cursor. Tags: `product_id`,
`publish_id` (bounded: active runs only, ~40/week).

**Rationale**: lag is a live value, not an event — a counter cannot express
it; the port stays two-method-shaped for events and gains one method for
state, which is the standard Micrometer split (Counter vs Gauge). Additive
default keeps every existing implementor source-compatible (constitution
VII).

**Alternatives considered**:
- *Encode lag as a fake timer (`recordTime` with lag duration)*: rejected —
  abuses the timer semantic and breaks p99 math on that meter.
- *Evaluator-only derivation from cursor reads*: rejected as the sole
  mechanism — dashboards need the gauge; evaluator still reads cursors for
  the stall alert (independent of emission success).

## D6: Structured JSON logging approach

**Decision**: framework-free.
- Port `StructuredLogSink` with
  `void log(LogLevel level, String event, Instant at, Map<IdentifierField, String> identifiers)`.
- `IdentifierField` enum = the spec whitelist exactly: `publish_id`,
  `product_id`, `firm_id`, `engagement_id`, `region`, `chunk_id`, `state`.
- `WhitelistValidatingLogSink` decorator: the enum IS the whitelist — any
  attempt to emit outside it cannot compile; a second runtime guard rejects
  blank values and content-shaped values (long free-text heuristic:
  value > 128 chars or containing newlines outside allowed id charset) so
  "content smuggled through an identifier slot" still fails the
  confidentiality test.
- `JsonLineStructuredLogSink` writes one flat JSON object per line
  (`ts`, `level`, `event`, identifier keys) to an injected `Appendable` via a
  ~40-line hand-rolled escaper (identifiers-only payload makes full JSON
  libraries unnecessary).
- Emission points (chunk/batch cadence only, never per-engagement on the hot
  path): ingest claim/duplicate/defer, run start/complete, chunk
  start/complete, retry, DLQ store, supersede marker, backfill batch
  admit/complete, halt observation, alert fire/clear. `engagement_id` only
  on DLQ/retry-error events.
- Tests: `InMemoryStructuredLogSink` captures events; the suite-wide
  `LogConfidentialityTest` runs representative paths and asserts every
  captured event uses only whitelist fields and content-shaped-value guard
  passes (FR-005).

**Rationale**: main code has zero logging framework today; adding logback +
encoder would be the first runtime framework dependency in the worker and
would fight the hexagonal style. The spec cares about whitelist + JSON
shape + build enforcement, not about the transport. A later adapter can
bridge the port to SLF4J/logback without touching engines (TODO in
compliance pass, not silent).

**Alternatives considered**:
- *logback-classic + logstash-logback-encoder*: rejected for now — two new
  runtime deps, XML config, and harder suite-wide capture; recorded as the
  recommended production bridge once the worker gains a runtime shell.
- *java.util.logging with a custom Formatter*: rejected — awkward JSON,
  global mutable state, still needs the same whitelist layer.
- *slf4j-api only with MDC*: rejected — MDC does not enforce a field
  whitelist; the test would have to scrape unstructured text.

## D7: Alert hooks architecture

**Decision**:
- `AlertSink` port: `void fire(AlertCondition condition, Instant at,
  Map<IdentifierField, String> identifiers)` and `void clear(...)`.
- `AlertEvaluator` (pure, framework-free): `List<AlertEvaluation>
  evaluate(Instant now)` — caller-driven, no internal threads or schedulers
  (a runtime shell adapter will tick it; in tests the fixture calls it).
  Reads injected snapshot sources:
  - freshness: Micrometer timer snapshot (p99 over the evaluation window)
    via a narrow `FreshnessSnapshot` functional dependency on the registry
    adapter (or pre-aggregated supplier in tests);
  - coverage: `FanOutCursorRepository` runs not COMPLETE whose
    `publishedAt` (via ingested publish record) is older than 24 h;
  - DLQ: `DeadLetterStore.count() > 0`;
  - backfill stall: `BackfillCursorRepository` newest checkpoint timestamp
    (already carried in checkpoint payload) older than the stall window
    while a backfill is enabled (halt excluded — intentionally halted
    backfill must not alert);
  - saturation: `fanout.downstream.inflight` gauge == C and
    `fanout.downstream.completed` delta == 0 over the window.
- `AlertThresholds` record holds thresholds+windows; defaults:
  freshness p99 > 60 s (window 5 min), coverage > 24 h (window 10 min),
  DLQ depth > 0 (window 1 min), backfill stall > 15 min without checkpoint
  advance (window 5 min), saturation 2 min pinned-at-C-zero-completions.
- `AlertThresholdProvider` port supplies current thresholds so operators
  change them without redeploy (FR-014); in-memory implementation with
  setter for tests/demo, documented as the config binding point.
- Evaluation state (was-active set) lives inside one evaluator instance;
  crash resets it — acceptable because alerts are edge-triggered
  notifications, not durable state (constitution II: correctness never
  depends on observability side channels).

**Rationale**: five conditions map 1:1 to spec; pure evaluator = trivially
deterministic tests with `FakeClock`; thresholds behind a port = safe
evolution without redeploy; no alert state persistence needed because a
re-fired alert on restart is safe and a missed clear re-fires.

**Alternatives considered**:
- *Spring @Scheduled + Actuator /metrics alerting*: rejected — no runtime
  shell in scope; Actuator/cloud alerts out of scope (spec: real
  infrastructure out of scope).
- *Persist alert state in CheckpointStore*: rejected — pollutes durable
  progress store with side-channel state; restart semantics equal.
- *Micrometer built-in percentile gauges only*: rejected — still needs a
  component to compare against thresholds and call the sink.

## D8: Metric cardinality and per-publish freshness

**Decision**: tag `publish_id` on `fanout.publish_to_pending` and
`fanout.checkpoint.lag.chunks` only (active/complete runs, ~40/week for the
timer; lag gauges removed or zeroed at COMPLETE). All other meters stay at
`product_id` / `firm_id` / enum-value cardinality. No `engagement_id`
metric tags anywhere (enforced by tag-allowlist test). README cost section
states the series arithmetic (~1,600 freshness series/year — trivial; firm
reject meters bounded by 4,000 firms × few reasons).

**Rationale**: FR-003 forbids engagement dimensions; publish-scoped meters
are required by FR-002 (per-publish histogram) and stay bounded by publish
rate, not engagement count. Backend retention/TTL is out of scope but named
in the README so the cost claim stays honest.

**Alternatives considered**:
- *No publish_id tags, compute p99 only in logs*: rejected — spec demands a
  freshness histogram (metrics object), not log scraping.
- *Engagement-level timing samples*: rejected — 800,000× cardinality bomb
  and a confidentiality footgun.

## D9: README strategy

**Decision**: rewrite the root `README.md` as the component README (~two
pages): purpose; architecture one-paragraph + pointer to in-repo docs for
detail but REQUIRED knowledge inlined (tradeoffs list from spec FR-009);
known weaknesses; riskiest element (fair-shard scheduler + permit-guard
interaction under mixed real-time/backfill load is named, with reasoning in
compliance pass); C = 20 placeholder callout; cost arithmetic table;
verify + demo commands; telemetry reference (metric names, log fields,
alert conditions). No "see external doc" for required information — existing
`docs/design.md` may be linked as *further* reading only.

**Rationale**: current root README documents phase 2 only and contradicts
the delivered code (phases 3–4 exist); spec FR-009/FR-010 demand a
self-contained two-page README; one README at repo root is where reviewers
land.

**Alternatives considered**:
- *New `docs/worker-readme.md`, leave root README*: rejected — split
  attention; root README is stale and must be replaced regardless.
- *Keep appending phase sections to old README*: rejected — would exceed two
  pages of accumulated history instead of a purpose-written component doc.

## D10: Compliance pass artifact

**Decision**: implementation delivers `docs/compliance.md` — table of all
nine constitution principles with verdict + evidence (file/class level), a
"riskiest element" section, and an explicit numbered `TODO` register (every
gap, e.g. trace-span hook, logback bridge, real registry binding, DLQ replay
tool, C confirmation). README carries a condensed compliance section
referencing the same TODOs inline (self-contained summary, not a pointer
for required content). Spec checklist gains the compliance evidence rows at
implementation time via tasks, not now.

**Rationale**: constitution VIII requires ambiguity as explicit TODO; the
spec's story 6 requires recorded outcomes per principle; Markdown in-repo
matches every previous phase's evidence style.

**Alternatives considered**:
- *Compliance rows only in spec checklists*: rejected — checklists validate
  specs, not shipped code; README must stand alone for hand-off.
- *Issue-tracker TODOs*: rejected — spec assumption says tracked in repo
  artifacts, and out-of-band trackers violate self-containment.

## D11: End-to-end demo test shape

**Decision**: `EndToEndObservabilityDemoTest` (fanout package, beside
`LargePublishSimulationTest`): fixture wires `SeededEngagementEnumerator`
(40,000 refs, deterministic), in-memory checkpoint/DLQ/index stores,
`FakeCapacityBudget(C=20)`, `FakeClock`, `MicrometerFanOutMetrics` +
`SimpleMeterRegistry`, `InMemoryStructuredLogSink`, `InMemoryAlertSink`.
Flow: `IngestService.ingest(TemplatePublished)` → engine `runPublish` →
assert: (a) index writer rows == eligible count; (b) downstream client call
log empty on hot path; (c) in-flight gauge never exceeded 20 (sampled via
recording budget/guard or HWM meter == ≤ 20); (d)
`fanout.publish_to_pending` timer count ≥ 1 and snapshot max > 0 (freshness
measurable); (e) log sink events all whitelisted. Runs in seconds on the
existing test stack; no real I/O.

**Rationale**: FR-008/SC-005; reuses proven fixtures; proves observability
wraps the REAL engine, not a toy; complements (not replaces) the existing
large simulation.

**Alternatives considered**:
- *Extend LargePublishSimulationTest in place*: rejected — keep the capacity
  proof focused; demo asserts a different contract (metrics/logs/alerts).
- *Spring Boot @SpringBootTest context demo*: rejected — no runtime shell
  exists; context test would only prove wiring of fakes.

## D12: Ingest tag-format normalization

**Decision**: change the six `IngestService` emissions from single
`"product_id=" + id` strings to `key, value` pairs
(`"product_id", id`), matching every other emitter and the Micrometer
adapter's pair parser. Metric **names** unchanged; the fan-out metrics
contract test's tag assertions updated in the same release.

**Rationale**: two incompatible tag encodings would force the adapter to
support both (and silently double-tag); fixing the minority sites now is
cheap because no real registry has consumed them yet.

**Alternatives considered**:
- *Adapter accepts both encodings forever*: rejected — permanent ambiguity,
  violates one-name-one-meaning, and hides a bug class.

## D13: Where alerts read freshness p99

**Decision**: `MicrometerFanOutMetrics` configures the freshness timer with
`publishPercentileHistogram`; `AlertEvaluator` receives a
`FreshnessP99` functional source (in production: adapter method reading
`takeSnapshot().getValueAtPercentile(99)` over the window; in tests: a
scripted supplier). Window semantics: sliding — SimpleMeterRegistry keeps
cumulative percentiles, so the evaluator uses an explicitly **windowed**
timer (Micrometer `DistributionStatisticConfig` expiry = evaluation window)
or a small in-adapter ring of recent durations (last N=1,024 samples).
Ring chosen: deterministic, no registry-config subtleties, bounded memory.

**Rationale**: p99 over "all time" would mask a recent burn; a bounded ring
of per-publish durations gives exact p99 over recent publishes (≤ window
count) with zero external deps.

**Alternatives considered**:
- *Cumulative percentiles + delta between evaluations*: rejected —
  SimpleMeterRegistry deltas are lossy for percentiles.
- *Only per-publish single-sample timers, alert on each sample > 60 s*:
  rejected — spec says p99 per publish window, not max-sample; but the ring
  also exposes each sample, so both views are testable from the same data.

## Open items (explicit, none blocking)

- **TODO (post-worker shell)**: bridge `StructuredLogSink` to SLF4J/logback
  JSON and bind `AlertEvaluator.evaluate` to a scheduler + real
  `MeterRegistry` when the worker gains a runtime shell. Recorded in
  `docs/compliance.md`, not silently omitted.
- **TODO (downstream owner)**: confirm C = 20; `CapacityBudget` already
  configurable — README carries the placeholder warning.
- **TODO (trace spans)**: identifier-only OpenTelemetry spans deferred until
  a trace backend exists; ports stay span-free (no hidden coupling).
