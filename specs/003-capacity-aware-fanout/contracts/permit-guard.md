# Contract: PermitGuardedDownstream (off-path permit gate)

**Component**: `com.example.fanout.fanout.PermitGuardedDownstream` (new)

The ONLY sanctioned way to call `DownstreamLoadClient`. Gates every call on
`CapacityBudget` (C = 20, configurable) with paused admission — never used by
the publish/withdraw hot path (the engine has no dependency on this class or
on the client). Delivers the spec's off-path rate-limit invariant (FR-030,
FR-031, FR-033, R5) without building any repair job.

## Signature (conceptual)

```
String load(EngagementRef ref)   // permit-gated call; same return as client
int inflight()                   // current permit-held count (tests/metrics)
```

Constructor-injected: `DownstreamLoadClient`, `CapacityBudget`,
`BackoffWaiter`, `java.time.Clock`, `FanOutMetrics`, `FanOutConfig`
(retry/backoff tunables), `JitterSource`.

## Call sequence (normative; spec R5)

1. Loop: `tryAcquire()`.
   - `false`: increment `fanout.backpressure` (tag `product_id`), await via
     `BackoffWaiter` (paused admission — the caller blocks logically; no
     queue of admitted-but-waiting units exists anywhere), retry. This is
     backpressure, not failure (FR-031).
   - `true`: proceed.
2. Invoke `client.loadEngagement(ref)` inside try/finally:
   - success => `finally { release() }`, return value.
   - transient client failure => `finally { release() }`, rethrow as
     `TransientFanOutException` (permit released BEFORE any retry backoff —
     C is reserved only for actual in-flight calls; `RetryExecutor` owns the
     backoff outside the permit).
3. Track in-flight count; on each new peak increment
   `fanout.downstream.inflight.hwm` (event-style counter — the metrics port
   has no gauge and rejects negative deltas).
4. Retry exhaustion (via wrapping `RetryExecutor`): store
   `DeadLetterRecord(DOWNSTREAM_CALL, …)`, increment `fanout.dlq`, rethrow or
   return per caller policy — never silent drop (FR-041).

## Invariants (asserted by direct contract test; spec SC-002)

- Observed in-flight calls NEVER exceed C at any observation point —
  verified exactly by an atomic max-tracker inside a latch-blocking client
  fake, not by sampling.
- `CapacityBudget.available() + held == C` at quiescence; every acquire has
  exactly one release; release-without-hold still throws (phase-1 rule).
- Zero calls when C = 0 is attempted (degenerate config: pure backpressure —
  test uses a time-bounded fake clock to prove pause, not progress).

## Hot-path prohibition

`FanOutEngine` MUST NOT reference this class (checked by construction: no
dependency). Publish/withdraw tests assert the client call log is empty —
which covers both direct and guarded paths.

## Test harness

- `LatchDownstreamClient` (test fake): each call blocks on a per-call latch;
  maintains exact in-flight counter + atomic max; `releaseAll()` /
  `failNext(n)` injection. Replaces phase-1's sleep-simulating fake for all
  concurrency tests (deterministic; no `Thread.sleep`).
- Determinism: all waiting is latch-based; backoff waiting advances the
  `FakeClock` via the immediate waiter.

## Contract test vectors

1. C = 3, 10 guarded calls, latch-held -> exactly 3 in flight; max-tracker
   = 3; after releaseAll, remaining calls proceed; final max ≤ 3
   (FR-030, SC-002).
2. Client fails transiently n < budget times -> permit released between
   attempts; final success; hwm ≤ C throughout (FR-033).
3. Client fails >= budget -> dead-letter record `DOWNSTREAM_CALL`,
   `fanout.dlq` incremented, no silent drop (FR-041).
4. C exhausted -> `fanout.backpressure` increments; no unbounded buffering
   (assert via backpressure counter + call-log length, FR-031).
5. Quiescence: all permits returned; `available() == C`.
6. Engine-side tests: guard never involved; client call log empty.
