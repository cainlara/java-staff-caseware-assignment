# Phase 0 Research: Capacity-Aware Fan-Out Engine

**Feature**: 003-capacity-aware-fanout | **Date**: 2026-10-18

All Technical Context fields resolved from the constitution, the feature spec,
and the phase-1/2 codebase — **zero NEEDS CLARIFICATION items remain**.
Decisions below are recorded with rationale and alternatives, plus the
constitution Principle VIII disclosures.

## Decisions

### D1. FanOutEngine shape and entry points

- **Decision**: `FanOutEngine` is a plain final class with constructor-injected
  ports (`EngagementEligibilitySource`, `IndexWriter`, `CheckpointStore`,
  `FanOutMetrics`, `FanOutConfig`) — no Spring wiring yet (phase-1/2
  precedent: framework-neutral, tests plain JUnit). One typed entry point per
  trigger kind: `FanOutRunOutcome runPublish(FanOutTrigger trigger)` and
  `FanOutRunOutcome runWithdrawSupersede(FanOutTrigger trigger)`; both are
  idempotent on `(kind, eventId, productId)`. `FanOutRunOutcome` is a record
  `(status: COMPLETED | SKIPPED_DUPLICATE | SKIPPED_IN_PROGRESS | COMPLETED_EMPTY
  | FAILED_DEAD_LETTERED, eventId, chunkCount, rowsResolved, deadLetterCount)`.
  The engine does NOT depend on `DownstreamLoadClient` or `CapacityBudget` at
  all — the hot path cannot call what it cannot see (spec FR-032 enforced by
  construction, plus the per-test call-log assertion).
- **Rationale**: Spec FR-002 fixes the trigger-to-chunk-to-pool flow; typed
  entry points keep the domain trigger record as the only input
  representation. Engine-side dependency absence is the strongest form of the
  hot-path prohibition; the permit-gated downstream wrapper (D9) is a separate
  class reachable only by future off-path callers. An outcome record makes
  every path (including duplicate-skip and dead-letter) assertable without
  inspecting internals.
- **Alternatives considered**: engine takes `DownstreamLoadClient` +
  `CapacityBudget` "for later" — widens the hot-path blast radius and invites
  accidental calls, rejected; single `run(Object)` with type switch — loses
  compile-time safety at two trigger kinds; Spring `@EventListener` consumer —
  premature framework coupling; consuming via `FanOutTriggerSink` interface —
  the sink is the emit side (phase 2); the engine is a plain consumer class a
  future router/adaptor will call.

### D2. Eligibility: single ordered materialization, positional chunks

- **Decision**: On run start the engine drains the eligibility stream into one
  ordered `List<EngagementRef>` (metadata-only handles; spec R1 ordering:
  firm id ascending, engagement id ascending within firm), then partitions
  positionally into chunks of `FanOutConfig.chunkSize` (default 500).
  Chunk `i` is the positional slice `[i*size, min((i+1)*size, n))` — indices
  are stable across runs given the deterministic source ordering. The total
  chunk count is written into the cursor at run claim time (D3).
- **Rationale**: Materializing ≤ ~40,000 lightweight refs costs ~8 MB
  transiently (D10) and buys three hard requirements cheaply: (a) positional
  chunk indices that survive crash/resume without re-scanning the stream
  (spec FR-003/FR-051), (b) total-chunk count known up front for the cursor
  and progress metrics, (c) deterministic fairness seeding (D4) over a stable
  firm->chunks grouping. A strict stream-while-writing design would need a
  two-pass (count, then write) or offset-based resume against a mutating
  source — strictly more failure modes for no memory win at this scale.
- **Alternatives considered**: streaming write with byte-offset cursor —
  resume depends on source re-iteration stability, cannot precompute chunk
  count, harder fairness grouping; two-pass stream (count then write) — two
  full source reads per run, still needs deterministic re-iteration; loading
  engagement data per ref — forbidden (spec R1, content never loaded).

### D3. Cursor: new `FANOUT_CURSOR` kind, claim-by-writeIfAbsent

- **Decision**: New `CheckpointKind.FANOUT_CURSOR`. Key id =
  `kind US eventId US productId` (US = U+001F, phase-2 delimiter). Value
  schema: `RUNNING US totalChunks US lastCompletedChunkIndex` or
  `COMPLETE US totalChunks US lastCompletedChunkIndex` (decimal ints;
  `lastCompletedChunkIndex` = -1 when none). Run claim uses the store's atomic
  `writeIfAbsent` with the RUNNING value: `false` return means an existing
  cursor — if `COMPLETE`, the run is `SKIPPED_DUPLICATE` (spec: replay
  converges, no re-processing); if `RUNNING`, the run is
  `SKIPPED_IN_PROGRESS` (another live run or a crash-resume case handled by
  the restart path below). Crash-resume: a restarted process sees RUNNING and
  re-enters the run, continuing from `lastCompletedChunkIndex + 1`; a cursor
  claiming an unknown/corrupt total fails loudly to dead-letter/alert (spec
  Edge Case). Chunk completion advances the cursor by rewriting the value
  (last-write-wins per phase-1 `CheckpointStore` contract; single writer per
  run is guaranteed by the claim). Completion rewrites to COMPLETE.
- **Rationale**: The phase-2 `writeIfAbsent` claim pattern (002 research D2)
  is the proven first-wins primitive and already atomic in
  `InMemoryCheckpointStore`. One key per run keyed on the trigger identity
  makes duplicate triggers (at-least-once bus) and duplicate chunk executions
  (spec FR-053) converge by construction. Delimiter schema stays consistent
  with phase-2 `IngestProgressRepository` conventions (US/LF, delimiter-free
  natural keys).
- **Alternatives considered**: separate claim key + progress key (two writes,
  wider crash window, phase-2 rejected the same); cursor per chunk
  (write amplification, no read-path need); in-process cursor with
  checkpoint-only completion — fails crash-resume mid-publish (FR-051);
  storing the full eligibility list in the checkpoint value — store bloat,
  content-adjacent risk for no benefit (source re-read is cheap and
  deterministic).

### D4. Fair sharding: per-firm deque + round-robin cursor + firm semaphore

- **Decision**: `FairShardScheduler` holds, per run: `Map<firmId,
  Deque<FanOutTask>>` built from the materialized list (D2), a
  `TreeSet<firmId>` of firms with pending chunks (ascending order), a cursor
  into that set, and one `Semaphore(firmCap)` per firm (default 2). The
  scheduling loop, while work remains: starting at the cursor, scan firms in
  ascending order for the first firm that (a) has a pending chunk and (b)
  acquires its firm permit; submit that chunk to the bounded task runner,
  advance the cursor past that firm (wrap-around); firms at their cap are
  skipped this cycle, not queued ahead of others (spec R3). Total in-flight
  chunks are bounded by the task runner's fixed bound (default 16); a chunk
  releases its firm permit and returns its runner slot on completion
  (success, dead-letter, or abandonment). Chunks of one firm are executed in
  index order (deque FIFO).
- **Rationale**: This is the minimal structure satisfying spec FR-010/FR-011:
  round-robin over firms interleaves the 40,000-engagement firm with
  median-40 firms; the per-firm semaphore caps the giant firm at 2 in-flight
  chunks regardless of its backlog; the `TreeSet` cursor gives deterministic,
  starvation-free scan order for tests (no randomness, no wall-clock).
  Semaphore counting is the same proven primitive as phase-1
  `FakeCapacityBudget`.
- **Alternatives considered**: weighted fair queuing by firm size — needs
  size-aware math, overkill for "cap + interleave" (spec asks cap + RR, not
  proportional share); global queue with per-firm tags — a 100-chunk backlog
  from the giant firm still occupies queue position ahead of small firms
  unless re-sorted every poll; virtual-thread-per-chunk with semaphores —
  fine, but a fixed platform-thread runner with an explicit bound is simpler
  to make deterministic in tests (D5); static partitioning of the pool per
  firm — thousands of firms make partitions meaningless.

### D5. Deterministic concurrency: injected runner, clock, jitter, waiter

- **Decision**: Three small seams, all constructor-injected via `FanOutConfig`
  holders or direct parameters, with production defaults:
  - **Task runner**: interface `TaskRunner { void execute(Runnable task); }
    ` plus `int bound()`. Production: fixed platform-thread executor of
    `poolBound` threads. Tests: `TestTaskRunner` — a test-controlled runner
    that either runs tasks inline on the caller thread or hands them to the
    test, exposing latches for "all in-flight" observation. The engine's
    scheduling loop waits for completion signals (latch/counter), never sleeps.
  - **Clock + waiter**: interface `BackoffWaiter { void await(Duration
    delay); }` reading time from an injected `java.time.Clock`. Production:
    `Thread.sleep`-based waiter. Tests: `FakeClock` (manual advance) +
    an immediate waiter that advances the fake clock instead of sleeping —
    retry schedules become fully deterministic (spec FR-042, SC-006).
  - **Jitter**: interface `JitterSource { double next(); }` returning a value
    in [0, 1). Production: `Random`. Tests: `SeededJitter` (fixed seed or
    scripted sequence) so every delay is reproducible and assertions check
    the [0.5, 1.5) multiplier bounds exactly.
  - **Downstream latency in tests**: a latch-blocking fake
    (`LatchDownstreamClient`) replaces phase-1's sleep-simulating fake for
    concurrency tests: in-flight count is an exact integer observed under
    latches, never estimated from timing (40k-simulation, C-limit).
- **Rationale**: Spec FR-072 mandates latches and fake clocks with no
  sleep-based sync; the seams above remove every source of timing
  non-determinism (thread scheduling remains, but all invariants are asserted
  on counters/latches, not on timing). Production behavior is unchanged
  (sleeping waiter, random jitter, real executor).
- **Alternatives considered**: virtual-time executors /Awaitility — new test
  dependencies, rejected under self-containment/minimalism; sleeping tests —
  spec-forbidden; `if (testMode)` flags in production code — rejected (phase-2
  D7 precedent).

### D6. CAS re-apply cycle needs a read: additive `find` on `IndexWriter`

- **Decision**: Add `Optional<PendingIndexRow> find(String engagementId,
  String offeredVersion, PendingIndexState state)` to the phase-1
  `IndexWriter` port (additive, no default — every implementation in the repo
  is ours: `InMemoryIndexWriter` implements it by map lookup). Engine write
  path per row: build the intended row presenting the expected `rowVersion`
  (1 for a fresh idempotency triple; the read version for updates); upsert;
  on `REJECTED_STALE`, `find` the current row, recompute the intended content
  against the fresh version (merge: `publishIds` union, newest `updatedAt`,
  winner's `state`/`decision`/`pinnedRanges` per supersede rules), re-upsert
  once; a second `REJECTED_STALE` is a transient failure under R6 (retry →
  DLQ on exhaustion).
- **Rationale**: Spec R4/FR-021 mandate re-read-and-reapply on stale
  rejection, but the phase-1 port is write-only — a read method is the
  minimal, additive way to honor the spec. Keying `find` on the port's own
  idempotency triple keeps the contract coherent (the triple IS the row's
  identity). Re-apply-once-then-retry bounds the busy loop and keeps
  concurrent duplicate chunk executions deterministic (exactly one writer
  advances each version step).
- **Alternatives considered**: treat `REJECTED_STALE` as success ("someone
  else wrote it") — wrong: the stored row may carry different content (e.g.
  one execution superseding, one publishing) and the spec requires convergence
  on the deterministic merge, not silent abandonment; new `IndexReader` port —
  splits one row identity across two ports for no isolation benefit;
  version-blind blind overwrite — breaks the phase-1 CAS contract and the
  deterministic-writer requirement (spec FR-021).

### D7. Withdrawn supersede: engine-owned version marker + eligibility source listing

- **Decision**:
  - `EngagementEligibilitySource` gains a second method:
    `Stream<EngagementRef> streamOffering(String productId, String version)` —
    rows currently offering that version, same deterministic ordering. Both
    methods stream index metadata only (spec FR-001).
  - On `runWithdrawSupersede` start, the engine atomically records the
    withdrawn marker: `writeIfAbsent(CheckpointKey(PRODUCT_WITHDRAWN_VERSION,
    productId), version)` appended into a per-product LF-joined version list
    (idempotent append under the repository's synchronized read-modify-write,
    same pattern as `PRODUCT_PUBLISHES`). The marker is read when selecting
    the next eligible version and is durable across crashes.
  - Next eligible version selection (spec R7): load the product's ingested
    publishes (`PRODUCT_PUBLISHES` -> `PUBLISH` records, via a thin read
    adapter reusing phase-2 decoding), filter to non-withdrawn versions
    (marker list) and to publishes strictly newer than the withdrawn
    version's publish under the phase-2 R4 ordering; take the max
    `(publishedAt, publishId)` of the remainder; none => target state
    `CREATED`.
  - Each superseding row write keeps `decision`, `pinnedRanges`,
    `summaryKey` from the stored row (history preserved byte-for-byte) and
    changes only `offeredVersion` (next version) / `state`, adds the
    supersede publish id to `publishIds`, bumps `updatedAt`. A `CREATED`
    target likewise preserves decision fields.
- **Rationale**: Phase 2 stores withdraw records keyed by withdraw id — there
  is no per-product withdrawn-version index to read, and scanning all withdraw
  ids is unbounded. The engine-owned marker (durable, idempotent, written
  before supersede work) is the smallest self-contained addition; writing it
  in the fan-out layer rather than retroactively changing ingest keeps phase-2
  code untouched (Constitution VII). `streamOffering` on the same port keeps
  "what the index metadata knows" in one owner. Preserving decision fields in
  the recomputed row is what makes spec FR-061 (`history preserved`) hold
  under both fresh writes and CAS re-apply merges (D6).
- **Alternatives considered**: deriving withdrawn set by scanning all
  `WITHDRAW` records — no enumeration key exists in phase 2, O(all withdraws);
  adding the marker to `IngestService` — touches frozen phase-2 behavior and
  its tests for a fan-out concern; keying supersede on the withdraw trigger's
  `matchedPublishId` only — does not identify the withdrawn *version* on all
  paths (republish case) and cannot answer "is version W withdrawn" for the
  next-version filter; separate `WithdrawnVersionTracker` port — checkpoint
  store already is the durable shared store; a new port adds an impl for zero
  new semantics.

### D8. Retry policy and dead-letter record

- **Decision**: `RetryPolicy` record inside `FanOutConfig`: `maxAttempts`
  (default 5), `baseDelay` (default 100 ms), `maxDelay` (default 30 s),
  `jitterFactorMin` (0.5), `jitterFactorMax` (1.5). `RetryExecutor` wraps a
  `Supplier<T>`: attempt, on `TransientFanOutException` (new unchecked type;
  wraps writer/downstream/index errors) sleep `min(maxDelay, base *
  2^(attempt-1)) * jitter` via the waiter, until exhaustion throws
  `FanOutExhaustedException` carrying the `DeadLetterRecord`. The engine (or
  permit guard) catches exhaustion, stores via `DeadLetterStore`, increments
  `fanout.dlq` alert counter, and continues other work — never drops.
  `DeadLetterRecord` (domain record): `eventId`, `productId`, `chunkIndex`
  (-1 for non-chunk units), `unitKind` (enum
  `CHUNK_WRITE | ELIGIBILITY_STREAM | DOWNSTREAM_CALL | CURSOR_WRITE |
  SUPERSEDE_PLAN`), `attemptCount`, `lastFailureType`, `lastFailureMessage`
  (exception class + message, never engagement content), `recordedAt`.
  `DeadLetterStore` port: `void store(DeadLetterRecord)` +
  `List<DeadLetterRecord> byEvent(String eventId)` (test/replay reads).
  Fake: `FakeDeadLetterStore` (synchronized list).
- **Rationale**: Spec R6 fixes the shape (bounded exponential + jitter,
  exhaustion => DLQ + alert metric, replayable). Splitting unit-kind on the
  record makes the replay tool (later phase) and the DLQ tests address the
  exact unit. Storing the exception message is safe here because failure
  messages come from ports that carry identifiers only (phase-1 contracts);
  the domain record validation rejects blank identities.
- **Alternatives considered**: Spring Retry / Resilience4j — new runtime
  dependencies for ~30 lines of policy, rejected; DLQ as another checkpoint
  kind — checkpoints are progress cursors, DLQ is an append-only forensic
  log, different access pattern (`byEvent`); dropping with only a metric —
  spec forbids silent drops and requires replayable identity; throwing to the
  caller — a dead-lettered chunk must not fail the whole publish run (other
  firms still need progress; alert, don't cascade).

### D9. Permit gate: `PermitGuardedDownstream` wrapper (off-path only)

- **Decision**: New class in `fanout`: `PermitGuardedDownstream` wrapping
  (`DownstreamLoadClient`, `CapacityBudget`, `BackoffWaiter`, metrics). Call:
  loop `tryAcquire()`; on failure increment `fanout.backpressure` and await
  via waiter (paused admission — no buffering); on acquire, invoke the client
  inside try/finally `release()`; transient client failures rethrow as
  `TransientFanOutException` for `RetryExecutor` (permit released before the
  retry sleep — never held across backoff); tracks its own in-flight counter
  and records `fanout.downstream.inflight.hwm` when a new peak is reached
  (the metrics port rejects negative deltas, so the HWM is an event-style
  counter, not a gauge). Hot path: the class is never constructed inside
  `FanOutEngine` (D1) — future off-path callers (reconciliation, revalidation)
  construct it; a contract test drives it directly to prove the C-limit and
  backpressure invariants (spec FR-030/FR-031/FR-033).
- **Rationale**: Spec keeps off-path repair in scope only as a rate-limit
  invariant; a wrapper class + direct contract test delivers the invariant
  without building any repair job (out of scope). Releasing the permit before
  backoff sleeping is what "admission pauses, not buffer" means at the
  permit level: C is reserved only for actual in-flight calls.
- **Alternatives considered**: permit logic inside `DownstreamLoadClient`
  fake — contract says the client does not self-limit (phase 1); permit logic
  inside the engine — engine must not see the client at all (D1); a
  rate-limiter token bucket — spec asks a concurrency ceiling, not a rate;
  blocking `acquire()` — hides backpressure pauses from the
  `fanout.backpressure` metric and can deadlock a pool thread holding a
  chunk slot.

### D10. Cost arithmetic (Constitution IV)

- **Decision**: Per typical publish (~20,000 eligible engagements):
  - **Memory**: 20,000 `EngagementRef` records ≈ 5 fields × ~40 B + record
    header ≈ ~250 B each ≈ **5 MB** transient per run (40,000-engagement
    worst firm ≈ 10 MB). Pool of 16 chunk slots adds ≤ 16 × 500 refs worth of
    working set — refs already counted in the materialized list.
  - **Compute**: ~20,000 index upserts (in-memory map ops today; DB ops later,
    batched by chunk of 500). Scheduler overhead: ~40 chunks × firm-scan
    (≤ firms-with-work probes per cycle) ≈ tens of thousands of cheap
    deque/semaphore ops. No engagement loads, no content processing.
    Wall clock at test scale: well under a second; at production DB speeds
    (say 1 ms/write, 16-way) ≈ 20,000/16 ≈ 1.25 s of write time — far inside
    the 60 s p99 budget.
  - **Downstream**: **0 calls** on publish/withdraw paths (spec invariant);
    off-path future use capped at C=20 in flight × 60 s/call ⇒ ~20
    engagements/minute worst case — the reason the hot path must never touch
    it.
  - **Storage**: index rows ≤ 800,000 metadata rows × ~500 B ≈ **400 MB**
    ceiling (one row per engagement per offered version triple); cursors: 1
    small value per run (~40 publishes + withdraws/week ⇒ negligible);
    dead-letter records: append-only, only on exhaustion.
  - **Monthly**: one small worker instance + the shared stores; ~40
    publishes/week × (5 MB transient + 20k writes) is noise. **Well under
    USD 5,000/month; zero inference/LLM spend** in this feature.
- **Rationale**: Principle IV demands stated arithmetic against the real
  numbers; the table above uses the spec's scale (800k/40/4,000/40/40k).
- **Alternatives considered**: streaming without materialization would save
  ~5–10 MB and cost resume complexity (D2) — rejected; larger chunks (5,000)
  would cut cursor writes but grow per-chunk retry blast radius and fairness
  quanta — 500 balances both.

### D11. Metric names and tagging

- **Decision**: Counter names (dot-separated, identifier-only):
  `fanout.chunks.started`, `fanout.chunks.completed` (tags:
  `product_id`), `fanout.rows.resolved` (delta = rows in a chunk),
  `fanout.retry` (transient failure retried), `fanout.dlq` (**alert
  metric**, mandatory — FR-041; tags `product_id`, `unit_kind`),
  `fanout.backpressure` (permit acquisition paused; tag `product_id`),
  `fanout.duplicate.run` (cursor claim said skip), `fanout.cas.reapply`
  (stale-rejection re-apply), `fanout.downstream.inflight.hwm` (peak
  in-flight, off-path wrapper only). Timer: `fanout.publish_to_pending` from
  trigger receipt to cursor COMPLETE (tags `product_id`). No metric carries
  engagement ids at fan-out volume (cardinality discipline, Constitution V/VI);
  engagement-level identity lives in cursors/dead-letters/checkpoints only.
- **Rationale**: Spec FR-071 mandates progress, retry, DLQ, backpressure,
  HWM, and the freshness timer. Bounded tags (≤ 40 products, 4 unit kinds)
  keep cardinality small. Correctness never depends on metrics (phase-1 rule).
- **Alternatives considered**: per-engagement-id tags — 20k× cardinality per
  publish, useless and expensive; gauge API on `FanOutMetrics` — port has no
  gauge and rejects negative deltas, so HWM-as-counter is the additive path.

### D12. Large-publish (40k) simulation strategy

- **Decision**: `FakeEligibilitySource` generates refs programmatically: one
  firm with 40,000 refs + 500 firms × 40 refs (spec distribution), zero
  content. `LatchDownstreamClient` blocks each call on a per-call latch and
  tracks exact in-flight counts (max observed exposed as a field). The
  simulation test (a) drives a publish run to completion asserting 41,000
  rows resolved exactly once and cursor COMPLETE, (b) drives the permit-guard
  path with 400 off-path calls asserting observed max in-flight ≤ C at every
  observation point (checked continuously via an atomic max-tracker inside
  the fake), and (c) asserts fairness mid-run: while the giant firm still has
  pending chunks, median firms have completed (sampled at a latch point).
  Runtime stays seconds: all sleeps are zero; the only waiting is latches.
- **Rationale**: Spec FR-072/SC-002/SC-005 mandate the scale test and the
  invariants; generating refs keeps the test self-contained (no fixtures);
  the atomic max-tracker inside the fake makes "never exceeded C" an exact
  assertion, not a sampled hope.
- **Alternatives considered**: real 800k-engagement universe — slows `mvn
  verify` for no additional invariant; probabilistic sampling of in-flight
  counts — weaker than the exact tracker; timing-based latency simulation —
  non-deterministic (phase-1 fake's `Thread.sleep` is deliberately not used
  here).

## Carried-Forward Obligations (not this phase, tracked honestly)

1. **Trigger-to-engine wiring**: a consumer/router subscribing
   `FanOutTriggerSink` emissions to `FanOutEngine` (trivial; tests call the
   engine directly). Bus acknowledgment stays with the real bus adapter.
2. **Real adapters**: real eligibility source (index-metadata query), real
   checkpoint/index/dead-letter stores — contracts now pin their semantics
   (`find`, cursor claim atomicity, `byEvent` reads).
3. **Dead-letter replay tool + alert routing**: store port, record, and alert
   counter are delivered; the replay CLI/job and destination (paging system)
   are later phases.
4. **Off-path repair/reconciliation jobs**: only the permit-gate invariant is
   delivered (D9); the jobs themselves are out of scope per spec.
5. **Checkpoint durability across real process death**: still the in-memory
   placeholder; the cursor contract inherits the phase-2 `writeIfAbsent`
   atomicity requirement for real adapters.
6. **Stalled-run detection / cursor pruning**: cursors are retained per run;
   detecting a RUNNING cursor older than a threshold and alerting is a later
   observability phase (Constitution VI alerting on stalled fan-out).
7. **Feature-flag staged rollout**: out of scope per spec; `FeatureFlags`
   port exists unused by the engine.

## Principle VIII Disclosures

- **Riskiest element**: the interaction of cursor advancement with concurrent
  chunk completions under crash. Order per chunk is: resolve every row write
  (each independently idempotent) → advance cursor. A crash between those
  steps re-runs the chunk after restart; every write resolves as
  SKIPPED_DUPLICATE or is absorbed by the D6 re-apply merge, so the row set
  converges (spec FR-052). The sub-window where two *different* executions of
  the same chunk (duplicate trigger + resume) interleave writes is handled by
  CAS determinism (D6); it is tested, but it is the densest correctness
  surface in the feature.
- **Deliberately left out**: Spring bean wiring and scheduling, trigger
  router, real adapters, backfill, feature flags, dead-letter replay tooling,
  alert routing, cursor GC, off-path repair jobs, UI, applying template
  content (spec out-of-scope list).
- **The one requirement challenged**: **spec R4/FR-021 "the engine re-reads
  the current row"** presumes a read capability that the phase-1 `IndexWriter`
  port does not have. This plan honors the spec by adding the additive `find`
  method (D6) rather than reinterpreting the requirement as
  "stale-rejection = success" — the latter would violate the spec's
  deterministic-convergence wording. Cost: every `IndexWriter` implementation
  gains one method (only ours exist; in-memory fake implements it in a few
  lines).
- **Assumptions and impact if wrong**:
  - *Deterministic eligibility ordering (R1) holds across resume* — if a real
    adapter's stream order drifts between the original run and the resume,
    positional chunks resume misaligned (gap/duplicate at the index level).
    Mitigation: the contract mandates the ordering; a real adapter must sort
    (or the cursor schema can be extended later with a chunk-boundary
    fingerprint — deliberately deferred).
  - *Delimiter-free natural ids* (inherited from phase 2) — a firm/engagement
    id containing US corrupts cursor/marker schemas; UUID-like ids make this
    safe; a real adapter should move to structured encoding.
  - *Materializing ≤ 40k refs is cheap* (D2/D10) — if eligibility metadata
    per ref grows beyond ~1 KB, memory pressure rises; the contract keeps
    `EngagementRef` metadata-only, which bounds it.
  - *Engine-owned withdrawn markers* (D7) — if a withdraw trigger is lost
    before the engine runs (phase-2 checkpoint-to-trigger window), the marker
    is never written and supersede never happens for that version; the
    phase-2 reconciler obligation (002 research) is the repair path; the
    marker is written before supersede work so a crash mid-supersede resumes
    with the marker already durable.
  - *Single live runner per cursor claim* — two instances racing the same
    trigger produce one claim winner (writeIfAbsent); the loser skips; if ops
    ever need active-active runs of one publish, the cursor needs a fencing
    token — not required by current scale/ops model.
