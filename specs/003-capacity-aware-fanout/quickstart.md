# Quickstart Validation Guide: Capacity-Aware Fan-Out Engine

**Feature**: 003-capacity-aware-fanout | **Date**: 2026-10-18

Validation proves the fan-out engine end-to-end against fakes: hot-path
publish with zero downstream calls, firm fairness, idempotent CAS writes,
crash-resume, retry bounds, dead-letter-on-exhaustion, withdrawn supersede,
and the 40,000-engagement scale invariant — all deterministic (latches, fake
clock, seeded jitter, test-controlled runner; no sleeps). No bus, no database,
no real downstream.

## Prerequisites

- JDK 21+ on `PATH` (`java -version` shows 21+)
- Maven 3.9+ on `PATH` (`mvn -version`)
- No database, broker, network services, or credentials required

## Setup

Run all commands from the repository root (the Maven project root — `pom.xml`
lives here).

## Primary gate (FR-073)

```bash
mvn verify
```

**Expected outcome**: `BUILD SUCCESS`, zero test failures/errors. Runs
entirely offline once dependencies are cached; no external system access.

## What the test suite proves (map to spec)

| Check | Where | Expected |
|-------|-------|----------|
| Publish fans out: rows written, chunks formed, cursor COMPLETE, **zero** downstream calls (US1, FR-001/FR-032, SC-001) | `FanOutEnginePublishTest` | rows == eligible count; `FakeDownstreamLoadClient`/`LatchDownstreamClient` call log empty |
| Zero-eligible publish completes empty (FR-004) | `FanOutEnginePublishTest` | `COMPLETED_EMPTY`; zero writes; cursor COMPLETE |
| Duplicate trigger / replay converges, no duplicate rows (FR-053, SC-004) | `FanOutEngineIdempotencyTest` | `SKIPPED_DUPLICATE`; row set unchanged |
| Concurrent duplicate chunk execution == single execution (FR-053, SC-004) | `FanOutEngineIdempotencyTest` | identical rows; one cursor advance |
| CAS stale-rejection re-apply converges deterministically (FR-021, R4) | `FanOutEngineIdempotencyTest` + `IndexWriterContractTest` | `fanout.cas.reapply` counted; merged content deterministic |
| Per-firm round-robin; per-firm cap; pool ceiling (US2, FR-010/FR-011, SC-009) | `FairShardSchedulerTest` | caps never exceeded at any observation; interleave order deterministic |
| Median firms finish while giant firm still running (FR-012, SC-005) | `FairShardSchedulerTest` + `LargePublishSimulationTest` | giant firm incomplete when median firms complete |
| Crash mid-publish -> resume at first incomplete chunk, no gaps/duplicates (US3, FR-051/FR-052, SC-003) | `FanOutEngineCrashResumeTest` | resumed rows identical to fault-free run |
| Retry: bounded exponential backoff + jitter within [0.5, 1.5) (US4, FR-040, SC-006) | `RetryExecutorTest` | attempt count ≤ budget; every delay within computed bound (fake clock + seeded jitter) |
| Retry exhaustion -> dead-letter + alert, never silent drop (US4, FR-041, SC-007) | `RetryExecutorTest`, `DeadLetterStoreContractTest` | record with full work identity; `fanout.dlq` incremented |
| Dead-letter replay converges (FR-043) | `RetryExecutorTest` | replayed unit reaches first-attempt-success state |
| C-limit: in-flight downstream never exceeds C; backpressure pauses admission (US4, FR-030/FR-031/FR-033, SC-002) | `PermitGuardedDownstreamTest` | latch fake max-tracker ≤ C exactly; `fanout.backpressure` counted; quiescence restores all permits |
| Withdraw supersede: next version or CREATED; decision history preserved (US5, FR-060/FR-061, SC-008) | `WithdrawSupersedeTest` | target state correct; `decision`/`pinnedRanges` byte-identical |
| Supersede uses same machinery; zero downstream calls (FR-062/FR-032) | `WithdrawSupersedeTest` | cursor + chunks used; call log empty |
| 40,000-engagement publish completes; in-flight ≤ C throughout (FR-072, SC-002/SC-005/SC-009) | `LargePublishSimulationTest` | 40,000 rows once; HWM ≤ C; fairness observed |
| New record validation (FanOutTask, FanOutCursor, DeadLetterRecord, FanOutConfig) | domain tests | invalid construction rejected |
| `find` read semantics + additive compatibility (index-writer v2) | `IndexWriterContractTest` | read-your-writes; absent -> empty |
| Cursor claim atomicity + marker append (checkpoint v2) | `CheckpointStoreContractTest` | one `true` under concurrent claim; idempotent append |
| Metrics allowlist; hot path emits no backpressure/HWM names (fan-out-metrics v2) | engine tests via `RecordingFanOutMetrics` | only mandated names; identifiers only |

## Self-containment check (spec SC-011)

```bash
# from repository root — expect zero hits pointing at external docs
grep -rniE 'TODO: see (the )?(spec|doc|pdf)|/docs/|sharepoint|intake doc' src pom.xml || echo "self-contained: OK"
```

**Expected**: `self-contained: OK`.

## Hot-path zero-downstream spot check

```bash
# the engine must not reference the downstream client or capacity budget
grep -rn 'DownstreamLoadClient\|CapacityBudget' src/main/java/com/example/fanout/fanout || echo "hot path clean: OK"
```

**Expected**: `hot path clean: OK` (only `PermitGuardedDownstream` may match —
if so, scope the grep to `FanOutEngine.java`/`FairShardScheduler.java`/
`RetryExecutor.java`/`FanOutCursorRepository.java` and expect zero hits).

## Interpreting results

- All green + both grep checks clean = fan-out meets spec; proceed to
  `/speckit.tasks`.
- Any fan-out test red = implementation disagrees with `contracts/`
  (authoritative alongside the spec); fix to the contract.
- Flaky timing symptoms should not exist; if a test waits on wall-clock
  instead of latches/fake clock, fix the test (FR-072 forbids sleep-based
  sync).
- Fairness test red only on order = check `TreeSet` scan + rotating cursor
  (contract D4), not the pool.

## Not validated this phase (by design)

End-to-end 60 s p99 on a real DB-backed index, real bus/trigger wiring,
real checkpoint durability across process death (needs a real adapter),
dead-letter replay tooling, alert routing, off-path repair jobs, feature-flag
rollout, backfill. See `research.md` "Carried-Forward Obligations".
