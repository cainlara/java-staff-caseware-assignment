# Contract: Structured Logging (whitelist + JSON)

**Components**:
- `com.example.fanout.ports.StructuredLogSink` (port)
- `com.example.fanout.observability.{LogLevel, LogEvent, IdentifierField}`
- `com.example.fanout.observability.WhitelistValidatingLogSink` (decorator)
- `com.example.fanout.adapters.logging.JsonLineStructuredLogSink`
- `com.example.fanout.adapters.inmemory.InMemoryStructuredLogSink` (test)

## Port

```java
void log(LogLevel level, String event, Instant at, Map<IdentifierField, String> identifiers);
```

- `event`: dotted lower_snake name (`ingest.claimed`, `ingest.duplicate`,
  `ingest.deferred`, `fanout.run.started`, `fanout.run.completed`,
  `fanout.chunk.started`, `fanout.chunk.completed`, `fanout.retry`,
  `fanout.dlq`, `fanout.supersede.marked`, `backfill.batch.admitted`,
  `backfill.batch.completed`, `backfill.halt.observed`, `alert.fired`,
  `alert.cleared`). New event names are additive; the confidentiality test
  allowlists event names separately from identifier fields.
- Identifiers: map keyed by `IdentifierField` — the whitelist
  (publish_id, product_id, firm_id, engagement_id, region, chunk_id, state).
  Any other key is unrepresentable (enum).

## WhitelistValidatingLogSink (build-enforced guard, FR-005)

Decorates any sink. Before delegating:

1. Rejects null level/event/at/map; blank event; blank identifier values.
2. Rejects content-shaped values: length > 128, or containing CR/LF/NUL, or
   matching a free-text heuristic (≥ 3 consecutive whitespace runs).
3. Rejects `engagement_id` on events outside the error set
   {`fanout.retry`, `fanout.dlq`} — success-path logs stay chunk-cadence
   and content-free by construction.

Violations throw `IllegalArgumentException` naming the offending event and
field. Engines depend on the decorator in all wiring (main and tests).

## JsonLineStructuredLogSink

One flat JSON object per line to an injected `Appendable`:

```json
{"ts":"2026-10-19T12:00:00Z","level":"INFO","event":"fanout.chunk.completed","product_id":"p1","publish_id":"pub-9","chunk_id":"12","state":"pending"}
```

- Key order fixed: `ts`, `level`, `event`, then identifiers in enum
  declaration order (deterministic tests).
- Escaping: JSON minimal (`"` `\` control chars); identifier values already
  charset-guarded by the decorator.
- No stack traces, no free-text message field — failure class/message live
  in `DeadLetterRecord` (identifiers + bounded failure metadata), not logs.

## Emission policy (volume discipline)

| Path | Events | engagement_id? |
|------|--------|----------------|
| ingest claim/duplicate/defer | 1 per event | no |
| run started / completed | 1 each | no |
| chunk started / completed | 2 per chunk (~40 chunks per 20k-row publish) | no |
| retry / DLQ | 1 per retry/exhaustion | yes (error path only) |
| supersede marker | 1 per withdraw run | no |
| backfill batch admit/complete/halt | 1 per batch | no |
| alert fire/clear | 1 per edge | no |

Never per-engagement-row on the hot path (spec perf constraint; README cost
arithmetic).

## Test vectors

1. `WhitelistValidatingLogSinkTest`: every guard throws with the offending
   field named; valid event passes through unchanged.
2. `JsonLineStructuredLogSinkTest`: golden-line assertions (escaping, key
   order, one line per event).
3. `LogConfidentialityTest` (suite-wide, FR-005): drive ingest → publish →
   retry/DLQ → supersede → backfill → alert paths with
   `InMemoryStructuredLogSink` behind the validator; assert every captured
   event: identifier keys ⊆ whitelist; no content-shaped values; no
   unexpected top-level JSON keys. Additionally scan all captured identifier
   values for engagement-content markers (workpaper-like substrings from the
   fixtures) — zero matches.
4. Hot-path volume: 40,000-engagement publish emits O(chunks) log events
   (≤ 4 × chunkCount + constant), asserted in the e2e demo.

## Non-goals

No logback/SLF4J bridge, no transport, no log storage/retention (runtime
shell TODO, recorded in docs/compliance.md). No metrics from logs.
