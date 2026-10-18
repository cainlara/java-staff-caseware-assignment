# Phase 0 Research: Idempotent Event Ingestion & External Checkpointing

**Feature**: 002-idempotent-event-ingestion | **Date**: 2026-10-18

All Technical Context fields resolved from the constitution, the feature spec,
and the phase-1 codebase — **zero NEEDS CLARIFICATION items remain**. Decisions
below are recorded with rationale and alternatives, plus the constitution
Principle VIII disclosures.

## Decisions

### D1. IngestService shape and outcome model

- **Decision**: `IngestService` is a plain final class with constructor-injected
  ports (`IngestProgressRepository`, `FanOutTriggerSink`, `FanOutMetrics`) —
  no Spring wiring yet (phase-1 precedent: framework-neutral, tests plain JUnit).
  Two typed entry points: `IngestOutcome ingest(TemplatePublished)` and
  `IngestOutcome ingest(TemplateWithdrawn)`. `IngestOutcome` is a record
  `(IngestStatus status, String eventId, List<String> resolvedWithdrawIds)`
  with `IngestStatus { INGESTED, DUPLICATE, DEFERRED, REJECTED }`;
  `resolvedWithdrawIds` is non-empty only when ingesting a publish that resolved
  deferred withdraws.
- **Rationale**: Spec FR-001 fixes the order validate -> checkpoint -> trigger;
  typed overloads keep the domain records as the only event representation
  (they already enforce natural-key validation at construction, FR-004).
  An outcome record makes every path (including DEFERRED, which emits no
  trigger) assertable without inspecting internals. Rejected status exists for
  the defensive null-argument path and future raw adapters.
- **Alternatives considered**: single `ingest(Object)` with runtime type switch
  — loses compile-time safety, no benefit at two event types; returning bare
  enum — loses the resolved-withdraw linkage tests need; throwing on duplicates
  — spec requires duplicates to ack without error.

### D2. Atomic duplicate claim: `writeIfAbsent` on CheckpointStore

- **Decision**: Add `default boolean writeIfAbsent(CheckpointKey key, String
  value)` to the phase-1 `CheckpointStore` port: returns `true` if the value
  was written (key was absent), `false` if a value already existed. Default
  implementation is non-atomic `read`-then-`write` (preserves any existing
  third-party impl). `InMemoryCheckpointStore` overrides it atomically via
  `ConcurrentHashMap.putIfAbsent`. `IngestService` claims an event id with
  `writeIfAbsent` **before** any further ingest side effects (claim = the
  processed record itself; a single write, not two). A `false` return is the
  duplicate path (FR-003).
- **Rationale**: The phase-1 port is last-write-wins read/write only — a naive
  read-check-then-write duplicate test races under concurrency and cannot
  satisfy FR-023 (combined idempotency across instances sharing one store).
  A default method is backward compatible (Constitution VII: expand, never
  break); the atomic override gives the in-memory fake real claim semantics,
  and a future DB adapter maps it to `INSERT` (unique constraint) — the
  contract now expresses the atomicity production needs.
- **Alternatives considered**: synchronize inside `IngestService` — only guards
  one JVM instance, fails FR-023's shared-store scenario; separate
  "claim" key + metadata key (two writes) — widens the crash window for no
  gain, doubles store traffic; CAS/version token on values — store has no
  version concept, would break the phase-1 value type (`String`).

### D3. Checkpoint value schemas and kind extensions

- **Decision**: Extend `CheckpointKind` additively with four constants and
  define delimiter-joined string schemas (no new dependencies; store values
  stay `String` per phase-1 contract). Separator is the ASCII unit separator
  (U+001F, written `US` below); list separator is newline (LF). Natural keys
  are assumed delimiter-free (see disclosures): UUID-like tokens contain
  neither US nor LF.

  | Kind | Key `id` | Value schema |
  |------|----------|--------------|
  | `PUBLISH` (phase 1) | publishId | `productId US toVersion US publishedAtEpochMilli` |
  | `WITHDRAW` (new) | withdrawId | `productId US version US status (DEFERRED or RESOLVED) US matchedPublishId-or-empty US withdrawnAtEpochMilli` |
  | `PRODUCT_INGEST` (new) | productId | next per-product ingest sequence (decimal long) |
  | `PENDING_WITHDRAW` (new) | `productId US version` | LF-joined deferred withdraw ids for that (product, version) |
  | `PRODUCT_PUBLISHES` (new) | productId | LF-joined ingest-ordered publish ids for that product |

- **Rationale**: `PUBLISH`/`WITHDRAW` claim-and-record in one write (D2).
  `PRODUCT_INGEST` is the read-modify-write sequence counter (observational,
  per spec Assumptions). `PENDING_WITHDRAW` is the resolution index: when a
  publish (P, V) is ingested, the service reads this key, resolves every listed
  withdraw, then clears the key — avoids O(all withdraws) scans (FR-011
  "every deferred withdraw"). `PRODUCT_PUBLISHES` retains all publishes per
  product in ingest order (FR-013) and makes rule-R4 effective-version
  computable for the permutation test without a full-store scan.
- **Alternatives considered**: JSON values (Jackson) — new runtime dependency
  for five flat schemas, rejected under self-containment/minimalism; one
  "state document" per product rewritten on every event — write amplification
  and a hot-key race for no read-path need this phase; typed repository
  returning domain objects hides schemas in one place (done — see D4).

### D4. IngestProgressRepository: typed adapter over CheckpointStore

- **Decision**: `IngestProgressRepository` (package `ingest`) is a final class
  wrapping `CheckpointStore`, exposing typed operations used only by
  `IngestService`: `claimPublish(...)`, `claimWithdraw(...)`,
  `nextProductSequence(productId)`, `addProductPublish(productId, publishId)`,
  `deferWithdraw(productId, version, withdrawId)`,
  `takePendingWithdraws(productId, version)`, `resolveWithdraw(...)`,
  `productPublishes(productId)`, `withdraw(withId)`, `publish(withId)`.
  All encoding/decoding of D3 schemas lives here, nowhere else.
- **Rationale**: Keeps the phase-1 `CheckpointStore` port pristine (string
  values, `(kind, id)` keys) while giving the service compile-time safety;
  schema churn stays in one file; tests can unit-test encoding separately and
  behavior-test through the service.
- **Alternatives considered**: put encoding in `IngestService` — mixes policy
  with mechanics, hard to test schemas alone; a new general-purpose port —
  unnecessary indirection when the checkpoint port already owns durability.

### D5. FanOutTrigger payload and sink port

- **Decision**: New domain record `FanOutTrigger`:
  `kind` (enum `FanOutTriggerKind { PUBLISH, WITHDRAW }`), `eventId`
  (publish/withdraw id), `productId`, `ingestSequence` (long, per-product),
  `matchedPublishId` (nullable String — non-null only for WITHDRAW), plus
  defensive validation (non-blank, non-null). New port `FanOutTriggerSink`
  with `void emit(FanOutTrigger trigger)`; Javadoc: at-most-once per ingest
  transition, emitted after checkpoint success, consumer idempotent on
  `(kind, eventId)`. Main-source stub `RecordingFanOutTriggerSink` records
  emissions in a synchronized list for tests and as the phase-2 seam.
- **Rationale**: Spec FR-005 fixes the payload contents. Carrying
  `ingestSequence` lets the future fan-out scheduler process triggers in
  per-product order (R5) without re-reading the store. `matchedPublishId`
  gives resolved-withdraw triggers their linkage (FR-011).
- **Alternatives considered**: `java.util.function.Consumer<FanOutTrigger>`
  constructor injection — no Javadoc/idempotency contract surface, weaker than
  a named port (phase-1 convention); Spring `ApplicationEventPublisher` —
  premature framework coupling, nothing consumes events yet; returning
  triggers from `ingest()` instead of a sink port — spec says "publish an
  internal fan-out trigger", the sink makes emission an observable side effect
  and survives the crash-injection tests.

### D6. Effective version (rule R4) as a pure function

- **Decision**: Static pure function (small `EffectiveVersion` helper in the
  `ingest` package): given a product's ingested publishes (`PRODUCT_PUBLISHES`
  list -> load each `PUBLISH` value), select max by
  `(publishedAtEpochMilli, publishId)` pair (timestamp descending, then
  publish id lexicographic). No stored "current version" state — always
  derived. Withdraw matching (R3) binds a deferred withdraw to the matched
  publish with the same greatest-pair among publishes with
  `toVersion == withdraw.version`.
- **Rationale**: Deriving (not storing) effective version makes the permutation
  test's convergence property hold by construction: any arrival order yields
  the same set of publishes, hence the same max pair. Storing a running
  "current" pointer would make state order-dependent and break FR-014.
- **Alternatives considered**: store effective version on ingest —
  order-dependent, fails permutation convergence for out-of-order publishes;
  compare version strings — spec declares versions opaque (no arithmetic).

### D7. Crash-window injection strategy for tests

- **Decision**: No production hooks. Tests inject failure at the two window
  edges using existing seams: (a) *kill after checkpoint, before trigger* — a
  `FailingTriggerSink` (test-only) throws on `emit` after the repository write
  has succeeded; catch the thrown error in the test, then redeliver the event
  and assert DUPLICATE + no second trigger. (b) *kill before checkpoint* — a
  `FailingCheckpointStore` wrapper (test-only) throws on the claim write;
  assert no trigger, no durable record, then redeliver against a healthy
  store and assert exactly one INGESTED effect.
- **Rationale**: The checkpoint-then-trigger order (spec R5) means the two
  failure points are fully defined by port boundaries; wrapping the ports is
  the standard fault-injection seam already used by `FakeDownstreamLoadClient`
  failure injection in phase 1. No `if (testMode)` branches in production code.
- **Alternatives considered**: `Clock`/time-based flakiness — non-deterministic;
  process-kill simulation via forked JVM — slow, fragile in `mvn verify`;
  package-private visibility toggles — test hooks leak into production design.

### D8. Metric names and tagging

- **Decision**: Counter names (dot-separated, identifier-only):
  `ingest.published`, `ingest.withdrawn`, `ingest.duplicate` (mandatory,
  FR-003/FR-032), `ingest.deferred`, `ingest.resolved`, `ingest.rejected`.
  Tags: `product_id=<id>` only where relevant; event ids are recorded via the
  trigger/checkpoint, not needed as metric tags (keeps metric cardinality
  bounded by product count, ~40). No timers required this phase (ingest is
  map-ops only); `recordTime` unused.
- **Rationale**: Spec mandates the duplicate counter; the others are permitted
  ("counters needed here") and make deferral/resolution observability testable
  and alert-ready without scope creep. Cardinality discipline follows
  Constitution V/VI.
- **Alternatives considered**: per-event-id tags — unbounded cardinality,
  useless at 40 products x weeks; no other counters — leaves deferral silent
  when the "matching publish never arrives" case (FR-015) needs an observable
  signal for later alerting.

## Carried-Forward Obligations (not this phase, tracked honestly)

1. **Cost arithmetic (Constitution IV)**: ingest adds ~6 map operations per
   event; run cost ~ 0. Full per-event/per-engagement/month arithmetic remains
   a fan-out-phase design-document obligation. Budget USD 5,000/month binding.
2. **End-to-end freshness SLO (Constitution VI)**: publish-to-pending p99 < 60 s
   becomes fully measurable only when fan-out runs; this phase delivers the
   ingest-to-trigger < 1 s slice (SC-006).
3. **Checkpoint durability across real process death**: still the in-memory
   placeholder; the `writeIfAbsent` contract now pins the atomicity a real
   adapter must provide (DB: unique-constraint INSERT).
4. **Trigger reconciliation after the checkpoint-to-trigger crash window**
   (R5/FR-024): content sufficiency is delivered now; the reconciler that
   scans ingested-but-untriggered state is a later phase.
5. **Bus acknowledgment wiring**: `EventSource` remains poll-only; real
   ack/nack belongs with the real bus adapter.
6. **Deferred-withdraw resolution repair pass**: a crash mid-resolution after
   the publish claim is written leaves `PENDING_WITHDRAW` entries that no
   redelivery will re-process (duplicate path); a future repair pass (or the
   trigger reconciler) can re-run resolution from those entries. Schemas keep
   the entries until explicitly cleared, enabling that pass.

## Principle VIII Disclosures

- **Riskiest element**: the deferred-withdraw resolution sequence. Ordering
  within one publish-ingest call is: (1) claim publish (`writeIfAbsent`),
  (2) append to `PRODUCT_PUBLISHES`, (3) take + resolve each pending withdraw
  for (P, V), each with its own `writeIfAbsent`-style claim on `WITHDRAW`,
  (4) clear `PENDING_WITHDRAW`, (5) emit triggers. A crash after (1) but
  before (4) means redelivery takes DUPLICATE and skips the remaining
  resolution. Mitigation: each withdraw resolution is independently idempotent,
  the pending list is cleared last, and the carried-forward repair pass (6)
  can finish the job from durable state. The happy reordering path is fully
  tested; the mid-resolution crash sub-window is documented, not automated.
- **Deliberately left out**: fan-out work, scheduling, retries, rate limiting,
  backfill, real bus/DB adapters, bus ack wiring, the trigger reconciler,
  Spring bean wiring, logging configuration (spec out-of-scope list).
- **The one requirement challenged**: **"Kill after checkpoint: no
  re-processing" (FR-021) combined with at-most-once in-process trigger
  emission (R5)**. These two together mean a crash in the checkpoint-to-trigger
  window permanently loses that delivery's trigger unless something re-derives
  it from checkpoint state. The spec accepts this by making the checkpoint the
  durable truth and requiring content sufficiency (FR-024); this plan accepts
  the same, with the reconciler deferred. The alternative — trigger-before-
  checkpoint — would re-emit duplicate triggers on every redelivery (safe if
  consumers are idempotent, which R5 already requires) and was rejected
  because the spec explicitly orders checkpoint-then-trigger. If phase-2
  scheduling proves trigger loss unacceptable, the cheap fix is the reconciler
  scanning `PUBLISH`/`WITHDRAW` records without a `TRIGGERED` marker —
  the schemas deliberately leave room for that (no trigger-status field yet).
- **Assumptions and impact if wrong**: delimiter-free natural keys (D3) — a key
  containing US or LF corrupts schema parsing; UUID-like ids make this safe,
  but a real adapter should switch to length-prefixed or structured encoding.
  Single-threaded per-product ingest sequencing (`PRODUCT_INGEST` is
  read-modify-write, last-write-wins) — concurrent same-product ingests on
  *different instances* may duplicate/skip sequence numbers; sequences are
  observational only (spec Assumptions), effective version uses R4 pairs, so
  correctness holds; ops should partition the bus by product. Sequential
  cross-instance delivery (FR-023 test scenario) is fully safe via
  `writeIfAbsent`.
