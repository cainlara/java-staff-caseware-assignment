# Contract: DownstreamLoadClient

**Port**: `com.example.fanout.ports.DownstreamLoadClient`

Calls the shared downstream engagement-load service (owned by another team,
~1 minute per call). **Off the hot path only.**

## Signature (conceptual)

```
String loadEngagement(EngagementRef ref)   // returns current template version id
```

## Idempotency

- Read-only: same `EngagementRef` MAY be loaded repeatedly; results SHOULD be
  treated as a point-in-time observation of `currentVersion`, not as a
  monotonic guarantee. Callers re-read when CAS rejects their write.

## Latency and capacity

- Expected real latency ~60 s per call. Real concurrency is governed by
  `CapacityBudget` (C = 20 in flight, configurable); this port does not
  self-limit.
- Test fakes simulate latency (configurable; default ~60 s simulated) and
  support failure injection. Contract tests use zero/short latency for speed
  and separately assert the fake's default simulated latency is ~60 s.

## Hot path prohibition

- MUST NOT be called on the publish hot path (the path that writes PENDING
  rows from index metadata). FR-041: hot path touches the index only.
- Legitimate callers (later phases): revalidation, summary generation,
  backfill — always behind `CapacityBudget.acquire()`.

## Errors

- Unchecked exception on downstream failure; retry/backoff is a later phase.
- Returns non-blank version id; blank/null results violate the contract.

## Contract test vectors

1. Fake with injected latency: call waits the configured duration (asserted
   via elapsed-time bounds or virtual clock, with tiny test values).
2. Fake default configured latency ~60 s (asserted on configuration, not by
   sleeping).
3. Failure injection: configured to fail, the call throws; after reset it
   succeeds.
4. Null `EngagementRef` rejected (domain/contract guard).
