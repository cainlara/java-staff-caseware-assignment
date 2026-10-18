# Feature Specification: Idempotent Event Ingestion & External Checkpointing

**Feature Branch**: `002-idempotent-event-ingestion`

**Created**: 2026-10-18

**Status**: Draft

## User Scenarios & Testing *(mandatory)*

Context: this feature delivers event ingestion and its checkpoints only — no
fan-out work. The worker consumes `TemplatePublished` and `TemplateWithdrawn`
events from an at-least-once event bus. Phase 1 already delivered the immutable
event records, the boundary ports (event source, checkpoint store, metrics, and
the engagement-load dependency), and their test fakes. A single publish will
later fan out to ~20,000 engagements (scale: ~800,000 engagements across 40
products). Ingestion must be idempotent, tolerate arbitrary delay and
reordering, record all progress outside process memory, and never touch the
slow shared engagement-load service (that dependency takes ~1 minute per call
with an in-flight budget of C = 20 and is off the publish hot path). Freshness
SLO for the finished system: publish-to-pending p99 < 60 seconds.

### User Story 1 - Idempotent publish ingestion with a fan-out trigger (Priority: P1)

A publish event arrives on the bus. The ingest service validates the event's
natural keys, records the publish id as processed in the external checkpoint
store, and then emits exactly one internal fan-out trigger (a stub signal; real
fan-out is the next phase). Because the bus is at-least-once, the same publish
may arrive many times: every redelivery whose publish id is already checkpointed
is acknowledged successfully, creates no new trigger, increments a
duplicate-delivery counter, and never raises an error.

**Why this priority**: idempotent ingest is the correctness foundation for
everything downstream. Without it, duplicate deliveries would multiply into
tens of thousands of redundant engagement updates and breach both capacity and
cost budgets.

**Independent Test**: Deliver the same publish event repeatedly to the ingest
service; observe exactly one checkpoint write and one fan-out trigger for that
publish id, a duplicate-delivery counter increment per redelivery, zero errors,
and zero engagement-load calls.

**Acceptance Scenarios**:

1. **Given** a never-seen publish event, **When** it is ingested, **Then** its
   publish id is recorded in the checkpoint store and exactly one fan-out
   trigger carrying that identity is emitted.
2. **Given** a publish id already checkpointed, **When** the same event is
   delivered again, **Then** the delivery is acknowledged with no error, no new
   trigger is emitted, and the duplicate-delivery counter increments.
3. **Given** any ingest of a publish, **When** the engagement-load dependency's
   call log is inspected, **Then** it records zero calls.
4. **Given** an event whose natural keys are null, empty, or whitespace-only,
   **When** ingest validates it, **Then** it is rejected without checkpointing,
   without a trigger, and without partial state.

---

### User Story 2 - Withdraw ingestion with deferral until its publish arrives (Priority: P1)

A withdraw event retracts a specific version of a product's template. Buses
delay and reorder, so a withdraw may arrive before the publish that introduced
the version it retracts. Such a withdraw must be recorded and deferred — never
crashed on, never dropped. When the matching publish is later ingested, the
deferred withdraw resolves automatically against it and exactly one withdraw
trigger is emitted. A withdraw whose publish was already ingested is processed
immediately. The full resolution rule is specified under "Ordering &
Resolution Rules" below.

**Why this priority**: dropping or crashing on early withdraws loses
retractions permanently; processing them prematurely (before the publish is
known) cannot link them to the publish they retract. Both failure modes corrupt
the pending-update state later phases build on.

**Independent Test**: Deliver a withdraw for a product version whose publish
has not been ingested; observe it recorded as deferred with no crash, no drop,
and no trigger; then deliver the matching publish; observe the withdraw resolve
and emit exactly one withdraw trigger linked to that publish id.

**Acceptance Scenarios**:

1. **Given** no ingested publish for product P version V, **When** a withdraw
   for (P, V) is ingested, **Then** it is recorded as deferred in the checkpoint
   store, no error is raised, and no trigger is emitted.
2. **Given** a deferred withdraw for (P, V), **When** a publish of (P, V) is
   later ingested, **Then** the withdraw transitions to resolved with the
   matching publish id recorded, and exactly one withdraw trigger is emitted.
3. **Given** a publish for (P, V) already checkpointed, **When** a withdraw for
   (P, V) is ingested, **Then** it is processed immediately: checkpointed on its
   withdraw id with exactly one withdraw trigger emitted.
4. **Given** a deferred withdraw whose matching publish never arrives, **When**
   time passes, **Then** the withdraw stays deferred and observable — no crash,
   no drop, no error.

---

### User Story 3 - Crash recovery driven solely by external checkpoints (Priority: P2)

The checkpoint store is the sole record of ingest progress and lives outside
process memory (its interface must admit file- or database-backed
implementations later; for tests, an in-memory fake can be shared across
service instances). If the process dies after the checkpoint write but before
trigger emission, redelivery of that event is acknowledged as a duplicate and
the ingest is not re-processed; the durable checkpoint remains the truth from
which a later phase reconciles any missed trigger. If the process dies before
the checkpoint write, the event is redelivered and processed exactly once.

**Why this priority**: at-least-once delivery plus process crashes is the
normal operating condition, not an edge case. Progress that lives only in
process memory is lost on every deploy and crash, violating the no-maintenance-
window requirement.

**Independent Test**: Simulate a kill at each of the two points (after
checkpoint, before checkpoint) and re-run ingestion against the same shared
checkpoint store; assert exactly the single-ingest effect in both recovery
paths, and assert two service instances sharing one store behave as one
logical consumer for duplicate detection.

**Acceptance Scenarios**:

1. **Given** a checkpoint written for publish X, **When** the process is
   simulated as killed and restarted, **When** X is redelivered, **Then** it is
   acknowledged as a duplicate: no second ingest effect, duplicate counter
   incremented.
2. **Given** no checkpoint for publish X, **When** the process is simulated as
   killed and restarted before checkpointing, **When** X is redelivered, **Then**
   it is processed once: one checkpoint write, one trigger.
3. **Given** two ingest service instances sharing one checkpoint store, **When**
   the same publish is delivered to each instance, **Then** exactly one ingest
   effect occurs across both and the second delivery takes the duplicate path.
4. **Given** a crash after checkpoint but before trigger emission, **When** the
   checkpoint state is inspected, **Then** it contains the event's identity and
   ingest metadata sufficient for a later reconciler to detect the
   ingested-but-not-triggered gap.

---

### User Story 4 - Out-of-order publishes retained with recorded ingest order (Priority: P2)

Two publishes for the same product may arrive in any order (and each may be
duplicated). Both must be retained — neither may overwrite or suppress the
other — and the order in which the worker ingested them must be recorded. The
spec's documented rule determines the effective current version for the
product independent of arrival order. A permutation test proves that any
duplication and reordering of a batch of publishes converges to the same
functional checkpoint state.

**Why this priority**: silent suppression of an "older-looking" late publish
would lose real publishes; overwriting without order loses auditability. The
permutation convergence test is the proof that idempotency and ordering rules
are jointly correct, not just individually plausible.

**Independent Test**: Take a batch of publishes (including several for one
product), apply every tested permutation with injected duplicates, and assert
the final checkpoint state is functionally identical each time: same processed
publish-id set, same effective current version per product, same withdraw
resolutions.

**Acceptance Scenarios**:

1. **Given** two publishes for product P with different publish ids, **When**
   they arrive in either order, **Then** both publish ids are checkpointed and
   both triggers are emitted, with ingest arrival order recorded per product.
2. **Given** a batch of publishes, **When** any permutation with duplication is
   ingested, **Then** the processed publish-id set, the effective current
   version per product, and all withdraw resolutions are identical across
   permutations.
3. **Given** an ingest sequence recorded per product, **When** publishes arrive
   out of version order, **Then** the recorded ingest order reflects actual
   arrival order (observational), while the effective version follows the
   normative rule below.

---

### Edge Cases

- Duplicate delivery immediately after crash replay: must take the duplicate
  path (ack, no trigger, counter), never a second ingest.
- Withdraw delivered before its publish, then both redelivered as duplicates:
  withdraw resolves exactly once; duplicates of either take the duplicate path.
- Deferred withdraw whose matching publish is itself a duplicate delivery: the
  already-checkpointed publish still satisfies the resolution (resolution keys
  on ingest state, not on fresh trigger emission).
- Two distinct withdraw ids retracting the same version: each is processed once
  on its own withdraw id; both produce their own withdraw trigger once resolved.
- Two publishes to the same to-version (republish of a version): both retained;
  withdraw matching binds per the rule below.
- A publish event that is invalid (null/blank natural key despite record-level
  validation): rejected as poison — not checkpointed, no trigger, error
  surfaced; retry/dead-letter handling is out of scope.
- Deferred withdraw whose publish never arrives: retained indefinitely as
  deferred; no crash, no drop; pruning is a later phase.
- Event source exhausted (no events available): ingest is a no-op.
- Trigger consumer not yet wired (stub phase): trigger emission records
  identity only; ingestion correctness does not depend on a consumer.
- Concurrent ingest of the same event into two service instances sharing one
  store: combined idempotency — at most one ingest effect per event id.

## Requirements *(mandatory)*

### Ordering & Resolution Rules (normative)

These rules are binding; user stories and tests above depend on them.

- **R1 — Publish idempotency**: a publish is processed iff its publish id is
  not yet checkpointed. Processing records the publish id, assigns the next
  per-product ingest sequence number (monotonic per product, starting at 1),
  and emits one fan-out trigger. Any later delivery of the same publish id is
  a duplicate: acknowledged, no trigger, duplicate counter incremented, no
  error.
- **R2 — Withdraw idempotency**: a withdraw is processed iff its withdraw id
  is not yet checkpointed. Duplicates follow the same duplicate path as R1.
- **R3 — Withdraw-before-publish (deferral)**: when a withdraw for product P
  version V arrives and no ingested publish for (P, V) exists, the withdraw is
  recorded as *deferred* (idempotent on withdraw id), emits no trigger, and
  errors on nothing. When any publish with product P and to-version V is later
  ingested, every deferred withdraw for (P, V) transitions to *resolved*
  against that publish and emits exactly one withdraw trigger, carrying the
  matched publish id. Matching keys on (product id, to-version equality); if
  several ingested publishes share that to-version, the match binds to the one
  with the greatest (published-at timestamp, publish id) pair.
- **R4 — Publish retention and effective version**: all distinct publish ids
  are retained; ingest arrival order is recorded per product as a monotonic
  sequence. The *effective current version* of a product is the to-version of
  the ingested publish with the greatest (published-at timestamp, publish id)
  pair — deterministic regardless of arrival order. Version strings are opaque;
  no version-number arithmetic is performed.
- **R5 — Trigger emission order**: within one product, triggers are emitted in
  ingest-sequence order. The trigger is an in-process, at-most-once signal
  emitted after the checkpoint write succeeds; the checkpoint store is the
  durable truth. A crash in the checkpoint-to-trigger window is tolerated: the
  event is not re-ingested on redelivery (R1), and the checkpoint content is
  sufficient for a later reconciler to detect and repair the missing trigger.
- **R6 — Hot-path isolation**: ingestion and its triggers never load
  engagement content and never call the engagement-load dependency.

### Functional Requirements

**Ingestion path**

- **FR-001**: The system MUST provide an ingest service that processes each
  received event in the order: validate natural keys → record processed state
  in the checkpoint store → emit an internal fan-out trigger.
- **FR-002**: Publish ingestion MUST be idempotent on publish id and withdraw
  ingestion on withdraw id, per rules R1 and R2.
- **FR-003**: A duplicate delivery MUST be acknowledged without error, MUST
  create no new trigger, and MUST increment a duplicate-delivery counter.
- **FR-004**: An event with a null, empty, or whitespace-only natural key MUST
  be rejected before any checkpoint write or trigger, MUST leave no partial
  state, and MUST surface the failure to the caller.
- **FR-005**: The internal fan-out trigger MUST carry the event identity
  (event id plus product id) and its ingest sequence; a resolved-withdraw
  trigger MUST additionally carry the matched publish id. A stub
  implementation that records emissions MUST be provided for tests.

**Reordering tolerance**

- **FR-010**: A withdraw for a version whose publish has not been ingested
  MUST be recorded as deferred in the checkpoint store — not crashed on, not
  dropped, no trigger — per rule R3.
- **FR-011**: When a matching publish is later ingested, every deferred
  withdraw for that (product id, version) MUST transition to resolved with the
  matched publish id recorded, and MUST emit exactly one withdraw trigger.
- **FR-012**: A withdraw whose matching publish is already ingested MUST be
  processed immediately (checkpoint plus exactly one withdraw trigger).
- **FR-013**: Out-of-order publishes of the same product MUST all be retained,
  each checkpointed exactly once on its publish id, with per-product ingest
  arrival order recorded; the effective current version follows rule R4.
- **FR-014**: The system MUST provide a permutation test asserting that any
  duplication and reordering of a batch of publishes converges to identical
  functional checkpoint state: same processed publish-id set, same effective
  current version per product, same withdraw resolutions. Arrival-sequence
  fields may differ between permutations because they record the actual
  arrival order.
- **FR-015**: A deferred withdraw whose matching publish never arrives MUST
  remain recorded as deferred indefinitely, observable through the progress
  store, with no error.

**Crash recovery**

- **FR-020**: The checkpoint store MUST be the sole record of ingest progress,
  MUST live outside process memory, and MUST expose an interface that admits
  file- or database-backed implementations later; an in-memory fake shared
  across service instances MUST be provided for tests.
- **FR-021**: After a kill following the checkpoint write, redelivery of that
  event MUST take the duplicate path: no re-ingest, no second trigger,
  duplicate counter incremented.
- **FR-022**: After a kill preceding the checkpoint write, redelivery of that
  event MUST be processed exactly once: one checkpoint write, one trigger.
- **FR-023**: Multiple ingest service instances sharing one checkpoint store
  MUST exhibit combined idempotency: at most one ingest effect per event id
  across all instances.
- **FR-024**: Checkpoint content for an ingested event MUST include its
  identity and ingest metadata (per R1) so that a later reconciler can detect
  an ingested-but-not-triggered gap left by a crash in the checkpoint-to-trigger
  window (rule R5).

**Safety and telemetry invariants**

- **FR-030**: The ingest path MUST make zero calls to the engagement-load
  dependency; every ingest test MUST assert the fake client's call log is
  empty.
- **FR-031**: Ingest logs and metrics MUST carry identifiers and measurements
  only — never engagement content.
- **FR-032**: A duplicate-delivery counter MUST increment once per duplicate
  acknowledged (FR-003); correctness MUST NOT depend on metric values.

**Test-first coverage**

- **FR-040**: Tests MUST be written first and MUST cover: duplicate delivery,
  delayed delivery, withdraw-before-publish reordering, out-of-order publishes,
  crash-and-replay on both sides of the checkpoint write, multi-instance shared
  store, and permutation convergence (FR-014).
- **FR-041**: All ingest tests MUST assert zero engagement-load calls (FR-030).
- **FR-042**: All tests MUST run inside the project's existing single
  verification command, self-contained, with no external systems or documents.

### Key Entities

- **IngestedPublish**: one accepted publish event; identified by publish id
  (scoped by product id); carries from-version, to-version, publish timestamp,
  and the per-product ingest sequence assigned on arrival. All distinct
  publishes are retained (R1, R4).
- **IngestedWithdraw**: one accepted withdraw event; identified by withdraw id
  (scoped by product id); carries version, withdraw timestamp, and a status of
  *deferred* or *resolved*; when resolved, records the matched publish id
  (R2, R3).
- **FanOutTrigger**: internal signal that a resolved event awaits fan-out;
  carries event identity, product id, ingest sequence, and — for resolved
  withdraws — the matched publish id. At-most-once in-process emission per
  successful ingest transition (R5).
- **IngestProgress**: the durable, external record of the above (processed ids,
  per-product ingest sequences, effective-version contributions, deferred and
  resolved withdraw state) held in the checkpoint store; the sole progress
  truth across crashes (FR-020, FR-024).
- **TemplatePublished / TemplateWithdrawn**: existing immutable event records
  from phase 1; the inputs to this feature, unchanged.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Across the entire test suite, 100% of duplicate deliveries are
  acknowledged with zero errors, produce zero additional triggers, and
  increment the duplicate-delivery counter — zero divergent states.
- **SC-002**: 100% of withdraw-before-publish cases are deferred without loss;
  once the matching publish arrives, exactly one withdraw trigger and a
  resolved linkage (matched publish id) are recorded per withdraw id.
- **SC-003**: Crash-and-replay tests pass at 100%: kill-before-checkpoint
  yields exactly one ingest effect after redelivery; kill-after-checkpoint
  yields zero additional ingest effects.
- **SC-004**: The permutation test converges in 100% of tested permutations ×
  duplication patterns: identical processed publish-id sets, identical
  effective current version per product, identical withdraw resolutions.
- **SC-005**: Zero engagement-load dependency calls occur across all ingest
  tests, asserted on every test.
- **SC-006**: 99% of ingestions emit their fan-out trigger within 1 second of
  event receipt at the stated scale, keeping ingest far inside the 60-second
  publish-to-pending p99 freshness budget.
- **SC-007**: 100% of multi-instance tests show combined idempotency: two
  service instances sharing one checkpoint store produce at most one ingest
  effect per event id.
- **SC-008**: All delivered artifacts pass a self-containment review: zero
  references to external documents anywhere in the project.

## Assumptions

- Version values are opaque strings; no version-number arithmetic. Effective
  version and withdraw matching use rule R4/R3: greatest (published-at
  timestamp, publish id) wins; equal timestamps break on publish id.
- "Acknowledged" for a duplicate means processed without error by the ingest
  service. Explicit bus-acknowledgment wiring is out of scope (the phase-1
  event-source port has no acknowledgment method; its polling contract stands).
- The fan-out trigger is an in-process, at-most-once signal emitted after a
  successful checkpoint write. The durable checkpoint is the truth; a crash in
  the checkpoint-to-trigger window is repaired by a future reconciler reading
  checkpoint state (sufficiency guaranteed by FR-024). That reconciler is a
  later phase.
- Invalid events (blank/null natural keys) are poison: rejected without
  checkpointing; retry and dead-letter handling are out of scope.
- Deferred withdraws are retained indefinitely in the progress store this
  phase; pruning/alerting on long-deferred withdraws is a later phase.
- The per-product ingest sequence starts at 1 for each product and increments
  by 1 per newly ingested publish for that product; sequences are observational
  audit data, not correctness state (effective version uses R4).
- The duplicate-delivery counter is the only mandatory metric; additional
  counters (ingested, deferred, resolved) are permitted but not required.
- Existing phase-1 contracts are reused unchanged; recording withdraw
  processing state, per-product ingest sequences, and effective-version
  metadata in the progress store may require additive extensions to the
  checkpoint domain, which MUST remain backward compatible.
- Freshness SLO (publish-to-pending p99 < 60 seconds) applies to the finished
  system; this phase measures the ingest-to-trigger portion only (SC-006).

## Out of Scope

The following are explicitly excluded from this phase and MUST NOT be built now:

- Eligibility computation
- Index writes (pending-row fan-out effects)
- Scheduling and chunk dispatch
- Retries and backoff
- Rate limiting
- Backfill of existing engagements
- Real fan-out work (the trigger is a stub; consumption comes next phase)
- Reconciliation of the checkpoint-to-trigger crash window (content sufficiency
  is in scope via FR-024; the reconciler itself is not)
- Metrics implementation beyond the counters required here
- Real infrastructure adapters (file/database checkpoint store, real bus)
- Applying template content to engagement files
