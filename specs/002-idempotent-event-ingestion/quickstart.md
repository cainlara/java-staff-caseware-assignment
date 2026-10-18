# Quickstart Validation Guide: Idempotent Event Ingestion & External Checkpointing

**Feature**: 002-idempotent-event-ingestion | **Date**: 2026-10-18

Validation proves ingest end-to-end against fakes: idempotent claims, withdraw
deferral/resolution, out-of-order convergence, crash-and-replay, multi-instance
combined idempotency — with zero engagement-load calls. No bus, no database, no
fan-out work to run.

## Prerequisites

- JDK 21+ on `PATH` (`java -version` shows 21+)
- Maven 3.9+ on `PATH` (`mvn -version`)
- No database, broker, network services, or credentials required

## Setup

Run all commands from the repository root (the Maven project root — `pom.xml`
lives here).

## Primary gate (FR-042)

```bash
mvn verify
```

**Expected outcome**: `BUILD SUCCESS`, zero test failures/errors. Runs entirely
offline once dependencies are cached; no external system access.

## What the test suite proves (map to spec)

| Check | Where | Expected |
|-------|-------|----------|
| Fresh publish ingested once: claim + sequence + append + one trigger (FR-001, R1) | `IngestServiceIdempotencyTest` | INGESTED; one PUBLISH trigger; PRODUCT_PUBLISHES has the id |
| Duplicate publish acked, no trigger, counter (FR-003, SC-001) | `IngestServiceIdempotencyTest` | DUPLICATE; `ingest.duplicate` incremented; trigger count unchanged |
| Duplicate withdraw acked, no trigger (FR-003, R2) | `IngestServiceIdempotencyTest` / deferral test | DUPLICATE; zero new triggers |
| Withdraw-before-publish deferred, not dropped/crashed (FR-010, SC-002) | `IngestServiceWithdrawDeferralTest` | DEFERRED; WITHDRAW record status DEFERRED; PENDING_WITHDRAW lists id; zero triggers |
| Deferred withdraw auto-resolves on matching publish (FR-011, SC-002) | `IngestServiceWithdrawDeferralTest` | publish outcome carries resolvedWithdrawIds; RESOLVED record with matched publish id; exactly one WITHDRAW trigger; pending list cleared |
| Withdraw after its publish resolves immediately (FR-012) | `IngestServiceWithdrawDeferralTest` | INGESTED; one WITHDRAW trigger with matchedPublishId |
| Withdraw whose publish never arrives stays deferred (FR-015) | `IngestServiceWithdrawDeferralTest` | DEFERRED persists; no error |
| Out-of-order publishes both retained, order recorded (FR-013) | `IngestServiceReorderingTest` | both ids in PRODUCT_PUBLISHES; sequences = arrival order |
| Effective version independent of arrival order (R4) | `IngestServiceReorderingTest` | R4 helper same result for both orders |
| Kill before claim -> redelivery processes once (FR-022, SC-003) | `IngestServiceCrashRecoveryTest` | first call throws (failing store); no trigger/record; redelivery INGESTED once |
| Kill after claim, before trigger -> redelivery is duplicate (FR-021, SC-003) | `IngestServiceCrashRecoveryTest` | first call throws (failing sink); redelivery DUPLICATE; zero additional triggers |
| Claim value sufficient for reconciler (FR-024) | `IngestServiceCrashRecoveryTest` | PUBLISH record readable after kill-after-claim, carries product + toVersion |
| Two instances, one store, one ingest effect (FR-023, SC-007) | `IngestServiceMultiInstanceTest` | first INGESTED, second DUPLICATE, one trigger total |
| Permutation x duplication converges (FR-014, SC-004) | `IngestPermutationTest` | identical processed sets, effective versions, withdraw resolutions across all permutations |
| `writeIfAbsent` atomic claim (v2 contract) | `CheckpointStoreContractTest` | one `true` under concurrent hammer; first value wins |
| Zero engagement-load calls (FR-030/FR-041, SC-005) | every ingest test | `FakeDownstreamLoadClient.getCallCount() == 0` asserted per test |
| Null argument rejected, no side effects (FR-004) | idempotency test | throws; no checkpoint; no trigger |
| Trigger payload validation (FR-005) | domain tests | invalid `FanOutTrigger` construction rejected |
| Metrics identifiers only (FR-031/FR-032) | recording-metrics assertions | names from the allowlist; no engagement content |

## Self-containment check (spec SC-008)

```bash
# from repository root — expect zero hits pointing at external docs
grep -rniE 'TODO: see (the )?(spec|doc|pdf)|/docs/|sharepoint|intake doc' src pom.xml || echo "self-contained: OK"
```

**Expected**: `self-contained: OK`.

## Out-of-scope leak check (spec Out of Scope)

```bash
# expect no matches: no eligibility logic, index writes, scheduling, retries,
# rate limiting, backfill implementation, or content application this phase
grep -rniE 'IndexWriter|@Scheduled|RetryTemplate|RateLimiter|KafkaListener|RabbitListener|eligib' src/main || echo "no leaks: OK"
```

**Expected**: `no leaks: OK`. (Note: `IndexWriter` *interface* from phase 1
remains in the codebase — the grep targets `src/main` usage *by ingest*; if
the phase-1 interface declaration matches, scope the grep to the `ingest`
package: `grep -rniE 'IndexWriter|@Scheduled|RetryTemplate|RateLimiter|eligib' src/main/java/com/example/fanout/ingest || echo "no leaks: OK"`.)

## Interpreting results

- All green + both grep checks clean = ingest meets spec; proceed to
  `/speckit.tasks`.
- Any ingest test red = implementation disagrees with `contracts/` (authoritative
  alongside the spec); fix to the contract.
- Permutation test red with only sequence-number differences = test failed to
  normalize observational fields; fix the test, not the state (FR-014
  explicitly allows sequence fields to differ).

## Not validated this phase (by design)

End-to-end publish-to-pending p99 < 60 s (needs fan-out), real event bus ack,
real checkpoint durability across process death (needs a real adapter),
trigger reconciliation after the crash window, retries/rate limiting/
scheduling/backfill, metrics backend. See `research.md`
"Carried-Forward Obligations".
