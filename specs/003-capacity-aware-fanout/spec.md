# Feature Specification: Capacity-Aware Fan-Out Engine

**Feature Branch**: `003-capacity-aware-fanout`

**Created**: 2026-10-18

**Status**: Draft

## User Scenarios & Testing *(mandatory)*

Context: phases 1–2 delivered the boundary ports (index writer, checkpoint
store, metrics, capacity budget, downstream engagement-load client), the
immutable event records, and idempotent ingestion that emits a fan-out trigger
per resolved event. This feature consumes those triggers and performs the
actual fan-out: an ingested `TemplatePublished` publish (publish id, product id,
from-version, to-version) must turn into Pending Update Index rows for every
eligible engagement. Scale: one typical publish reaches ~20,000 engagements
(~800,000 engagements across 40 products, 4,000 firms; median firm ~40
engagements, largest firm ~40,000 — heavily skewed). The publish hot path
writes index rows from metadata only and MUST NEVER call the shared downstream
engagement-load service (~1 minute per call, owned by another team, in-flight
budget C = 20, configurable). Freshness SLO for the finished system:
publish-to-pending p99 < 60 seconds. No engagement content in logs, ever.

### User Story 1 - Publish fans out to pending rows without touching the downstream (Priority: P1)

A publish trigger arrives. The engine streams the publish's eligible
engagements from index metadata (never loading engagement content), splits them
into fixed-size chunks, and writes one pending-index row per engagement through
the existing index-writer port. The shared downstream engagement-load service
is never called on this path — its call log must stay empty for every publish.
Because ingestion is at-least-once and tasks may be executed twice, replaying
any chunk must produce no duplicate rows: identical rewrites are no-ops and
concurrent writers resolve by compare-and-set on the row version.

**Why this priority**: this is the feature's core value — the pending-update
indicator that users see. It also carries the hardest constraint: the
publish hot path must complete inside the 60-second freshness budget while a
forbidden, ~1-minute-per-call dependency sits one port away.

**Independent Test**: Deliver one publish trigger for a product with 1,000
eligible engagements; assert 1,000 pending rows appear through the index
writer, the downstream call log records zero calls, and re-running the whole
fan-out for the same publish changes no row (all writes resolve as duplicate
skip or compare-and-set no-op).

**Acceptance Scenarios**:

1. **Given** an ingested publish trigger for product P, **When** the engine
   runs, **Then** every engagement eligible for P's to-version receives exactly
   one pending-index row written through the index writer, and no engagement
   content is read, written, or logged.
2. **Given** a publish fan-out in progress or complete, **When** the
   downstream engagement-load client's call log is inspected, **Then** it
   records zero calls.
3. **Given** a completed fan-out for publish X, **When** the identical chunk
   executions are replayed, **Then** the row set is unchanged: each write
   resolves as a duplicate skip or a compare-and-set rejection with stored
   state untouched.
4. **Given** two workers writing the same row concurrently with different
   expected row versions, **When** both writes race, **Then** exactly one wins,
   the loser observes a stale rejection, and stored state equals the winner's
   content — deterministically.

---

### User Story 2 - Skewed firms are served fairly (Priority: P1)

A publish touches one firm with 40,000 engagements and thousands of median-40
firms. Scheduling must round-robin across firms with a per-firm in-flight cap
so the giant firm's chunks cannot monopolize the bounded worker pool and
starve small firms. Progress for every firm advances steadily; no firm waits
for the largest firm to finish.

**Why this priority**: without fairness, one customer's size degrades service
for all others — a contractual and reputational risk in a multi-tenant
platform, and a direct threat to the freshness SLO for the majority of firms.

**Independent Test**: Simulate a publish spanning a 40,000-engagement firm and
500 median-40 firms; assert that at any observation point no firm holds more
than its in-flight cap, and that median firms complete long before the giant
firm drains — e.g. every median firm finishes while the giant firm is still
early in its chunks.

**Acceptance Scenarios**:

1. **Given** a publish spanning firms of widely different sizes, **When**
   chunks are scheduled, **Then** no firm ever exceeds its configured
   in-flight chunk cap.
2. **Given** a 40,000-engagement firm alongside median-40 firms, **When** the
   bounded pool runs, **Then** median firms' chunks are interleaved with the
   giant firm's chunks in round-robin order rather than after it.
3. **Given** the worker pool at its concurrency ceiling, **When** a small
   firm's chunk becomes eligible, **Then** it is admitted at the next
   round-robin opportunity instead of waiting behind the giant firm's backlog.

---

### User Story 3 - Crash mid-publish resumes with no gaps and no duplicates (Priority: P1)

The engine records a per-publish chunk cursor in the external checkpoint store
after each chunk completes. If the process dies mid-publish, restart re-reads
the cursor and resumes from the first chunk not yet marked complete. Already
written chunks are not re-processed; if a chunk is re-executed anyway (crash
between write and cursor advance, or duplicate task execution), the idempotent
index writes keep the row set identical. No engagement is skipped.

**Why this priority**: at-least-once delivery plus process crashes is the
normal operating condition. A fan-out that loses rows on restart silently
breaches the coverage guarantee; one that re-writes duplicates corrupts the
index.

**Independent Test**: Run a fan-out with a fault injected after N chunks, kill
the process, restart against the same checkpoint store, and assert: the
resumed run starts at chunk N+1, the final row set equals a fault-free run's
row set exactly, and no chunk produced duplicate rows.

**Acceptance Scenarios**:

1. **Given** a publish whose fan-out completed chunks 0..k-1, **When** the
   process is killed and restarted, **Then** execution resumes at chunk k with
   no gap (every eligible engagement eventually written) and no re-write of
   chunks 0..k-1.
2. **Given** a crash between a chunk's index writes and its cursor advance,
   **When** the chunk runs again after restart, **Then** the resulting row set
   is identical to single execution — zero duplicate rows.
3. **Given** the same chunk submitted twice concurrently, **When** both
   executions run, **Then** the row set equals single-execution output and the
   cursor advances once.
4. **Given** a completed publish, **When** its cursor is inspected, **Then**
   it records the chunk count and last completed chunk for audit.

---

### User Story 4 - Transient failures retry with bounds, then dead-letter with an alert (Priority: P2)

Index writes and downstream calls (off-path use only) fail transiently. Each
failed unit retries with bounded exponential backoff and jitter, driven by a
controllable clock so tests stay deterministic. When the retry budget is
exhausted, the unit — never silently dropped — moves to a dead-letter store
with full identity for later repair, and an alert metric increments. Replay of
dead-lettered or retried units remains safe through chunk idempotency.

**Why this priority**: silent drops are the failure mode that destroys trust;
an alerted, replayable dead-letter path converts an outage into a recoverable
 backlog. It ranks below the core fan-out because the happy path must exist
first.

**Independent Test**: Configure an index writer that fails k times then
succeeds; advance the fake clock through the backoff schedule; assert attempt
counts and backoff delays are bounded and jittered within bounds. Set failures
to exceed the retry budget; assert the unit lands in the dead-letter store with
its identity and an alert counter increments, with exactly zero lost and zero
duplicated rows after replay.

**Acceptance Scenarios**:

1. **Given** a transient failure on a unit, **When** retries run, **Then**
   delays grow exponentially from a configured base to a configured maximum,
   total attempts never exceed the configured budget, and each delay carries
   jitter within its computed bound.
2. **Given** a unit exhausting its retry budget, **When** exhaustion occurs,
   **Then** the unit is written to the dead-letter store with its publish id,
   chunk id, and failure identity, an alert metric increments, and the unit is
   not silently dropped.
3. **Given** an exhausted unit later replayed from the dead-letter store,
   **When** it succeeds, **Then** the index state equals what a first-attempt
   success would have produced.
4. **Given** downstream failures during any off-path use, **When** the budget
   is exhausted, **Then** the same retry, dead-letter, and alert rules apply.

---

### User Story 5 - Withdrawn version supersedes pending offers (Priority: P2)

A `TemplateWithdrawn` trigger retracts a product version. Every index row
offering the withdrawn version must move back to `CREATED` state, or — if the
product has a next eligible non-withdrawn version — be re-offered at that
version. Recorded decisions (declines, pins) made against the withdrawn version
are preserved as history, never rewritten or lost. The supersede fan-out
follows the same chunking, fairness, checkpoint, and retry machinery as a
publish.

**Why this priority**: leaving a withdrawn version offered to users is a
correctness and trust defect; preserving decision history is required for
deterministic resolution of accumulated user decisions.

**Independent Test**: Complete a publish fan-out offering version V with some
rows declined-and-pinned; withdraw V; assert every V-offering row now offers
the next non-withdrawn version (or returns to `CREATED` when none exists),
that decline/pinned decision records survive untouched, and the downstream call
log is still empty.

**Acceptance Scenarios**:

1. **Given** rows offering withdrawn version V, **When** the withdraw trigger
   is fanned out, **Then** each such row transitions to `CREATED` when no
   non-withdrawn version follows, else re-offers the next eligible
   non-withdrawn version.
2. **Given** rows with recorded decisions against V, **When** V is withdrawn,
   **Then** the decision records remain preserved as history on the row.
3. **Given** a withdraw supersede run, **When** the downstream call log is
   inspected, **Then** it records zero calls.
4. **Given** a supersede run interrupted by a crash, **When** it resumes,
   **Then** it follows the same cursor, idempotency, and fairness rules as a
   publish fan-out.

---

### Edge Cases

- Publish for a product with zero eligible engagements: fan-out completes
  immediately with a completed cursor, zero writes, zero downstream calls.
- Firm appearing in many chunks while at its in-flight cap: further chunks for
  that firm wait; other firms continue — no deadlock, no starvation.
- Worker pool fully occupied by retry backoff waits: admission of new chunks
  pauses (backpressure) rather than queueing unboundedly.
- Downstream slow/failing during any off-path use: permit acquisition pauses
  admission; in-flight calls never exceed C; no buffer grows without bound.
- Cursor stored but the chunk list not reconstructible (corrupt cursor): the
  run fails loudly to dead-letter/alert — never silently restarts from zero
  with duplicate side effects assumed absent.
- Withdraw of a version never offered (no rows reference it): supersede is a
  no-op that still completes with a cursor.
- Withdraw for a product whose next version equals the withdrawn version
  (republish): supersede selects by the deterministic eligibility rule below,
  never by version-string arithmetic.
- Duplicate trigger for the same publish arriving while the first run is
  mid-flight: the second run observes the same cursor and converges to the
  same final state; row-level idempotency absorbs overlap.
- Chunk execution that completes all writes but dies before cursor write: on
  resume the chunk re-runs and every write resolves as duplicate skip or
  stale-rejection — identical row set.
- Eligibility source failing mid-stream: the run retries the stream per the
  retry rules; a partial stream never advances the cursor past the failed
  point.
- Log/metric emission anywhere in the engine: only identifiers and
  measurements; engagement content never appears.

## Requirements *(mandatory)*

### Fan-Out Rules (normative)

These rules are binding; user stories and tests above depend on them.

- **R1 — Eligibility source**: eligibility for (product id, publish id) is
  streamed from index metadata only, as lightweight engagement references
  (engagement id, firm id, product id, current version, region). The eligibility
  source MUST NOT load engagement content and MUST NOT call the downstream
  engagement-load service. The stream is ordered deterministically: grouped by
  firm id (ascending), engagement id ascending within firm, so chunking and
  cursor resume are reproducible.
- **R2 — Chunking and cursor**: the ordered eligibility stream is split into
  chunks of a configured size (default 500 engagements, configurable). Each
  publish (or supersede run) has one cursor in the checkpoint store recording
  the last completed chunk index and the total chunk count. The cursor advances
  only after every write of that chunk has resolved as success (written,
  duplicate skip, or stale-rejection handled per R4). Resume starts at
  `lastCompletedChunkIndex + 1`. Chunks are indexed positionally over the
  deterministic stream from R1, so resume needs no gap.
- **R3 — Fair sharding**: chunks are scheduled by round-robin over firms: in
  each scheduling cycle, every firm with pending chunks contributes at most one
  chunk, in firm-id order, and firms rotate. Each firm has an in-flight cap
  (default 2 concurrent chunks, configurable); a firm at its cap is skipped for
  the cycle, not queued ahead of others. Total in-flight chunks never exceed
  the bounded worker pool size.
- **R4 — Idempotent writes**: every row write goes through the existing
  index-writer port. Rewriting an identical effective row resolves as a
  duplicate skip with stored state unchanged. An update presents the exact
  expected row version; a stale presentation is rejected and stored state is
  unchanged. On stale rejection the engine re-reads the current row, recomputes
  the intended content against the fresh version, and re-applies once; a second
  stale rejection is treated as a transient failure under R6. The final stored
  content for a row is a deterministic function of (current version, offered
  version, state, recorded decisions) regardless of writer interleaving.
- **R5 — Capacity and backpressure**: any call to the downstream
  engagement-load client first acquires a permit from the capacity budget;
  permits are released exactly once per acquired call; in-flight downstream
  calls never exceed C (default 20, configurable). When no permit is
  available, admission of the dependent unit pauses and is retried — the engine
  MUST NOT queue an unbounded backlog of admitted-but-waiting downstream work.
  The publish and withdraw hot paths acquire zero permits and make zero
  downstream calls. Off-path repair/reconciliation use of the downstream
  obeys the same C limit.
- **R6 — Retry, backoff, dead-letter**: transient failures of a work unit
  (one chunk's writes, one eligibility stream segment, or one off-path
  downstream call) are retried up to a configured attempt budget (default 5)
  with exponential backoff from a configured base delay (default 100 ms) capped
  at a configured maximum (default 30 s), each delay scaled by a jitter factor
  in [0.5, 1.5). Time is read through an injectable clock. On exhaustion the
  unit is written to the dead-letter store with its identity (publish id,
  product id, chunk index, unit kind, attempt count, last failure identity) and
  an alert metric increments — never a silent drop. Dead-lettered units are
  replayable and safe to re-execute per R4.
- **R7 — Withdrawn supersede**: for a withdrawn version V of product P, every
  row offering V is re-evaluated. The next eligible version is the to-version
  of the ingested, non-withdrawn publish for P with the greatest (published-at
  timestamp, publish id) pair among publishes strictly newer than V's publish
  under the phase-2 effective-version rule; if none exists the row returns to
  `CREATED`. Recorded decisions (declined, pinned ranges) on the withdrawn
  version are preserved as history on the row and are never cleared or
  rewritten by the supersede. Supersede runs are fan-out runs: same chunking
  (R2), fairness (R3), idempotency (R4), retry (R6), and checkpoint rules.

### Functional Requirements

**Eligibility and fan-out core**

- **FR-001**: The system MUST provide an eligibility-source port that, given
  (product id, publish id), streams engagement references from index metadata
  without engagement loads; a fake implementation MUST be provided for tests.
- **FR-002**: The fan-out engine MUST, on trigger, stream eligible
  engagements per R1, split them into chunks per R2, and execute chunks on a
  bounded worker pool whose maximum in-flight chunk count is configurable.
- **FR-003**: Chunks MUST be positioned over the deterministic eligibility
  order so that chunk indices are stable across runs and restarts.
- **FR-004**: A publish with zero eligible engagements MUST complete with a
  completed cursor, zero writes, and zero downstream calls.

**Fair sharding**

- **FR-010**: Scheduling MUST be per-firm round-robin with a configurable
  per-firm in-flight cap, per R3, so a 40,000-engagement firm cannot starve
  median-40 firms.
- **FR-011**: At any observation point, the number of in-flight chunks for any
  single firm MUST NOT exceed its cap, and total in-flight chunks MUST NOT
  exceed the pool bound.
- **FR-012**: The system MUST provide a fairness test asserting that median
  firms complete while the largest firm is still incomplete, at the stated
  scale distribution.

**Idempotent index writes**

- **FR-020**: All row writes MUST go through the existing index-writer port;
  replaying a chunk MUST create no duplicate rows (per R4).
- **FR-021**: Concurrent writers MUST resolve deterministically via
  row-version compare-and-set: exactly one write wins per version step, losers
  observe stale rejection with stored state unchanged, and the engine
  re-applies once against the fresh version.
- **FR-022**: A chunk counts as complete only when every row write has
  resolved as written, duplicate skip, or a stale-rejection cycle that
  converged per R4.

**Capacity control**

- **FR-030**: Every downstream engagement-load call MUST acquire a permit
  first; in-flight downstream calls MUST NEVER exceed the configurable limit C
  (default 20), and each acquired permit MUST be released exactly once.
- **FR-031**: When permits are unavailable, admission MUST pause
  (backpressure); the engine MUST NOT buffer an unbounded number of
  admitted-but-waiting downstream units.
- **FR-032**: Publish-path and withdraw-path fan-out MUST make zero downstream
  calls; every publish and withdraw test MUST assert the fake downstream call
  log is empty.
- **FR-033**: Off-path repair/reconciliation use of the downstream MUST obey
  the same permit limit C; an off-path test MUST demonstrate C-limit
  enforcement.

**Retry and dead-letter**

- **FR-040**: Transient failures of index writes and downstream calls MUST be
  retried with bounded exponential backoff and jitter per R6, with attempt
  count and delay bounds configurable.
- **FR-041**: On retry exhaustion the unit MUST be written to a dead-letter
  store (new port, with a fake for tests) carrying its full work identity, and
  an alert metric MUST increment via the metrics port — never a silent drop.
- **FR-042**: Backoff timing MUST be driven by an injectable clock so retry
  tests are deterministic (no sleep-based synchronization).
- **FR-043**: Dead-lettered units MUST be replayable, and replay MUST produce
  the same final index state as first-attempt success (via R4).

**Checkpoints and crash-resume**

- **FR-050**: Each publish and each supersede run MUST record a per-publish
  chunk cursor in the existing checkpoint store, written after each chunk
  completes, outside process memory.
- **FR-051**: After a mid-publish kill and restart, execution MUST resume at
  the first incomplete chunk with no gap in coverage and no re-processed writes
  producing duplicate rows.
- **FR-052**: A crash between chunk writes and cursor advance MUST be
  absorbed: the re-executed chunk yields the identical row set.
- **FR-053**: Duplicate chunk execution (concurrent or sequential) MUST yield
  the row set of single execution and advance the cursor exactly once.

**Withdraw handling**

- **FR-060**: On a withdraw trigger, every row offering the withdrawn version
  MUST transition to `CREATED`, or re-offer the next eligible non-withdrawn
  version per R7, when one exists.
- **FR-061**: Recorded decisions on withdrawn versions MUST be preserved as
  history — never cleared, overwritten, or recomputed by supersede.
- **FR-062**: Supersede MUST use the same chunking, fairness, checkpoint,
  idempotency, and retry machinery as publish fan-out.
- **FR-063**: A withdraw with no rows offering the version MUST complete as a
  no-op run with a cursor.

**Safety, telemetry, and tests**

- **FR-070**: The engine MUST NOT read, write, or log engagement content in
  any log line or metric field; only identifiers and measurements are
  permitted, and correctness MUST NOT depend on metric values.
- **FR-071**: The metrics port MUST expose counters for fan-out progress
  (chunks started/completed), retries, dead-letter alerts, permit
  rejections (backpressure pauses), and downstream in-flight high-water mark,
  plus a timer for publish-to-pending latency.
- **FR-072**: Tests MUST be written first and MUST cover, deterministically
  (latches and fake clocks, no sleep-based sync): concurrency ceiling,
  C-limit, firm fairness, retry bounds (attempts and delay bounds), dead-letter
  on exhaustion, crash-resume mid-publish, duplicate chunk execution,
  withdrawn supersede with decision-history preservation, hot-path zero
  downstream calls, and a simulated 40,000-engagement publish completing with
  downstream in-flight never exceeding C.
- **FR-073**: All tests MUST run inside the project's existing single
  verification command, self-contained, with no external systems or external
  document references.

### Key Entities

- **EngagementRef**: lightweight handle to one engagement (engagement id, firm
  id, product id, current version, region); the only engagement-related payload
  permitted off the hot path; never carries engagement content.
- **FanOutTask (chunk)**: one schedulable unit covering a positional slice of a
  publish's ordered eligibility stream; identified by (publish id, chunk
  index); executed at-least-once, safe to replay per R4.
- **FanOutCursor**: per-publish progress record in the checkpoint store — total
  chunk count plus last completed chunk index; the sole resume truth (R2).
- **PendingIndexRow**: existing index row (unchanged); the fan-out's write
  target, with idempotency key (engagement id, offered version, state) and
  row-version compare-and-set token.
- **DeadLetterRecord**: identity of an exhausted work unit — publish id,
  product id, chunk index, unit kind, attempt count, last failure identity —
  held in the new dead-letter store port for replay.
- **CapacityBudget state**: the permit pool for downstream calls (limit C,
  available count, high-water mark); shared by hot-path-external callers only.
- **FanOutTrigger / TemplatePublished / TemplateWithdrawn**: existing records
  from phases 1–2; inputs to this feature, unchanged.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Zero downstream engagement-load calls occur across 100% of
  publish and withdraw hot-path tests (asserted on every test).
- **SC-002**: In the simulated 40,000-engagement publish, observed downstream
  in-flight calls never exceed C at any sample point, and the publish completes
  with all 40,000 rows present exactly once.
- **SC-003**: 100% of crash-resume tests show final row sets identical to a
  fault-free run: zero missing rows, zero duplicate rows, resume starting at
  the first incomplete chunk.
- **SC-004**: 100% of duplicate-chunk-execution tests yield the single-execution
  row set and a single cursor advance.
- **SC-005**: In the fairness test at the stated distribution, 100% of median
  firms complete while the largest firm is still incomplete, and no firm ever
  exceeds its in-flight cap at any observation point.
- **SC-006**: 100% of retry tests show attempt counts within the configured
  budget and every backoff delay within its computed jittered bound, with zero
  sleep-based synchronization anywhere in the suite.
- **SC-007**: 100% of exhausted units appear in the dead-letter store with full
  work identity and increment the alert counter — zero silent drops — and
  100% of replays converge to the first-attempt-success state.
- **SC-008**: In the withdrawn-supersede tests, 100% of rows offering the
  withdrawn version reach the correct target state (`CREATED` or the next
  eligible version) and 100% of recorded decision histories are preserved
  byte-for-byte.
- **SC-009**: Total in-flight chunks never exceed the configured pool bound in
  100% of scheduling observations (concurrency ceiling invariant).
- **SC-010**: The publish hot path (trigger received to all rows resolved)
  completes for a typical ~20,000-engagement publish inside the 60-second
  publish-to-pending p99 freshness budget under test-scale timing.
- **SC-011**: All delivered artifacts pass a self-containment review: zero
  references to external documents, and zero engagement content in any log or
  metric output across the suite.

## Assumptions

- Chunk size defaults to 500 engagements, worker-pool bound defaults to 16
  concurrent chunks, per-firm in-flight cap defaults to 2, retry budget
  defaults to 5 attempts with 100 ms base and 30 s maximum backoff, and the
  downstream permit limit C defaults to 20 — all configurable; tests may set
  other values to expose invariants.
- "Eligible" means engagements referenced by the index metadata for the given
  (product id, publish id) — the eligibility source's own contents; computing
  eligibility from engagement data is not this feature's responsibility.
- Version strings are opaque; the "next eligible version" rule (R7) reuses the
  phase-2 effective-version ordering (greatest (published-at timestamp, publish
  id) wins) restricted to non-withdrawn publishes — no version arithmetic.
- Row-version semantics (idempotency key, stale rejection, re-read-and-apply
  once) are the phase-1 index-writer contract; this feature adds the
  engine-side re-read/re-apply loop on top.
- Backpressure means the engine stops admitting dependent units and retries
  acquisition with backoff; it does not mean failing the fan-out. A
  persistently exhausted downstream surfaces through retry exhaustion →
  dead-letter → alert (R6), not through unbounded queues.
- Dead-letter records are retained for later manual or automated replay;
  replay tooling, pruning, and alert routing destinations are out of scope —
  only the store port, fake, and alert counter are delivered.
- Off-path downstream usage (repair/reconciliation) is not built in this
  feature; only the permit-enforcement invariant for any such future caller is
  specified and tested via the fake.
- Freshness SLO p99 < 60 s applies to the finished system; this phase asserts
  it at test scale (SC-010) for the fan-out portion only.
- The eligibility source reads index metadata through test fakes in this
  phase; a real metadata adapter is a later phase (port contract only).
- Firm ids and engagement ids are non-blank opaque strings and stable across
  runs, which is what makes positional chunking and round-robin deterministic.

## Out of Scope

The following are explicitly excluded from this phase and MUST NOT be built now:

- Backfill of pre-feature engagements
- Feature-flag staged rollout
- Real infrastructure adapters (real eligibility source, real dead-letter
  store, real downstream client wiring beyond the existing fake)
- Observability beyond the counters/timers the tests need (FR-071)
- Alert routing destinations and dashboards
- Dead-letter replay tooling and pruning
- Reconciliation/repair jobs themselves (only their rate-limit invariant is
  specified)
- UI
- Applying template content to engagement files
- Engagement content handling of any kind, in code paths, logs, or metrics
