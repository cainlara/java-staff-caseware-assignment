# Quickstart: Fan-Out Observability, README & Compliance

Validation guide for spec 005. Prerequisites: JDK 21, Maven, repo checkout
on branch `observability`. No cloud, no brokers, no external registries —
everything runs in-process against fakes (spec FR-015).

## 1. Full verification (primary gate)

```bash
mvn verify
```

Expected: BUILD SUCCESS. Runs all phase 1–4 tests plus this feature's:

| Suite | Proves |
|-------|--------|
| `MicrometerFanOutMetricsTest` | port→meter mapping, tag rules, no `engagement_id` tags, windowed p99 ring |
| `WhitelistValidatingLogSinkTest` | field whitelist + content-shaped-value guards throw with field named |
| `JsonLineStructuredLogSinkTest` | deterministic flat-JSON lines (golden assertions) |
| `LogConfidentialityTest` | suite-wide capture: no unexpected fields, no content-shaped payloads (FR-005) |
| `AlertEvaluatorTest` | all 5 conditions fire exactly once, clear exactly once, halt-exclusion, threshold hot-swap |
| `EndToEndObservabilityDemoTest` | 40,000-engagement demo (below) |

## 2. Run the demo alone

```bash
mvn test -Dtest=EndToEndObservabilityDemoTest
```

Expected assertions (contracts/e2e-observability-demo.md):

1. 40,000 pending index rows written (eligible-set equality; zero DLQ).
2. Zero downstream load calls on the publish hot path.
3. In-flight never exceeded C = 20 (gauge + sampled accessor).
4. `fanout.publish_to_pending` timer count = 1, tagged with publish/product
   ids; `ingest.lag` recorded; `freshnessP99()` readable — freshness
   measurable end to end.
5. Chunk counters 80/80; checkpoint-lag gauge ends at 0.
6. All log events whitelisted; log volume O(chunks), never O(rows).
7. Zero spurious alert fires.
8. Duplicate re-delivery: duplicate counters increment, row count unchanged.

## 3. Prove the confidentiality tripwire

Temporarily add a bad log field in any engine path (e.g. emit a free-text
message or an identifier outside `IdentifierField`), then:

```bash
mvn test -Dtest=LogConfidentialityTest
```

Expected: FAILURE naming the offending event/field. Revert the edit.
This is the FR-005 build gate working as designed.

## 4. Exercise alerts with retuned thresholds (FR-014 evidence)

`AlertEvaluatorTest` covers this programmatically; to see the seam: supply
an `InMemoryAlertThresholdProvider` with a tighter `freshnessP99Limit`,
drive a slow publish under `FakeClock`, call `evaluate(now)` past the
window, and observe the `FRESHNESS_BURN` fire edge in `InMemoryAlertSink`
— no redeploy, only provider state.

## 5. Read the delivered docs

- Root `README.md` — the component doc (purpose, tradeoffs, weaknesses,
  C = 20 placeholder, cost arithmetic, run instructions).
- `docs/compliance.md` — constitution pass with evidence + explicit TODO
  register (every gap named, none silent).

## Expected total runtime

Seconds to low minutes on a laptop (deterministic fakes, no sleeps, 40k
demo is in-memory). If the suite needs more than ~2 minutes, a sleep-based
sync regressed — investigate before merging.
