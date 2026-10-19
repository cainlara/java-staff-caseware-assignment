# Contract: Alert Hooks

**Components**:
- `com.example.fanout.ports.AlertSink` (port)
- `com.example.fanout.ports.AlertThresholdProvider` (port)
- `com.example.fanout.observability.{AlertCondition, AlertThresholds, AlertEvaluation, AlertEvaluator}`
- `com.example.fanout.adapters.inmemory.InMemoryAlertSink` / `InMemoryAlertThresholdProvider` (tests/demo)

## Port: AlertSink

```java
void fire(AlertCondition condition, Instant at, Map<IdentifierField, String> identifiers);
void clear(AlertCondition condition, Instant at, Map<IdentifierField, String> identifiers);
```

- Edge-triggered notifications from the evaluator only (engines never call
  the sink directly).
- Identifiers only — never engagement content (hard rule).
- Delivery (paging/chat/email) is out of scope; the sink is the in-process
  seam. Runtime shell TODO: bridge to the ops alerting system.

## Port: AlertThresholdProvider

```java
AlertThresholds current();
```

Supplies runtime-changeable thresholds/windows (FR-014) — operators retune
without redeploy. Invalid records are rejected at construction
(`AlertThresholds` validation), so a bad hot-swap fails loud at the provider
boundary, never silently.

## AlertEvaluator

Pure component, clock-injected, caller-driven:

```java
List<AlertEvaluation> evaluate(Instant now);
```

No internal threads (a runtime shell will tick; tests call directly).
Injects: `FreshnessP99` supplier, `FanOutCursorRepository`,
`IngestedPublishLookup` (publishedAt source — via cursor repo's existing
ingested-publish reads), `DeadLetterStore`, backfill checkpoint reader +
backfill-running/halted probes, in-flight supplier, completion-counter
delta source, `AlertThresholdProvider`, `AlertSink`.

### Condition semantics

| Condition | True when | Identifiers on fire |
|-----------|-----------|---------------------|
| `FRESHNESS_BURN` | ring p99 of `fanout.publish_to_pending` > `freshnessP99Limit` (no data → never fires) | `product_id` of worst recent publish (ring exposes argmax), `publish_id` |
| `COVERAGE_BREACH` | exists run with status ≠ COMPLETE whose ingested `publishedAt` age > `coverageAgeLimit` | `publish_id`, `product_id` (one fire per publish; detail count in sink adapter) |
| `DLQ_NON_EMPTY` | `DeadLetterStore.count() > 0` | depth carried by sink adapter via store read; ids of newest record |
| `BACKFILL_STALLED` | backfill enabled ∧ ¬halted ∧ newest backfill-checkpoint `updatedAt` older than `backfillStallLimit` (empty checkpoint history while enabled → fire after limit) | `firm_id` of last checkpointed batch if known |
| `DOWNSTREAM_SATURATION` | in-flight == C for the whole `saturationWindow` ∧ completion-counter delta == 0 over the window | (none — global; `state=saturated`) |

### Edge / clear rules

- Evaluator keeps an in-memory active set; each `evaluate` returns FIRE
  edges for newly-true conditions and CLEAR edges for newly-false ones;
  steady-state returns nothing (no spam).
- Restart resets the active set → next true condition re-fires (safe).
- Intentionally halted backfill must NOT satisfy the stall predicate
  (halt exclusion — spec edge case).
- Saturation uses two consecutive consistent samples minimum inside the
  window (guard against single-tick races).

### Test vectors

1. Drive each of the 5 conditions true under `FakeClock`; advance past the
   window; `evaluate` returns exactly one FIRE edge per condition with the
   documented identifiers.
2. Clear each condition; `evaluate` returns the CLEAR edge; second
   evaluation returns nothing.
3. Freshness: ring samples {10s × 39, 90s × 1} → p99 below/above boundary
   behavior asserted at exactly 60 s.
4. Coverage: cursor IN_PROGRESS with `publishedAt` 25 h old fires; COMPLETE
   run of any age never fires.
5. DLQ: empty store → no fire; store 1 record → fire; `count()` visible.
6. Backfill: no checkpoint advance for 16 min while running → fire; same
   staleness while halted → no fire; advance → clear.
7. Saturation: in-flight pinned at C with zero completions for 2 min →
   fire; any completion in window → no fire.
8. Threshold hot-swap: provider returns tighter freshness limit mid-test;
   next `evaluate` uses it (no redeploy — FR-014 evidence).
9. Confidentiality: every emitted `AlertEvaluation` identifiers map keys ⊆
   `IdentifierField`; no content-shaped values (validator reuse).

## Non-goals

No persistent alert history, no dashboards, no external notification
transport, no auto-remediation (capacity response stays in the permit
guard, not the alerter).
