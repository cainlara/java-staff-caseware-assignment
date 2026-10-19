# Contract: FanOutMetrics (v2 — mandated fan-out emissions)

**Component**: `com.example.fanout.ports.FanOutMetrics` (phase-1 port, unchanged
interface)

Phase-1 semantics stand: identifier/measurement-only names, tags, durations;
non-blank name; non-negative delta; correctness never depends on metric
values; engagement content never appears (hard rule, spec FR-070).

This contract enumerates the names this feature mandates (spec FR-071) so
tests assert an exact allowlist.

## Counters

| Name | Delta | Tags | Emitted by | Spec anchor |
|------|-------|------|------------|-------------|
| `fanout.chunks.started` | 1 per chunk | `product_id` | engine scheduler | FR-071 |
| `fanout.chunks.completed` | 1 per resolved chunk | `product_id` | engine | FR-071 |
| `fanout.rows.resolved` | rows resolved in a write batch | `product_id` | engine | FR-071 |
| `fanout.retry` | 1 per retry attempt | `product_id` | retry executor / permit guard | FR-040 |
| `fanout.dlq` | 1 per exhausted unit (**alert metric**) | `product_id`, `unit_kind` | engine / permit guard | FR-041 |
| `fanout.backpressure` | 1 per failed permit acquisition | `product_id` | permit guard | FR-031 |
| `fanout.duplicate.run` | 1 per cursor-claim skip | `product_id` | engine | FR-053 |
| `fanout.cas.reapply` | 1 per stale-rejection re-apply cycle | `product_id` | engine | FR-021 |
| `fanout.downstream.inflight.hwm` | 1 per new in-flight peak | (none) | permit guard | FR-071 |

Cardinality discipline: tags limited to product ids (≤ ~40) and unit-kind
enum values. No engagement ids at fan-out volume (Constitution V/VI).

## Timers

| Name | Tags | Emitted by | Spec anchor |
|------|------|------------|-------------|
| `fanout.publish_to_pending` | `product_id` | engine, on cursor COMPLETE | FR-071, SC-010 |

Duration measured from run entry (trigger receipt) to COMPLETE using the
injected clock — deterministic under `FakeClock`.

## Hot-path note

The publish/withdraw hot path emits fan-out counters only; it performs ZERO
downstream calls, so `fanout.backpressure` and
`fanout.downstream.inflight.hwm` cannot appear from hot-path tests (asserted:
those names absent from the recording fake after every publish/withdraw test).

## Test vectors

1. Every counter name above appears in exactly the scenarios that mandate it;
   no other names are emitted by the engine (allowlist assertion on
   `RecordingFanOutMetrics`).
2. `fanout.dlq` present iff a dead-letter record was stored (SC-007).
3. No timer/counter argument contains engagement content (invariant check).
4. Hot-path tests: backpressure/hwm names absent; downstream call log empty.
