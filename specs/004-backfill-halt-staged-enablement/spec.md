# Feature Specification: Backfill, Halt, and Staged Enablement

**Feature Branch**: `enabling_backfill`

**Created**: 2026-10-19

**Status**: Draft

## User Scenarios & Testing *(mandatory)*

Context: the live system already ingests template-publish events idempotently and
fans out publish triggers into pending-update index rows through a
capacity-aware engine: bounded worker pool, per-firm round-robin scheduling, a
capacity budget of C = 20 in-flight permits for the shared downstream
engagement-load service (~1 minute per call, owned by another team), bounded
retry with backoff, a dead-letter store, and per-publish chunk-cursor
checkpoints. The real-time publish path (~20,000 rows per publish) is
index-metadata-only: it makes zero downstream loads and meets publish-to-pending
p99 < 60 seconds.

This feature adds three capabilities to that live system, with no maintenance
window:

1. **Backfill**: ~800,000 engagement files created before the feature existed
   have no pending-update coverage. They must receive rows incrementally.
   Learning one pre-feature engagement's current template version costs one
   shared downstream load (~1 minute, budget C = 20 total). At the full budget
   of 20, ~800,000 loads take ~27.8 days of continuous operation — pacing is a
   knob, not a deadline.
2. **Halt**: an operator (or automation) must be able to stop all new work
   within minutes, without a redeploy, and later resume with no duplicate and
   no missing rows.
3. **Staged enablement**: per-product and per-firm flags must gate admission on
   both the fan-out and backfill paths so rollout can widen gradually and a bad
   change can be contained to one firm or product — also without redeploy.

Firm sizes are heavily skewed: the largest firm has ~40,000 engagements, the
median firm ~40, across ~4,000 firms. Fairness across firms is required on the
backfill path just as on the fan-out path. Logs and metrics carry identifiers
and measurements only, never engagement content.

### User Story 1 - Backfill creates pending rows for pre-feature engagements (Priority: P1)

An incremental backfill runs while the system stays live. It enumerates
engagement references per customer database in stable, resumable batches keyed
by engagement id; for each engagement it acquires a downstream permit, captures
the engagement's current template version via the shared load service, and —
when the product's effective version is newer — writes one idempotent
pending-update row through the existing index-writer port. A batch's external
checkpoint advances only after every engagement in it has resolved (row
written, no-op, or dead-lettered). Re-running any engagement or any batch
produces the identical row set: identical rewrites are duplicate skips.

**Why this priority**: without backfill, the pending-update indicator silently
misses every engagement that predates the feature — the majority of the
estate. This is the feature's core value, and everything else (halt, flags)
governs how it runs safely.

**Independent Test**: enumerate a seeded fake of 5,000 engagements across 50
firms; run backfill to completion against a fake downstream whose call log and
a fake index writer's create-count are auditable; assert exactly the expected
row set exists, every row was created exactly once, every engagement's
downstream load was acquired under a permit, observed in-flight loads never
exceeded the configured backfill share, and engagements already at the
effective version produced no row and no failure.

**Acceptance Scenarios**:

1. **Given** pre-feature engagements whose captured versions are older than
   their product's effective version, **When** backfill completes, **Then**
   exactly one pending-update row exists per such engagement, offering the
   effective version, written through the index-writer port.
2. **Given** an engagement whose captured version already equals the product's
   effective version, **When** it is processed, **Then** no row is written, no
   failure is recorded, and its batch still resolves and checkpoints.
3. **Given** backfill of N covered engagements, **When** the entire run is
   replayed from a cleared cursor, **Then** the create-count per engagement
   remains exactly one and the final row set is byte-identical.
4. **Given** a poisoned engagement whose downstream load always fails, **When**
   its retry budget is exhausted, **Then** it lands in the dead-letter store
   with full work identity, an alert counter increments, its batch still
   checkpoints (the poisoned engagement does not block its firm forever), and
   all other engagements in the batch are unaffected.
5. **Given** a backfill run in progress, **When** downstream in-flight is
   sampled at any point, **Then** it never exceeds the configured backfill
   share and each acquired permit is released exactly once.

---

### User Story 2 - Interruption and restart never duplicate or drop rows (Priority: P1)

The backfill process is killed at arbitrary points — mid-batch, between load
and write, between write and checkpoint. On restart it reads the external
per-firm checkpoints and resumes. Engagements inside a partially completed
batch may be loaded again (at most one batch per firm), but idempotent writes
make the row set identical to an uninterrupted run: exactly N rows, each
created once. A seeded random-kill property test kills the run k times and
asserts the final state equals the fault-free state.

**Why this priority**: the system is live with no maintenance window; crashes,
deploys, and rollbacks are routine. A backfill that duplicates rows corrupts
the index; one that drops rows silently breaks the coverage guarantee.

**Independent Test**: run backfill over N = 3,000 engagements with a seeded
RNG that kills the run k ∈ [1, 7] times at random admission/load/write/checkpoint
points; after the final restart, assert exactly N rows exist, each engagement's
create-count is exactly one, the fake index writer's audit log shows no other
mutation, and per-firm checkpoints are complete.

**Acceptance Scenarios**:

1. **Given** a kill between a batch's last write and its checkpoint write,
   **When** the run restarts, **Then** the batch re-runs, every write resolves
   as a duplicate skip, the checkpoint advances, and no row is created twice.
2. **Given** a kill mid-batch after m of M engagements resolved, **When** the
   run restarts, **Then** work resumes at that batch (at most one batch of
   redundant loads per firm), all M engagements resolve, and the row set equals
   the fault-free run.
3. **Given** k random kills across the run, **When** the final restart
   completes, **Then** exactly N rows exist with per-engagement create-counts
   of exactly one — for every seeded k.
4. **Given** a real-time publish covering an engagement mid-backfill, **When**
   both paths write for that engagement, **Then** the idempotency key collapses
   them: one created row, one duplicate skip, no divergence.

---

### User Story 3 - Halt stops all admission within minutes; resume is clean (Priority: P1)

An operator flips the global halt switch. Within the configured poll interval,
no new fan-out chunk, backfill batch, backfill engagement, or permit wait is
admitted anywhere. Backfill yields first: its waiters abandon permit
acquisition immediately and its admission checks fire at every engagement.
Already in-flight loads (index-only fan-out chunks are sub-second; backfill
loads ~1 minute) drain, completed batches persist their checkpoints, and every
permit is released. Nothing is lost. When the halt clears and the process
restarts or reschedules, work resumes from the persisted checkpoints with no
duplicate and no missing rows.

**Why this priority**: "stop now, without a deploy, within minutes" is the
operator's primary safety lever on a live system; the resume guarantee is what
makes flipping it safe to do.

**Independent Test**: saturate backfill with blocked fake loads; set the halt
flag; assert (on the controllable scheduler clock) zero new admissions after
the flag is observed, all in-flight fake loads complete, checkpoints for
completed batches are persisted, all permits are released, and drain wall-time
stays within the configured bound; clear the flag, restart, and assert the run
completes with the exact fault-free row set.

**Acceptance Scenarios**:

1. **Given** a running system, **When** the halt switch is set, **Then** new
   admissions (fan-out chunks, backfill batches, backfill engagements, permit
   waits) stop within one configured poll interval of the flag's next
   observation at each admission point.
2. **Given** backfill permit waiters blocked when halt is set, **When** the
   waiters are next scheduled, **Then** they abandon the wait immediately,
   hold no permit, and record a halt-rejection counter — backfill yields first
   and never delays the fan-out path's drain.
3. **Given** in-flight backfill loads at halt time, **When** the drain
   finishes, **Then** every in-flight load has completed (success or failure
   per the normal retry rules), completed batches have persisted checkpoints,
   and total drain wall-time is within the configured halt-drain bound (~2
   minutes at the default 1-minute load latency).
4. **Given** a halted system with persisted checkpoints, **When** the halt
   clears and work resumes, **Then** execution continues at the first
   incomplete batch per firm and the final row set equals the never-halted
   run — zero duplicates, zero gaps.
5. **Given** a halt observed by the fan-out path, **When** mid-publish chunks
   are pending, **Then** the publish's cursor persists and the publish
   completes after resume — no trigger is dropped.

---

### User Story 4 - Real-time publishes never queue behind backfill (Priority: P1)

Real-time fan-out and backfill run on disjoint concurrency budgets. The
fan-out worker pool and the backfill worker pool share no threads. The
downstream capacity C = 20 is partitioned into a backfill share and a separate
small real-time repair share; waiters on one share can never consume a permit
of the other, and the shares never sum above C. The publish hot path itself
acquires zero permits and makes zero downstream calls — it is structurally
independent of downstream saturation. A test proves a full publish completes
inside its freshness budget while backfill holds every permit of its share.

**Why this priority**: the freshness SLO (p99 < 60 s) is a live contractual
surface; if a 27.8-day backfill could ever queue real-time publishes behind
itself, the backfill would breach the SLO by existing.

**Independent Test**: block fake downstream loads until all backfill-share
permits are held; run a publish fan-out at the ~20,000-engagement scale;
assert it completes inside the freshness budget, acquires zero permits, makes
zero downstream calls, and observes zero queueing behind the backfill pool;
concurrently, a real-time-repair-path waiter acquires a repair-share permit
while every backfill permit is held; assert parent-C accounting never exceeds
C.

**Acceptance Scenarios**:

1. **Given** backfill saturating its entire share, **When** a publish arrives,
   **Then** fan-out chunks are admitted on the fan-out pool immediately and
   the publish completes within the freshness budget.
2. **Given** all backfill-share permits held, **When** a repair-path caller
   requests a permit, **Then** it acquires from the repair share without
   waiting for backfill, and parent-C in-flight never exceeds C.
3. **Given** any observation point, **When** permits are counted, **Then**
   backfill in-flight ≤ B_backfill, repair in-flight ≤ B_repair, and
   B_backfill + B_repair ≤ C.
4. **Given** backfill and fan-out pools running together, **When** the backfill
   pool is fully blocked, **Then** fan-out pool throughput is unchanged from a
   backfill-free baseline.

---

### User Story 5 - Fair enumeration: the giant firm cannot monopolize backfill (Priority: P2)

Backfill enumeration is round-robin across firms: each scheduling cycle, every
firm with remaining engagements contributes at most one batch, in firm-id
order, and firms rotate. A per-firm in-flight engagement cap keeps the
40,000-engagement firm from hoarding permits while median-40 firms wait. Median
firms complete while the giant firm is still early in its backlog.

**Why this priority**: fairness is required for multi-tenant equity and for
the paced timeline to be meaningful, but it rides on top of a correct backfill
path, hence P2.

**Independent Test**: fake a distribution of one 40,000-engagement firm plus
500 median-40 firms; run backfill with a small share; assert at every
observation no firm exceeds its in-flight engagement cap, and every median
firm completes while the giant firm is still incomplete.

**Acceptance Scenarios**:

1. **Given** firms of widely different sizes, **When** batches are admitted,
   **Then** each cycle admits at most one batch per firm, in rotating firm-id
   order.
2. **Given** the giant firm at its in-flight engagement cap, **When** more of
   its engagements are ready, **Then** they wait while other firms' engagements
   are admitted.
3. **Given** the stated distribution, **When** the run is sampled, **Then**
   every median firm reaches a complete checkpoint while the giant firm's
   checkpoint is incomplete.

---

### User Story 6 - Staged enablement per product and per firm (Priority: P2)

Per-product and per-firm flags gate admission on both the fan-out and backfill
paths. A flag flip takes effect at the next admission decision — no redeploy.
Flipping a firm's flag off mid-publish stops that firm's remaining chunk
admission while other firms continue; flipping a backfill flag off stops that
firm's (or product's) remaining backfill admission at the next batch or
engagement boundary. Gated-off work is not a failure and is never dropped: it
stays behind its checkpoint and is re-derived when the flag re-enables.
Re-enabling resumes from checkpoints with no duplicate and no missing rows.

**Why this priority**: staged enablement is the containment mechanism for a bad
rollout on a live system; it is delivered after the paths it gates are correct
and haltable.

**Independent Test**: run a publish spanning many firms; flip one firm's flag
off at chunk m; assert that firm admits no further chunks while others
complete; flip it back on; assert the firm's remaining chunks are admitted from
the cursor and the final row set is exactly-once complete; repeat the pattern
on the backfill path with a per-firm and a per-product flag.

**Acceptance Scenarios**:

1. **Given** a publish mid-flight for firm F, **When** F's flag is set off,
   **Then** F's remaining chunks are not admitted after the flag is observed,
   other firms' chunks continue, and no failure is recorded for F's gated
   chunks.
2. **Given** F's flag re-enabled, **When** the scheduler runs, **Then** F's
   remaining chunks are admitted from the persisted publish cursor and the
   publish's final row set is complete with each row created once.
3. **Given** backfill progressing for firm F (or product P), **When** the
   corresponding flag is set off, **Then** admission of F's (P's) remaining
   batches and engagements stops at the next admission check; other firms
   continue unaffected.
4. **Given** gated-off work spanning many scheduler cycles, **When** the flag
   stays off, **Then** no unbounded queue accumulates — gated work is derived
   from the cursor on demand, not buffered in memory.
5. **Given** a flag flipped mid-run, **When** admission decisions are audited,
   **Then** every decision matches the flag state observed at decision time,
   deterministically, with a rejection counter carrying the reason.

---

### Edge Cases

- Product with zero publishes (no effective version): backfill writes no rows
  for its engagements; batches still resolve and checkpoint.
- Engagement created after backfill started but before its firm's cursor
  reaches it: enumeration is id-ordered and cursor-bounded; the engagement is
  covered either by backfill (if its id falls in an unprocessed range) or by
  the real-time path; idempotency collapses any overlap.
- Enumeration source fails mid-batch: the batch retries under the existing
  retry rules; the checkpoint never advances past unresolved engagements.
- Corrupt or unreadable checkpoint: the run fails loudly to dead-letter and
  alert — it never silently restarts from an earlier cursor with assumed-clean
  state.
- Halt set while a backfill engagement holds a permit mid-load: the load
  completes, its write resolves, the batch may or may not checkpoint; either
  outcome resumes correctly.
- Halt set while fan-out is mid-chunk: the chunk completes (index-only,
  sub-second); the publish cursor persists; resume completes the publish.
- Two backfill processes accidentally running (duplicate runner): idempotent
  writes collapse them to one row per engagement; the duplicate is wasteful
  but not incorrect; parent-C accounting still holds per process (documented
  weakness — single-runner is the operational assumption).
- B_backfill or B_repair configured so that B_backfill + B_repair > C:
  configuration is rejected at startup; the system never runs with an
  over-partitioned budget.
- B_repair configured to 0: repair-path callers fail fast with a distinct
  counter; backfill and fan-out are unaffected.
- Downstream slower than the halt-drain bound: drain continues past the bound;
  the bound is an alerting target, not a correctness limit; no permits leak.
- Flag disabled for a firm whose backfill batch is mid-flight: the in-flight
  batch finishes and checkpoints; only subsequent admission is gated.
- Same engagement requested by fan-out and backfill concurrently: both write
  through the idempotent index writer; one create, one duplicate skip.
- Any log, metric, dead-letter field, or checkpoint payload anywhere in the
  new paths: identifiers and measurements only — engagement content never
  appears.

## Requirements *(mandatory)*

### Backfill and Control Rules (normative)

These rules are binding; user stories and tests above depend on them.

- **B1 — EngagementEnumerator port**: the system MUST provide an
  EngagementEnumerator port that, given a customer database id, an optional
  resume cursor (engagement id, exclusive lower bound), and a limit, returns a
  batch of engagement references ordered by engagement id ascending plus the
  next cursor (or a done marker). Enumeration MUST be stable and resumable:
  absent concurrent engagement creation, the same (database, cursor, limit)
  yields the same batch; engagements created later with higher ids appear in
  later batches and never invalidate an already-persisted cursor. References
  carry identifiers only (engagement id, firm id, product id) — never
  engagement content. A seeded fake implementation MUST be provided that can
  be programmed to fail on demand (including after k batches) and restarted
  from any cursor, for tests.
- **B2 — BackfillWorker pipeline**: for each enumerated engagement the worker
  MUST, in order: acquire a permit from the backfill share; capture the
  engagement's current template version by calling the downstream
  engagement-load client under that permit; compare it to the product's
  effective version (B7); write or skip the pending-update row through the
  index-writer port; release the permit exactly once (in a finally path).
  In-flight backfill loads MUST NEVER exceed the backfill share.
- **B3 — Fair batch scheduling**: batches are admitted round-robin across
  firms: in each scheduling cycle, every firm with remaining work contributes
  at most one batch, in firm-id order, and firms rotate. A per-firm in-flight
  engagement cap (configurable) bounds how many of a firm's engagements may
  hold permits concurrently; a firm at its cap is skipped for the cycle, not
  queued ahead of others.
- **B4 — Checkpointing**: each firm (per customer database) has one external
  checkpoint recording the highest engagement id through which every engagement
  has resolved — where "resolve" means row written, no-op (already current), or
  dead-lettered. The checkpoint advances only after the batch boundary is
  fully resolved and is written to the existing checkpoint store outside
  process memory. Resume starts at the first incomplete batch; at most one
  batch per firm is re-executed after a crash, and idempotent writes make
  re-execution row-identical.
- **B5 — Disjoint budgets**: the fan-out worker pool and the backfill worker
  pool MUST be disjoint (no shared threads). The downstream capacity C MUST be
  partitioned into a backfill share B_backfill and a real-time repair share
  B_repair with B_backfill + B_repair ≤ C enforced at startup; permit waiters
  on one share MUST NEVER acquire from the other. The publish and withdraw
  hot paths acquire zero permits and make zero downstream calls. Backfill
  pacing is B_backfill: at C = 20, one load per minute, ~800,000 engagements
  take ~27.8 days at B_backfill = 20 and ~30.9 days at B_backfill = 18 — the
  share is the pacing knob.
- **B6 — Halt**: the feature-flags port exposes a global halt switch. Every
  admission point on every path (fan-out chunk admission, backfill batch
  admission, backfill engagement admission, permit acquisition) MUST check the
  halt flag and refuse admission when it is set. Backfill permit waiters MUST
  abandon waits immediately on observing halt — backfill yields first and MUST
  NOT delay the fan-out path's drain. On halt: in-flight work drains under
  normal completion rules, completed-batch checkpoints persist, every acquired
  permit is released, and a halt-drain wall-time bound (default ~2 minutes)
  is measured for alerting. Halt state is never persisted as data; it is read
  live at every admission decision.
- **B7 — Effective-version rule**: the effective version of a product is the
  to-version of the ingested, non-withdrawn publish for that product with the
  greatest (published-at timestamp, publish id) pair — opaque version strings,
  no version arithmetic. A backfilled engagement whose captured current version
  string equals the product's effective version is a no-op; otherwise the
  worker writes one pending-update row offering the effective version through
  the index-writer port (idempotency key and row-version compare-and-set per
  the existing writer contract: identical rewrite = duplicate skip; stale
  version presentation = rejected, stored state unchanged, one re-read and
  re-apply).
- **B8 — Retry, dead-letter, backpressure**: downstream-load failures and
  index-write failures on the backfill path retry with the existing bounded
  exponential backoff and jitter under an injectable clock. On exhaustion the
  engagement is dead-lettered with full work identity (customer database id,
  firm id, product id, engagement id, batch id, attempt count, last failure
  identity), an alert counter increments, and its batch still checkpoints —
  a poisoned engagement never blocks its firm. Dead-lettered engagements are
  replayable and safe to re-execute under B7. When permits are unavailable,
  admission pauses; the engine MUST NOT buffer an unbounded set of
  admitted-but-waiting units — backfill unit admission is gated on permit
  acquisition, not queued behind it.
- **B9 — Staged enablement flags**: the feature-flags port exposes per-product
  and per-firm enablement predicates consulted at every admission decision on
  both the fan-out and backfill paths, in addition to the halt switch (B6).
  Gated-off work is not a failure: it is not admitted, is not dead-lettered,
  and is not buffered — it remains behind its checkpoint and is re-derived
  when the gate opens. Defaults: fan-out gates default enabled (current
  behavior preserved); backfill gates default disabled (backfill must be
  explicitly staged in per firm and per product). Flag state is read live at
  every admission decision; no deploy is ever required to change it.

### Functional Requirements

**Enumeration and backfill core**

- **FR-001**: The system MUST provide the EngagementEnumerator port per B1,
  including a seeded fake that supports programmed failures and restart from
  any cursor.
- **FR-002**: The BackfillWorker MUST implement the per-engagement pipeline per
  B2, with every downstream load acquired under a backfill-share permit and
  released exactly once.
- **FR-003**: Enumeration order MUST be engagement-id ascending per customer
  database, and batch boundaries MUST be stable across restarts.
- **FR-004**: Every firm MUST have exactly one external checkpoint, advanced
  only at fully resolved batch boundaries, per B4.
- **FR-005**: Restarting from persisted checkpoints MUST yield the fault-free
  row set: zero missing rows, zero duplicate creates, at most one re-executed
  batch per firm.
- **FR-006**: An engagement already at the product's effective version MUST
  resolve as a no-op without any failure signal and without stalling its
  batch.
- **FR-007**: A product with no effective version MUST produce zero rows for
  its engagements while batches still resolve and checkpoint.
- **FR-008**: Observed in-flight backfill loads MUST NEVER exceed B_backfill
  at any sample point, and the high-water mark MUST be exported via the
  metrics port.

**Fair enumeration**

- **FR-010**: Batch admission MUST be per-firm round-robin per B3, with a
  configurable per-firm in-flight engagement cap.
- **FR-011**: At any observation point, a firm's in-flight engagements MUST
  NOT exceed its cap, and total backfill in-flight MUST NOT exceed
  B_backfill.
- **FR-012**: The system MUST include a fairness test at the stated
  distribution (one 40,000 firm + 500 median-40 firms) asserting median firms
  complete while the largest firm remains incomplete.

**Budget separation and real-time independence**

- **FR-020**: The fan-out pool and backfill pool MUST be disjoint; a blocked
  backfill pool MUST NOT reduce fan-out pool throughput.
- **FR-021**: The downstream capacity MUST be partitioned per B5; startup MUST
  reject configurations where B_backfill + B_repair > C.
- **FR-022**: Permit waiters on one share MUST NEVER acquire from another
  share; a repair-share waiter MUST acquire while all backfill-share permits
  are held.
- **FR-023**: The publish and withdraw hot paths MUST make zero downstream
  calls and acquire zero permits; every fan-out test MUST assert the fake
  downstream call log is empty.
- **FR-024**: The system MUST include an independence test in which a publish
  at ~20,000-engagement scale completes inside the freshness budget while
  backfill holds every permit of its share.

**Halt**

- **FR-030**: Every admission point on every path MUST observe the halt switch
  and refuse admission within one configured poll interval of the flag being
  set.
- **FR-031**: Backfill permit waiters MUST abandon waits on observing halt,
  releasing nothing they do not hold and holding nothing after abandonment;
  backfill MUST NOT delay the fan-out path's drain.
- **FR-032**: On halt, in-flight work MUST drain under normal completion
  rules, completed-batch checkpoints MUST persist, and all permits MUST be
  released; drain wall-time MUST be measured against the configured
  halt-drain bound.
- **FR-033**: Resume after halt MUST continue from persisted checkpoints with
  the fault-free row set — zero duplicates, zero gaps — for both paths.
- **FR-034**: The system MUST include a halt-latency test asserting zero new
  admissions after the flag is observed at the scheduler-clock granularity
  plus a bounded wall-clock drain.

**Staged enablement**

- **FR-040**: Per-product and per-firm flags MUST gate fan-out chunk admission
  and backfill batch/engagement admission, per B9, read live at every
  admission decision.
- **FR-041**: Flipping a firm's flag off mid-publish MUST stop that firm's
  remaining admission without redeploy while other firms continue unaffected.
- **FR-042**: Re-enabling a flag MUST resume gated work from checkpoints with
  no duplicate and no missing rows.
- **FR-043**: Gated-off work MUST NOT be dead-lettered, dropped, or buffered
  in an unbounded structure; it is re-derived from the cursor on demand.
- **FR-044**: The system MUST include a flag-flip-mid-publish test on each
  path asserting exact-once completion after re-enable.

**Retry, dead-letter, and telemetry**

- **FR-050**: Backfill-path transient failures MUST retry with the existing
  bounded exponential backoff, jitter, and injectable clock; attempt and
  delay bounds are configurable.
- **FR-051**: On exhaustion the engagement MUST be dead-lettered with full
  work identity (identifiers only) and increment an alert counter — never a
  silent drop — and its batch MUST still checkpoint.
- **FR-052**: Dead-lettered engagements MUST be replayable with final state
  equal to first-attempt success under B7.
- **FR-053**: The metrics port MUST expose, as counters/gauges needed by
  tests: batches admitted and completed per path, engagements resolved by
  outcome (created / no-op / dead-lettered), in-flight and high-water permits
  per share, admission rejections by reason (halt, product gate, firm gate,
  permit unavailable), halt observations, and halt-drain wall-time.
- **FR-054**: All identifiers and measurements only — engagement content MUST
  NOT appear in any log, metric, dead-letter field, or checkpoint payload;
  correctness MUST NOT depend on metric values.
- **FR-055**: Tests MUST be written first and MUST be deterministic (seeded
  RNG, latches, controllable clock — no sleep-based synchronization),
  covering: random-kill property (k interruptions → exactly N rows, each
  created once), halt latency and drain, resume-no-duplicates after crash and
  after halt, flag-flip-mid-publish on both paths, real-time-vs-backfill
  independence with backfill saturating its share, repair-share independence,
  fairness at the stated distribution, permit accounting (release exactly
  once, shares never exceeded, startup rejection of over-partition), and
  poisoned-engagement dead-letter with batch checkpoint advance.
- **FR-056**: All tests MUST run inside the project's existing single
  verification command, self-contained, with no external systems or external
  document references.

### Key Entities

- **EngagementRef**: lightweight engagement handle (engagement id, firm id,
  product id); the enumerator's batch element; never carries engagement
  content.
- **BackfillBatch**: one schedulable enumeration slice for one firm — (customer
  database id, firm id, batch index, engagement id range); resolved when every
  engagement in it has resolved; executed at-least-once, safe to re-run.
- **BackfillFirmCursor**: per-firm external checkpoint — highest engagement id
  through which all engagements have resolved (written / no-op /
  dead-lettered); the sole backfill resume truth.
- **CapturedVersion**: the current template version string returned by the
  downstream load for one engagement; opaque; the only load result that may
  cross the boundary — never engagement content.
- **FeatureFlags**: live-read control state — global halt switch, per-product
  enablement predicate, per-firm enablement predicate.
- **CapacityShares**: partition of downstream capacity C into B_backfill and
  B_repair with B_backfill + B_repair ≤ C; each share is an independent
  permit pool with its own waiters, in-flight count, and high-water mark.
- **PendingIndexRow / index-writer contract**: existing rows and writer port,
  unchanged; idempotency key (engagement id, offered version, state) and
  row-version compare-and-set; the backfill's write target.
- **DeadLetterRecord**: existing shape, extended with backfill work identity
  (customer database id, firm id, product id, engagement id, batch id,
  attempt count, last failure identity) — identifiers only.
- **FanOutCursor / triggers**: existing per-publish chunk cursors and
  publish/withdraw triggers from prior phases; unchanged inputs to the gated
  fan-out path.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: In 100% of seeded random-kill property runs (k ∈ [1, 7]
  interruptions at arbitrary points), the post-restart final state has exactly
  N rows for N covered engagements, each created exactly once, byte-identical
  to the fault-free run's row set.
- **SC-002**: In 100% of halt tests, new admissions stop within one configured
  poll interval of the flag being observed, drain completes within the
  configured halt-drain bound (~2 minutes at default load latency), all
  permits are released, and completed-batch checkpoints are persisted.
- **SC-003**: In 100% of resume tests (after crash and after halt), the final
  row set equals the never-interrupted run: zero missing rows, zero duplicate
  creates, at most one re-executed batch per firm.
- **SC-004**: In 100% of independence runs, a ~20,000-engagement publish
  completes inside the freshness budget while backfill holds every permit of
  its share; the publish acquires zero permits and makes zero downstream
  calls; fan-out throughput is unchanged from a backfill-free baseline.
- **SC-005**: In 100% of permit-accounting observations: backfill in-flight ≤
  B_backfill, repair in-flight ≤ B_repair, total ≤ C; every acquired permit
  is released exactly once; over-partitioned configurations are rejected at
  startup.
- **SC-006**: In the fairness test at the stated distribution, 100% of
  median firms complete while the largest firm is incomplete, and no firm
  exceeds its in-flight engagement cap at any observation point.
- **SC-007**: In 100% of flag-flip tests, post-flip admissions for the gated
  firm or product drop to zero within one admission check, other tenants are
  unaffected, and post-re-enable completion is exact-once.
- **SC-008**: Full-backfill pacing holds the documented arithmetic: with
  one-minute loads and B_backfill = 20 the projected wall time for ~800,000
  loads is ~27.8 days, and observed in-flight never exceeds B_backfill; the
  projection scales inversely with the share (the pacing knob).
- **SC-009**: 100% of poisoned-engagement tests show dead-letter with full
  identifier-only work identity, an incremented alert counter, batch
  checkpoint advance, and converged state after replay.
- **SC-010**: All delivered artifacts pass a self-containment review: zero
  references to external documents, zero engagement content in any log,
  metric, dead-letter field, or checkpoint payload across the suite, and the
  whole suite runs under the single existing verification command with no
  sleep-based synchronization.

## Assumptions

- One customer database per firm; "enumerate per customer database" and
  "round-robin across firms" are the same operation (firm id ↔ customer
  database id 1:1).
- B_backfill defaults to 18 and B_repair to 2 under C = 20 — a deliberate
  reserve so a future real-time repair path never waits behind 27.8 days of
  backlog; both are configurable, and B_backfill = 20 / B_repair = 0 restores
  the full-budget ~27.8-day pace when no repair caller exists.
- Backfill batch size defaults to 100 engagements and the per-firm in-flight
  engagement cap defaults to 8; both configurable; tests may set smaller
  values to expose invariants.
- Poll interval for flag observation defaults to 1 second; halt-drain bound
  defaults to 2 minutes (2× the one-minute load latency) — both configurable.
- Fan-out gates default to enabled so existing live behavior is preserved;
  backfill gates default to disabled and must be staged in explicitly.
- The downstream load result is a template version string only; the worker
  never sees engagement content, so "capture the version" requires no content
  handling, matching the confidentiality rules.
- A backfilled pending-update row offers the product's current effective
  version (one row per engagement); offering every historical missed version
  as separate rows is not required — accumulated updates resolve to a single
  effective offer.
- Checkpoints use the existing external checkpoint store; no new storage
  system is introduced.
- Single backfill runner is the operational assumption; duplicate runners are
  wasteful but row-safe under idempotent writes (documented weakness).
- Retry, backoff, jitter, dead-letter, and index-writer row-version semantics
  are the existing contracts from prior phases, restated here in condensed
  form (B7, B8) so this specification is self-contained.
- "Within minutes" for halt is interpreted as: admission stops within one poll
  second of observation, and full drain completes within the halt-drain bound
  (~2 minutes at defaults).
- Real adapters (real enumerator against customer databases, real feature
  flag source, real checkpoint store wiring) are later phases; this phase
  delivers ports, the worker, the control logic, and fakes.
- Cost: backfill compute is permit-bound (≤ 20 concurrent one-minute calls);
  storage cost is one pending row per covered engagement (~800,000 rows
  maximum) through the existing index writer — no new cost centers.

## Out of Scope

The following are explicitly excluded from this phase and MUST NOT be built now:

- Real infrastructure adapters (real engagement enumerator, real feature-flag
  source, real downstream client wiring beyond the existing fake)
- Observability beyond the counters, gauges, and one drain-time measurement
  the tests need (FR-053)
- Alert routing destinations, dashboards, and operator UI
- Dead-letter replay tooling and pruning
- A reconciliation/repair scanner (only the B_repair permit-share invariant
  for a future such caller is specified and tested)
- Applying template content to engagement files
- Offering historical missed versions as separate pending rows (single
  effective offer per engagement, per B7)
- Multi-runner backfill coordination (single runner assumed; idempotency is
  the safety net)
- Maintenance-window or coordinated-shutdown mechanisms beyond the halt switch
- Engagement content handling of any kind, in code paths, logs, or metrics
