# Contract: End-to-End Observability Demo (FR-008)

**Component**: `com.example.fanout.fanout.EndToEndObservabilityDemoTest`
(test-scope; the README's runnable demo).

## Wiring (all in-memory, FakeClock, no I/O, no Spring context)

| Slot | Implementation |
|------|----------------|
| `EngagementEnumerator` | `SeededEngagementEnumerator` — 40,000 refs, deterministic seed, multi-firm |
| `EventSource` / ingest | `IngestService` + `FakeEventSource` + `InMemoryCheckpointStore` + `RecordingFanOutTriggerSink` |
| Engine stores | `InMemoryCheckpointStore`, `FakeDeadLetterStore`, `InMemoryIndexWriter`, `FakeCapacityBudget(C=20)`, `FakeClock`, `SeededJitter`, `TestTaskRunner` (existing `FanOutFixture` pattern) |
| Metrics | `MicrometerFanOutMetrics` + `SimpleMeterRegistry` |
| Logs | `WhitelistValidatingLogSink` → `InMemoryStructuredLogSink` |
| Alerts | `InMemoryAlertSink`, `InMemoryAlertThresholdProvider` (defaults) |
| Downstream | `FakeDownstreamLoadClient` (call log retained; hot path must leave it empty) |

## Script

1. Build a `TemplatePublished` with `publishedAt = clock.instant()`.
2. `IngestService.ingest(...)` → trigger emitted; `ingest.lag` timer count = 1.
3. Advance FakeClock through the run (chunk completions).
4. `engine.runPublish(trigger)` to completion (40,000 rows / chunkSize 500 →
   80 chunks).
5. Run `AlertEvaluator.evaluate(now)` once at the end (expect no fires —
   healthy run).

## Assertions (SC-005)

1. **Rows written**: `InMemoryIndexWriter` pending rows == 40,000 (eligible
   set equality; zero dead-letters for the clean path).
2. **Zero hot-path downstream calls**: downstream call log empty after the
   whole publish (index-only hot path).
3. **In-flight ≤ C**: `PermitGuardedDownstream.inFlight()` sampled after
   every chunk ≤ 20; `fanout.downstream.inflight` gauge high-water from the
   registry ≤ 20; (hot path acquires no permits, so this mainly guards the
   engine's eligibility/version loads if any — assert still holds).
4. **Freshness measurable**: `fanout.publish_to_pending` timer count == 1,
   tagged `publish_id`/`product_id`; duration == FakeClock delta
   (deterministic); `MicrometerFanOutMetrics.freshnessP99()` == that sample;
   `ingest.lag` count == 1.
5. **Progress/coverage**: `fanout.chunks.started` == `fanout.chunks.completed` == 80;
   `fanout.rows.resolved` total == 40,000; checkpoint lag gauge last value 0.
6. **Log confidentiality + volume**: every captured `LogEvent` passes the
   whitelist/content guards; total log events ≤ 4×80 + 20 (chunk cadence
   bound — never per-row).
7. **No spurious alerts**: `InMemoryAlertSink` received zero fires.
8. **Duplicate safety under observation**: re-deliver the same
   `TemplatePublished`; ingest duplicate counter +1; index rows unchanged
   (40,000) — observability path is idempotency-neutral (constitution II).

## Non-goals

Not a replacement for `LargePublishSimulationTest` (that one owns crash/
fairness/property proofs); this demo owns the observability contract only.
No real registry export, no alert transport.
