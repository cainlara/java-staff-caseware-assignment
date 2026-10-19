# Quickstart Validation Guide: Backfill, Halt, and Staged Enablement

Run-guide for the phase-4 feature. Implementation details live in
[contracts/](./) and [data-model.md](./data-model.md); this file proves the
feature works end-to-end.

## Prerequisites

- JDK 21, Maven.
- No external systems: everything runs against the delivered in-memory fakes
  (`InMemoryCheckpointStore`, `InMemoryIndexWriter`, `SeededEngagementEnumerator`,
  `LatchDownstreamClient` / `FakeDownstreamLoadClient`, `FakeCapacityBudget`,
  `FakeFeatureFlags`, `FakeClock`, `RecordingFanOutMetrics`).

## Setup and verification

```bash
# single verification command — runs the whole suite incl. all phase-4 tests
mvn verify
```

Test-first workflow: phase-4 tests are written before/with the production
classes (FR-055); `mvn verify` is both the development loop and the gate.

## Validation scenarios → proof tests

| # | Spec criterion | Scenario | Test class (src/test/java/com/example/fanout) |
|---|----------------|----------|-----------------------------------------------|
| 1 | SC-001, SC-003 | Random-kill property: interrupt backfill k ∈ [1,7] times at seeded random points; final restart ⇒ exactly N rows, each created once, row set == fault-free run | `backfill/BackfillWorkerCrashResumeTest` |
| 2 | SC-002 | Halt latency: saturate B_backfill, set halt, zero new admissions after observation, drain ≤ bound, all permits released, completed-batch checkpoints persisted | `backfill/BackfillHaltTest` + `fanout/FanOutEngineHaltTest` |
| 3 | SC-003 | Resume-no-duplicates: crash between last write of a batch and its cursor advance ⇒ re-run is duplicate-skips only; identical row set | `backfill/BackfillWorkerCrashResumeTest` (resume cases) |
| 4 | SC-007 | Flag-flip-mid-publish: flip one firm's flag mid-run ⇒ that firm stops admitting (rejection counters), others complete, outcome GATED; re-enable + re-invoke ⇒ exact-once completion. Same on backfill path with product and firm flags | `fanout/FanOutEngineFlagFlipTest` + `backfill/BackfillFlagFlipTest` |
| 5 | SC-004, SC-005 | Real-time vs backfill independence: latch-blocked loads hold all 18 B_backfill permits; a ~20k publish completes inside the freshness budget with zero permits and zero attributable downstream calls; a repair-share waiter acquires while backfill is saturated | `fanout/RealTimeBackfillIndependenceTest` + `backfill/CapacitySharesTest` |
| 6 | SC-006 | Fairness: one 40,000-engagement firm + 500 median-40 firms ⇒ every median firm completes while the giant firm is incomplete; per-firm cap never exceeded | `backfill/BackfillFairnessTest` |
| 7 | SC-009 | Poisoned engagement: always-failing load exhausts retries ⇒ DLQ with full identity, alert +1, batch still checkpoints; replay converges | `backfill/BackfillDeadLetterTest` |
| 8 | FR-006/007, SC-008 | No-op engagements (already at effective version / product without publishes) resolve without rows; pacing arithmetic honored: observed in-flight ≤ B_backfill at every sample | `backfill/BackfillWorkerTest` + `CapacitySharesTest` |
| 9 | FR-054, SC-010 | Privacy/self-containment: no engagement content in any metric tag, DLQ field, or checkpoint value; zero external-document references | counters assertions embedded in the above + `ports/EngagementEnumeratorContractTest` |

All tests are deterministic: seeded RNG, latches, controllable clock — no
sleep-based synchronization anywhere (FR-055).

## Operational knobs (defaults)

| Knob | Default | Where |
|------|---------|-------|
| `bBackfill` / `bRepair` / `parentC` | 18 / 2 / 20 | `BackfillConfig` / `CapacityShares` (startup rejects `bBackfill + bRepair > parentC`) |
| `batchSize` | 100 | `BackfillConfig` |
| `firmInFlightCap` (concurrent batches/firm) | 1 | `BackfillConfig` |
| `haltPollInterval` / `haltDrainBound` | 1 s / 2 min | `BackfillConfig` |
| retry policy | 5 attempts, 100 ms → 30 s, jitter [0.5, 1.5) | `RetryPolicy` (shared) |

Pacing: full backfill of ~800,000 one-minute loads ≈ 27.8 days at
B_backfill = 20, ≈ 30.9 days at 18 — pacing is the share, not a deadline.

## Rollout sequence (staged enablement, D6)

1. Deploy the gated build. Opt-in defaults ⇒ **no work admitted yet**
   (fan-out and backfill both gated off for unknown ids).
2. Seed `FeatureFlags` with currently live products and firms ⇒ fan-out
   resumes at prior behavior (cursor-based, exact-once).
3. Enable backfill per pilot firm (+ its products), observe counters
   (`backfill.batches.*`, `backfill.engagements.*`, permit HWM ≤ 18).
4. Widen backfill firm-by-firm. Halt switch is live from step 1 onward —
   flip to stop all admission within seconds, no redeploy; clear to resume
   from checkpoints.

## What "done" looks like

`mvn verify` green with scenarios 1–9 passing; `git grep` shows zero external
document references in `specs/004-*` and zero new dependencies in `pom.xml`.
