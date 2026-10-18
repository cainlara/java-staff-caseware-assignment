# java-staff-caseware-assignment

## fanout-worker

Template-publish fan-out worker — **scaffold phase**. This project delivers the
verified foundation only: immutable domain records, port interfaces with
documented idempotency keys, in-memory/fake implementations, and contract tests.
No fan-out behavior yet (no event consumption, scheduling, retries, rate
limiting, backfill, real adapters, UI, or content application).

Part of a pending-template-update system: a product team publishes a template
version; later phases fan that publish out to ~20,000 engagement files. The
event bus is at-least-once; every consumer must be idempotent. The publish hot
path touches the Pending Update Index (metadata rows) only — never engagement
content. Logs/metrics must never contain engagement content.

## Verify

```bash
mvn verify
```

## Ports (idempotency keys)

| Port | Idempotency key / semantics | Hot path |
|------|------------------------------|----------|
| `EventSource` | `publishId` / `withdrawId` (+ `productId`); at-least-once | triggers |
| `IndexWriter` | `(engagementId, offeredVersion, state)` + CAS on `rowVersion` | yes (only writer) |
| `DownstreamLoadClient` | read-only re-reads; ~1 min latency, C permits | **never** |
| `CheckpointStore` | `(kind, id)`; must survive restart (in-memory placeholder now) | n/a |
| `CapacityBudget` | counted permits, C default 20 configurable | consumes off-path only |
| `FeatureFlags` | product/firm gates + global halt switch | gates admission |
| `FanOutMetrics` | no content in names/tags — identifiers only | n/a |

Full contracts: `specs/001-fanout-worker-scaffold/contracts/`.
Design decisions: `specs/001-fanout-worker-scaffold/research.md`.
