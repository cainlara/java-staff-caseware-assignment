# java-staff-caseware-assignment

## fanout-worker

Template-publish fan-out worker — **ingestion phase**. The scaffold (phase 1:
immutable domain records, port interfaces, fakes, contract tests) now includes
idempotent event ingestion with external checkpointing (phase 2): the
`IngestService` claims each `TemplatePublished` / `TemplateWithdrawn` event in
the `CheckpointStore` and emits one internal fan-out trigger per newly
ingested event. Real fan-out work is still a later phase.

Part of a pending-template-update system: a product team publishes a template
version; later phases fan that publish out to ~20,000 engagement files. The
event bus is at-least-once; every consumer must be idempotent. The publish hot
path touches the Pending Update Index (metadata rows) only — never engagement
content. Logs/metrics must never contain engagement content.

## Ingestion & checkpointing (phase 2 tradeoffs)

- **Duplicate claim**: the checkpoint write *is* the processed record —
  `CheckpointStore.writeIfAbsent` (atomic; DB adapter maps to unique-constraint
  `INSERT`). Redeliveries ack as duplicates: no new trigger, `ingest.duplicate`
  counter, never an error.
- **Checkpoint-then-trigger window**: a crash after the claim but before
  in-process trigger emission is not re-ingested on redelivery (spec R5/FR-021).
  Checkpoint values carry identity + ingest metadata (FR-024) so a future
  reconciler can re-derive missed triggers; the reconciler itself is a later
  phase.
- **Withdraw deferral (R3)**: a withdraw for an uningested (product, version)
  is recorded as deferred, not dropped; when the matching publish is ingested,
  every deferred withdraw for that (product, version) resolves once (matched
  publish id linked) and emits one withdraw trigger.
- **Effective version (R4)**: derived, never stored — greatest
  `(publishedAt, publishId)` pair wins, so out-of-order publishes converge to
  the same state; all publishes are retained with per-product ingest order
  recorded.
- **Zero engagement loads on the ingest path**: every ingest test asserts the
  fake downstream client's call count is zero.
- Progress encoding lives only in `IngestProgressRepository` (US/LF-delimited
  strings; natural keys assumed delimiter-free).

Full ingest contract: `specs/002-idempotent-event-ingestion/contracts/`.

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
| `CheckpointStore` | `(kind, id)`; `writeIfAbsent` atomic claim; must survive restart | n/a |
| `FanOutTriggerSink` | consumer key `(kind, eventId, productId)`; at-most-once emission | n/a |
| `CapacityBudget` | counted permits, C default 20 configurable | consumes off-path only |
| `FeatureFlags` | product/firm gates + global halt switch | gates admission |
| `FanOutMetrics` | no content in names/tags — identifiers only | n/a |

Full contracts: `specs/001-fanout-worker-scaffold/contracts/` and
`specs/002-idempotent-event-ingestion/contracts/`.
Design decisions: `specs/002-idempotent-event-ingestion/research.md`.
