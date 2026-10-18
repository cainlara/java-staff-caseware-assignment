# Contract: FanOutMetrics

**Port**: `com.example.fanout.ports.FanOutMetrics`

Counter/timer hooks for ingest, fan-out progress, downstream latency, retries,
dead-letter counts, and SLO measurement (constitution Principle VI). No-op
implementation now; real implementation is a later phase.

## Signature (conceptual)

```
void increment(String name, long delta, String... tags)
void recordTime(String name, Duration duration, String... tags)
```

## Idempotency

- Metrics are inherently non-idempotent observations (each call counts once).
  The port MUST NOT be used as a state store; correctness MUST NOT depend on
  metric values.

## Confidentiality invariant (hard rule)

- **Engagement content MUST NEVER appear in `name`, `delta`, `duration`, or
  `tags`.** Only identifiers (publish id, product id, firm id, engagement id,
  batch id) and measurements are permitted.
- Callers MUST NOT pass free-text content, template body fragments, or
  client-identifying material derived from engagement content.
- Contract test: recording fake captures all calls; assertions pin that test
  scenarios emit only allowlisted identifier/measurement shapes.

## Errors

- Null or blank `name` rejected. Null `duration` rejected. Negative `delta`
  rejected (counters go up; decrements use explicit negative-domain events
  later, not ad-hoc deltas).

## Contract test vectors

1. No-op impl: `increment`/`recordTime` accept valid calls, record nothing,
   never throw.
2. Null/blank name, null duration, negative delta -> rejected.
3. Recording fake round-trips captured names/tags for later assertions.
4. Documented invariant test: a fan-out-shaped scenario records only
   identifier-shaped tags (no content-shaped values) across the suite.
