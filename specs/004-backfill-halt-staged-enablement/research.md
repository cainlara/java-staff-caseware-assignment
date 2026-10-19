# Phase 0 Research: Backfill, Halt, and Staged Enablement

All Technical Context unknowns resolved; no `[NEEDS CLARIFICATION]` remain.
Decisions reference existing delivered code (phases 1–3) and the phase-4
specification (spec.md rules B1–B9, FRs).

---

## D1 — EngagementEnumerator port shape

**Decision**: new port `ports/EngagementEnumerator`:

```java
EnumerationBatch nextBatch(String customerDatabaseId, Optional<String> afterEngagementId, int limit);
```

Element type is a NEW record `EnumeratedEngagement(engagementId, firmId,
productId)` — identifiers only, deliberately NOT `EngagementRef`.
`EnumerationBatch(List<EnumeratedEngagement> items, Optional<String>
nextCursor, boolean done)` with items ordered by `engagementId` ascending,
`nextCursor` = last item's id when `!done`.

**Rationale**: `EngagementRef` requires a non-blank `currentVersion`, but a
pre-feature engagement's version is unknown until the downstream load — the
enumerator must not know it. Reusing `EngagementRef` would force a sentinel
version string (lie in the model). The eligibility source
(`EngagementEligibilitySource`) streams per-publish eligibility from index
metadata; backfill needs the full estate per customer database keyed by
engagement id — different question, different port (constitution I: one
responsibility per module).

**Alternatives considered**: (a) reuse `EngagementRef` with placeholder
version — rejected, sentinel values poison the type's invariant; (b) extend
`EngagementEligibilitySource` — rejected, mixes hot-path metadata streaming
with off-path estate enumeration; (c) cursor by (firm, product, engagement)
triple — rejected, engagement id is globally unique per the phase-1 model and
a single ascending key keeps resume total.

**Fake**: `SeededEngagementEnumerator` (test scope) — deterministic dataset
from a seed; supports `failNextCalls(n)`, `failAlways()`, and restart from any
cursor; records a call log for assertions.

---

## D2 — Backfill cursor schema and resume granularity

**Decision**: use the phase-1-reserved `CheckpointKind.BACKFILL_BATCH`. One
checkpoint per firm: key id = `firmId` (assumption: one customer database per
firm). Value = `status US lastResolvedEngagementId` where `status ∈
{RUNNING, COMPLETE}` and `US` = U+001F, matching the delivered
`FanOutCursorRepository` encoding style. Cursor semantics: exclusive lower
bound on engagement id; missing key = start from the beginning. The cursor
advances only when a whole batch has resolved (every engagement: row written,
no-op, or dead-lettered). A crash mid-batch loses only that batch's partial
progress — at most one batch of redundant loads per firm (spec B4, FR-005).

**Rationale**: batch-boundary advancement is exactly the spec's checkpoint
contract; per-engagement cursor advancement would add store traffic per load
against a ~1-minute-per-load pipeline (noise, not value). Encoding reuses the
proven US-delimited, corrupt-throws-IllegalStateException pattern
(`FanOutCursorRepository`), so a corrupt cursor dead-letters/alerts and never
silently restarts.

**Alternatives considered**: (a) per-engagement cursor writes — rejected,
write amplification with zero correctness gain (idempotent writes absorb the
≤ 1 batch overlap anyway); (b) new checkpoint kind — rejected,
`BACKFILL_BATCH` was reserved in phase 1 for exactly this; (c) cursor keyed by
customer-database id instead of firm id — equivalent under the 1:1 assumption
(D-assumption, data-model.md), firm id chosen because every other scheduler
keyed by firm.

---

## D3 — Effective version for a backfilled engagement

**Decision**: reuse the delivered pure function `EffectiveVersion.selectEffective`
over `FanOutCursorRepository.ingestedPublishes(productId)`, excluding versions
in `FanOutCursorRepository.withdrawnVersions(productId)`. `BackfillWorker`
injects `FanOutCursorRepository` directly (in-process domain collaborator, not
a port — it is already the typed adapter over the delivered checkpoint
schemas). Rule: captured version == effective to-version ⇒ no-op; product with
zero publishes (no effective version) ⇒ no-op; otherwise write one PENDING row
offering the effective to-version with `publishIds = {effective publish id}`,
`currentVersion` = captured version.

**Rationale**: the phase-2 effective-version rule is the single source of
truth for "current" (constitution II); re-deriving it per engagement keeps
backfill consistent with what the fan-out path would have written, and the
row's `publishIds` attribution to the real publish keeps downstream decision
history meaningful.

**Alternatives considered**: (a) compute "latest" from version strings —
rejected, versions are opaque (no arithmetic, spec B7); (b) store effective
version in the backfill cursor — rejected, duplicated state that can diverge
from ingest (constitution I/II: derive, never store — same reasoning as
phase-2 R4).

---

## D4 — Dead-letter identity for backfill units

**Decision**: extend `DeadLetterUnitKind` with `BACKFILL_ENGAGEMENT`,
`BACKFILL_BATCH`, `BACKFILL_CURSOR` (additive enum). Encode the backfill work
identity in the existing `DeadLetterRecord` fields — no record-shape change:

- `eventId` := `bf US firmId US batchIndex US engagementId` (for
  `BACKFILL_ENGAGEMENT`; `BACKFILL_BATCH`/`BACKFILL_CURSOR` omit the trailing
  engagement segment),
- `productId` := product id,
- `chunkIndex` := batch index (−1 for cursor units).

Encoding lives in one codec class (`BackfillDeadLetterIdentity`) and is
covered by a contract test.

**Rationale**: `DeadLetterRecord` and `DeadLetterStore` are delivered
phase-1/3 contracts with constructor validation and tests; a record change
breaks every existing construction site for zero added safety. Composite
identifier strings are already the established checkpoint-encoding pattern
(`FanOutRunKind token + US + eventId + US + productId`). Identifier-only is
preserved (constitution V).

**Alternatives considered**: (a) extend `DeadLetterRecord` with nullable
backfill fields — rejected, record component addition is a breaking change
for all consumers; (b) separate `BackfillDeadLetterStore` port — rejected,
two stores to operate for one alerting surface; (c) drop firm id from
identity — rejected, spec FR-051 requires full work identity.

---

## D5 — Capacity split: two budgets, no shared wait queue

**Decision**: `CapacityShares(int parentC, int backfill, int repair)` record
with validation `backfill + repair <= parentC` (defaults 20/18/2). Two
independent `CapacityBudget` instances (the existing `FakeCapacityBudget`
shape: semaphore + exactly-once release) — one per share. No parent
semaphore: disjointness holds by construction (two independent counting
semaphores whose capacities sum to ≤ C can never jointly exceed C), so no
cross-share wait queue can exist. `BackfillWorker` is constructed with the
backfill-share budget; a future real-time repair caller constructs a second
`PermitGuardedDownstream` with the repair-share budget. Startup/config
validation rejects over-partition (FR-021).

**Rationale**: the delivered `CapacityBudget` port is exactly a counting
semaphore contract; composing two instances is simpler and more obviously
correct than a hierarchical parent/child allocator. The arithmetic is
documented (constitution IV): 800,000 × 60 s / 20 ≈ 27.8 days;
/ 18 ≈ 30.9 days — B_backfill is the pacing knob.

**Alternatives considered**: (a) one semaphore + priority classes — rejected,
priority cannot be enforced on a plain semaphore without stealing, and
stealing recreates the coupling this split exists to remove; (b) parent
semaphore with child leases — rejected, more state, same invariant, harder to
test; (c) B_backfill = 20, B_repair = 0 default — rejected, a repair caller
added later would have to steal from live backfill (spec delivers the
reservation now, SC-005).

---

## D6 — Flag defaults: staged enablement is opt-in (spec-vs-codebase reconciliation)

**Decision**: keep the delivered `FeatureFlags` contract as authoritative —
unknown product/firm ids default to **false** (opt-in), pinned by
`FeatureFlagsContractTest`. The phase-4 spec assumption "fan-out gates
default to enabled so existing live behavior is preserved" is **superseded**:
the engine gains flag gating in this phase, and gating is opt-in for both
paths. Deployment procedure (quickstart.md): seed the flag source with the
currently live products/firms before (or immediately at) rollout of the
gated build. Backfill additionally requires explicit per-firm and per-product
enablement — consistent with opt-in.

**Rationale**: phase 1 delivered and contract-pinned the opt-in default;
changing it now would break a delivered contract and its tests. Opt-in is
also the *stronger* safety property for a live system (constitution VII):
a freshly deployed gated build admits nothing until deliberately enabled —
a mis-deploy contains itself. The operational cost (seed flags at rollout) is
explicit and testable.

**Alternatives considered**: (a) flip the port default to true — rejected,
breaks the phase-1 contract test and weakens containment; (b) engine-side
"default-open unless source says otherwise" wrapper — rejected, the port's
documented semantics would no longer describe actual behavior (dishonest
contract, constitution VIII).

---

## D7 — Backfill scheduling: fair, demand-driven, per-firm sequential batches

**Decision**: `BackfillScheduler` — demand-driven round-robin over per-firm
batch streams (NOT a pre-materialized chunk list like `FairShardScheduler`,
because 800k engagements cannot be materialized upfront). State: per-firm next
cursor + firm completion flag, `TreeSet` of active firms with a rotating scan
cursor, one `Semaphore(firmInFlightCap)` per firm (default 1 concurrent
batch), and admission gated on: (a) `AdmissionGate` (halt + product/firm
flags), (b) per-firm permit, (c) a non-blocking `budget.tryAcquire()` for the
engagement-level load permit inside `PermitGuardedDownstream`. Within a
batch, engagements execute sequentially on the batch's pool thread (each with
its own permit acquire/release). Backfill pool = a dedicated
`TaskRunner.fixedPool(bBackfill)` (default 18) — disjoint from the fan-out
pool (FR-020).

**Consequence**: at any instant, in-flight loads ≤ min(pool bound, B_backfill)
= B_backfill; per-firm in-flight engagements ≤ 1 ≤ spec cap; the giant firm
occupies at most `firmInFlightCap` of the 18 threads; median firms rotate
through the rest. Fairness holds at every observation point (SC-006).
Throughput: 18 engagements/min ≈ the documented 30.9-day full-estate pace.

**Rationale**: per-firm sequential batch execution is the simplest structure
that provably satisfies FR-011 (in-flight engagements per firm ≤ cap) and
B3 (one batch per firm per cycle). Demand-driven admission avoids the
materialization that the fan-out path can afford for a single ~20k publish
but not for the 800k estate.

**Tradeoff (documented, honest)**: when few firms remain, pool utilization can
drop below B_backfill (one thread per remaining firm). Accepted: fairness
over tail throughput; `firmInFlightCap` is the knob if a future phase wants
parallel batches per firm.

**Alternatives considered**: (a) materialize all firm batches then reuse
`FairShardScheduler` — rejected, 8,000 batch objects for 800k engagements is
fine but 4,000 firm queues of *all* batches upfront forces enumeration of the
whole estate before the first load (enumerator must then be perfectly stable
upfront; spec B1 only promises stability per batch); (b) parallel engagements
within a batch — rejected for v1, complicates per-firm accounting for zero
gain while permits (18) < batch size (100).

---

## D8 — Halt and flag wiring on the fan-out path

**Decision**: new seam `fanout/AdmissionGate`:

```java
final class AdmissionGate {
    enum Verdict { ADMIT, GATED_PRODUCT, GATED_FIRM, HALTED }
    Verdict admit(String productId, String firmId);
}
```

Built over `FeatureFlags` (halt dominates) + `FanOutMetrics` rejection
counters (`fanout.admit.reject.{halt,product,firm}` and
`backfill.admit.reject.*` on the backfill side). `FairShardScheduler` gains an
optional admission predicate (default allow-all → existing constructor and
tests unchanged): a firm whose pending head chunk is not ADMIT is skipped for
the scan (same rule as "firm at cap" — skipped, never queued ahead). When
nothing is in flight and every pending firm's head is non-ADMIT, `runAll`
terminates with a new `SchedulerResult` (existing void `runAll` overload
delegates and ignores it). `FanOutEngine` maps: all chunks done → COMPLETED
(unchanged); halted with pending chunks → new outcome `HALTED`; gated with
pending chunks → new outcome `GATED` (both additive enum values on
`FanOutOutcomeStatus`). Cursor state already persists the completed prefix;
re-invoking `runPublish`/`runWithdrawSupersede` with the same trigger resumes
from the cursor (the delivered claim/resume path handles it — no drop, no
duplicate, FR-042/FR-043; gated work is never buffered, re-derived from the
cursor on demand).

**Rationale**: admission = the scheduler's pick; gating anywhere later (in the
chunk handler) would violate "stop remaining admission" (FR-041). Skipping by
firm mirrors the delivered at-cap skip, so fairness machinery is untouched.
The engine previously had no `FeatureFlags` dependency — this is the additive
wiring the spec requires (phase 3 shipped the port, not the wiring).

**Halt latency**: `haltSwitch()` is read live at every admission decision
(zero polling delay for the scheduler loop; blocked waiters re-check at each
BackoffWaiter pause — default 100 ms jittered, ≪ the 1 s poll bound). The
fan-out in-flight unit is an index-only chunk (sub-second), so the drain
completes immediately after admission stops.

---

## D9 — Permit acquisition with halt-abort (FR-031)

**Decision**: extend `PermitGuardedDownstream` with an injectable
`BooleanSupplier haltProbe` (default `() -> false`; backfill passes
`featureFlags::haltSwitch`). In the acquire loop, the probe is checked each
iteration *before* each backoff pause; on abort it throws the new unchecked
`AdmissionHaltedException` — a distinct, NON-transient signal: the
`RetryExecutor` must not catch it (only `TransientFanOutException` retries —
already true), it is never dead-lettered, and the backfill worker counts
`backfill.halt.abandoned` and leaves the engagement unresolved behind its
batch cursor. Released-permit invariant unchanged (release only after a
successful acquire, in `finally`).

**Rationale**: "backfill yields first" (spec B4/FR-031) needs the blocked
waiter to give up promptly instead of completing its admission pause cycle
holding nothing; a dedicated exception type keeps halt out of the
failure/DLQ taxonomy (halt is not an error, constitution VIII). The existing
loop structure (tryAcquire → invoke → finally release) is preserved, so the
delivered exactly-once release tests keep passing.

**Alternatives considered**: (a) return a sentinel from `load` — rejected,
pollutes the version-string return type; (b) worker-side acquire loop
(duplicating `PermitGuardedDownstream`) — rejected, two permit protocols to
keep exactly-once (constitution I: single sanctioned call path).

---

## D10 — Retry reuse

**Decision**: extract `RetryPolicy` record (maxAttempts, baseDelay, maxDelay,
jitterMin, jitterMax) from the `FanOutConfig` knob subset;
`RetryExecutor` gains a `RetryPolicy` constructor (existing `FanOutConfig`
constructor delegates — source-compatible). `BackfillWorker` builds its
`RetryExecutor` from `BackfillConfig`'s policy. Exhaustion stores the
D4-encoded `DeadLetterRecord` via the delivered `DeadLetterStore` and emits
the delivered `fanout.dlq` counter (tagged `unit_kind=BACKFILL_*`) — one
alerting surface. Non-transient failures (simulated process death in the
random-kill tests) propagate untouched, matching the delivered crash-test
pattern.

**Rationale**: the backoff/jitter/DLQ machinery is delivered, tested, and
spec-identical for both paths (spec B8 says "reuse the existing rules");
generalizing the policy carrier is a smaller change than forking the
executor.

---

## D11 — Configuration record

**Decision**: `BackfillConfig(batchSize=100, firmInFlightCap=1, parentC=20,
bBackfill=18, bRepair=2, haltPollInterval=1s, haltDrainBound=2min,
retryPolicy=RetryPolicy(5, 100ms, 30s, 0.5, 1.5))` with compact-constructor
validation: all ≥ 1 where applicable, `bBackfill + bRepair <= parentC`
(FR-021 startup rejection), drain bound positive. Tests override freely, as
with `FanOutConfig`.

**Mapping note (spec reconciliation)**: the spec's default
`firmInFlightCap = 8` (engagement units) is realized as concurrent *batches*
per firm with sequential engagements inside a batch (D7), so per-firm
in-flight engagements ≤ 1 by construction; the config knob is the batch-level
cap and tests may raise it to expose the invariant.

---

## D12 — Test strategy for the five mandated proofs

**Decision** (all deterministic — seeded RNG, latches, controllable clock):

1. **Random-kill property (SC-001)**: seeded RNG decides kill points as
   "after k-th batch completion" plus mid-batch positions; kill simulated by a
   fake that throws a non-transient `SimulatedProcessDeath` (RuntimeException
   subclass that `RetryExecutor` does not catch); restart = new
   `BackfillWorker` over the same in-memory checkpoint store + index writer;
   final assertion: exactly N rows, per-engagement create-count == 1
   (InMemoryIndexWriter audit), row set equals fault-free run for the same
   seed; k ∈ [1, 7] over a fixed seed table.
2. **Halt latency (SC-002)**: saturate B_backfill with latch-blocked loads;
   set halt; assert zero new admissions after the flag (scheduler-clock /
   admission-counter based), release latches; assert drain completes, all
   permits released (`budget.available() == capacity`), completed-batch
   checkpoints persisted; wall-time asserted only against the generous
   configured bound.
3. **Resume-no-duplicates (SC-003)**: crash between write and cursor advance;
   restart; assert duplicate-skip outcomes, identical row set; plus the
   after-halt resume case from (2).
4. **Flag-flip-mid-publish (SC-007)**: multi-firm publish; flip one firm's
   flag off mid-run via `FakeFeatureFlags`; assert that firm admits nothing
   further (rejection counters), other firms complete; outcome GATED; flip
   on; re-invoke same trigger; assert exact-once completion. Same pattern on
   the backfill path with product and firm flags.
5. **Real-time vs backfill independence (SC-004)**: `LatchDownstreamClient`
   holds all B_backfill permits (18 blocked backfill loads in flight); run a
   ~20k publish on the fan-out `TaskRunner`; assert COMPLETED inside the
   freshness budget (FakeClock), zero downstream calls attributable to the
   publish (call log = the 18 blocked backfill calls only), zero permit
   acquisitions on the hot path (fan-out has no budget dependency — asserted
   by construction test on the engine's dependencies plus the call log);
   concurrently a repair-share waiter acquires from the B_repair budget while
   all backfill permits are held (SC-005).

**Rationale**: each proof maps 1:1 to a spec success criterion; all use
delivered fakes (`FakeClock`, `LatchDownstreamClient`, `FakeCapacityBudget`,
`RecordingFanOutMetrics`, `InMemoryIndexWriter`) — no timing assumptions
anywhere (spec FR-055).

---

## Assumptions carried into design

- One customer database per firm (1:1); firm id == customer database id for
  cursor keys.
- Single backfill runner; duplicate runners are wasteful but row-safe
  (idempotent writes) — documented weakness, no distributed lock delivered.
- `CheckpointStore` in-memory fake does not survive real restarts; durability
  is the existing later-phase adapter obligation (unchanged from prior
  phases).
- Halt-drain bound (~2 min) is an alerting measurement target, not a
  correctness limit; drain past the bound keeps draining.
- `publishIds` on a backfilled row carries the effective publish's id —
  attribution, not a new event.
