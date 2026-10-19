# Contract: MicrometerFanOutMetrics (FanOutMetrics v3)

**Component**: `com.example.fanout.adapters.micrometer.MicrometerFanOutMetrics`
implements `com.example.fanout.ports.FanOutMetrics` (port gains one default
method: `gauge`).

## Responsibilities

Map every port call to a Micrometer meter, identifier-only, with no
correctness coupling (a registry failure MUST NOT fail fan-out work — the
adapter catches registry exceptions, drops the sample, and counts
`fanout.metrics.dropped` best-effort; hard failures only on programming
errors: null name, negative delta).

## Mapping

| Port call | Meter | Notes |
|-----------|-------|-------|
| `increment(name, delta, tags)` | `Counter` (delta added) | meter cached per `(name, sorted tags)` in a `ConcurrentHashMap` (strong refs — Micrometer holds weak refs) |
| `recordTime(name, duration, tags)` | `Timer` | cached likewise; freshness timer additionally `publishPercentileHistogram` + adapter-side duration ring (N = 1,024) for windowed p99 |
| `gauge(name, value, tags)` | `Gauge` | first call binds to a strong `AtomicLong`/`DoubleHolder`; later calls `set` the holder |

## Tag rules

- Pairs: `key, value` (D12 normalization). Odd-length varargs rejected
  (`IllegalArgumentException`).
- Allowed keys: `product_id`, `firm_id`, `publish_id`, `unit_kind`,
  `reason`, `side`, `run_kind`, `share`. **`engagement_id` is forbidden**
  — adapter throws `IllegalArgumentException` (cardinality + confidentiality,
  spec FR-003/FR-012).
- Values non-blank.

## Mandated meters (this feature, additive to phase 3/4 allowlists)

Counters: existing names unchanged, plus `fanout.downstream.completed`
(permit-guard `finally`).

Timers: `ingest.lag` (`product_id`);
`fanout.publish_to_pending` (`product_id`, `publish_id`) — base =
`publishedAt` → last index write instant (SLO window, research D4).

Gauges: `fanout.downstream.inflight` (bound to
`PermitGuardedDownstream::inFlight`, tags `product_id` optional — binding
source is global; emit with no tags to avoid per-product fake splits);
`fanout.checkpoint.lag.chunks` (`product_id`, `publish_id`);
`fanout.dlq.depth` (no tags; best-effort holder updated at store-time
sites; authoritative depth = `DeadLetterStore.count()`).

## Windowed p99 (freshness-burn source)

`MicrometerFanOutMetrics.freshnessP99()` returns p99 over the last 1,024
recorded `fanout.publish_to_pending` durations (ring buffer, thread-safe).
`AlertEvaluator` receives this via the `FreshnessP99` functional source.
Empty ring → p99 undefined → evaluator treats as "no data" (never fires).

## Cardinality contract test vectors

1. Run ingest+publish+retry+DLQ+backfill paths; assert no meter tag key is
   `engagement_id`.
2. Assert meter name set ⊆ documented allowlist (phase 3/4 lists + the five
   additions above + `fanout.metrics.dropped`).
3. Freshness timer count == number of completed runs; snapshot
   `getValueAtPercentile(99)` ≥ max sample sanity, ring p99 == manual p99 of
   the same samples (deterministic under FakeClock).
4. Gauge `fanout.downstream.inflight` tracks guard accessor at sampled points.
5. Duplicate publish delivery: counters show `fanout.duplicate.run` or
   `ingest.duplicate`; row count in `IndexWriter` fake unchanged.

## Non-goals

No registry bootstrap, scraping endpoint, backend export, or dashboards
(real infrastructure out of scope). No per-engagement meters. No changes to
existing meter names (expand-only).
