# Quickstart Validation Guide: Fan-Out Worker Scaffold & Contracts

**Feature**: 001-fanout-worker-scaffold | **Date**: 2026-10-18

Validation proves the delivered scaffold end-to-end: builds clean, contracts
exist and are pinned by tests, no out-of-scope behavior leaked in. No fan-out
runs — there is nothing to run beyond verification.

## Prerequisites

- JDK 21+ on `PATH` (`java -version` shows 21+)
- Maven 3.9+ on `PATH` (`mvn -version`)
- No database, broker, network services, or credentials required

## Setup

Run all commands from the repository root (the Maven project root — `pom.xml`
lives here).

## Primary gate (FR-001)

```bash
mvn verify
```

**Expected outcome**: `BUILD SUCCESS`, zero test failures/errors. Runs entirely
offline once dependencies are cached; no external system access.

## What the test suite proves (map to spec)

| Check | Where | Expected |
|-------|-------|----------|
| Records reject null/blank natural keys (FR-014, SC-002) | domain validation tests | every invalid input throws at construction; 0 invalid records creatable |
| Records immutable + defensive copies (FR-015) | domain tests | external mutation of input collections does not change stored fields |
| IndexWriter duplicate write idempotent (FR-031, SC-003) | IndexWriter contract test | 2nd identical write = `SKIPPED_DUPLICATE`, state identical to single write |
| IndexWriter stale CAS rejected (FR-032) | IndexWriter contract test | `REJECTED_STALE`, stored state unchanged |
| Checkpoint survives "restart" (FR-023, SC-004) | CheckpointStore contract test | new instance over same storage returns prior value |
| Checkpoint missing key distinguishable | CheckpointStore contract test | `Optional.empty()` vs stored value |
| Capacity never exceeds C (FR-024, SC-005) | CapacityBudget contract test | tryAcquire false at limit; concurrent hammer never exceeds C |
| C configurable | CapacityBudget contract test | non-default C respected |
| Downstream fake latency + failure injection (FR-030) | DownstreamLoadClient contract test | configured latency honored; injected failure throws; default ~60s simulated asserted without sleeping |
| Metrics no-op + validation (FR-026) | FanOutMetrics contract test | valid calls record nothing; invalid args rejected |
| No engagement content in telemetry (FR-040, SC-006) | recording-fake assertions | only identifier/measurement shapes recorded |
| Every port has a contract test (SC-007) | ports package | one+ test class per port |

## Self-containment check (FR-003, SC-008)

```bash
# from repository root — expect zero hits pointing at external docs
grep -rniE 'TODO: see (the )?(spec|doc|pdf)|/docs/|sharepoint|intake doc' src pom.xml || echo "self-contained: OK"
```

**Expected**: `self-contained: OK`.

## Out-of-scope leak check (spec Out of Scope)

```bash
# expect no matches: no scheduling, retry loops, rate limiters, backfill,
# event consumption loop, or content application this phase
grep -rniE 'backfill.*implement|@Scheduled|RetryTemplate|RateLimiter|KafkaListener|RabbitListener' src || echo "no leaks: OK"
```

**Expected**: `no leaks: OK`. (Note: `CheckpointKey.BACKFILL_BATCH` and
`EventSource` *interface* declarations are in scope — only behavior/loops are
out of scope.)

## Interpreting results

- All green + both grep checks clean = scaffold meets spec; proceed to
  `/speckit.tasks`.
- Any contract test red = contract doc and implementation disagree; fix to the
  contract in `contracts/` (they are authoritative with the spec).

## Not validated this phase (by design)

Freshness SLO p99 < 60 s, real event consumption, scheduling, retries, rate
limiting, backfill, real metrics, residency enforcement, checkpoint durability
across real process death (needs a real adapter). See `research.md`
"Carried-Forward Obligations".
