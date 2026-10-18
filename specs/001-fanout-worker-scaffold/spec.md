# Feature Specification: Fan-Out Worker Scaffold & Contracts

**Feature Branch**: `001-fanout-worker-scaffold`

**Created**: 2026-10-18

**Status**: Draft

## User Scenarios & Testing *(mandatory)*

Context: this feature delivers the foundation only — the verified build, the
validated data contracts, and the boundary interfaces (ports) of the
template-publish fan-out worker. No fan-out behavior runs yet. The worker is one
narrow component of a larger pending-template-update system that notifies
engagement files when a product team publishes a template version.

### User Story 1 - Verified foundation build (Priority: P1)

A platform engineer clones the worker project and runs its automated verification
once. The build completes with zero failures, proving the scaffold is real,
self-contained, and ready for the next phase of development.

**Why this priority**: without a verified, compiling foundation, no later work
(contracts, fakes, fan-out logic) can land safely. This is the entry ticket for
every other story.

**Independent Test**: Run the project's single automated verification command on a
clean machine with no prior state; it completes successfully with zero test
failures and requires no external systems, network services, or documents.

**Acceptance Scenarios**:

1. **Given** a clean checkout of the project, **When** the automated verification
   command runs, **Then** it completes with zero failures and zero errors.
2. **Given** the foundation build, **When** a developer inspects the delivered
   artifacts, **Then** every required contract (data records, ports, fakes,
   contract tests) is present and no out-of-scope behavior (event consumption,
   scheduling, retries, rate limiting, backfill, metrics implementation, UI,
   content application) exists.
3. **Given** the delivered artifacts, **When** they are reviewed, **Then** they
   reference no external documents and stand on their own.

---

### User Story 2 - Validated immutable data contracts (Priority: P2)

A downstream developer consumes the worker's data shapes and can trust them: every
record is immutable after creation, and every natural key (identifiers such as
publish id, product id, engagement id) is present and non-blank or the record is
rejected at construction. Metadata rows can never smuggle engagement content.

**Why this priority**: fan-out correctness depends on stable identities. Bad or
mutable identities make idempotency impossible to guarantee later.

**Independent Test**: Feed each record constructor a set of valid and invalid
inputs (null, blank, empty collections); valid inputs produce an unchangeable
record, invalid inputs are rejected deterministically with a clear error.

**Acceptance Scenarios**:

1. **Given** a valid set of field values, **When** a record is constructed, **Then**
   it is created successfully and no field can be changed afterwards.
2. **Given** a null or blank natural key (for example a missing publish id or a
   whitespace-only product id), **When** a record is constructed, **Then**
   construction is rejected.
3. **Given** a pending-index metadata row, **When** its fields are examined, **Then**
   it contains only metadata identifiers and never engagement content.
4. **Given** a set of publish ids on a row, **When** the record is constructed,
   **Then** the set is defensively copied so later external mutation cannot alter
   the record.

---

### User Story 3 - Boundary interfaces with pinned idempotency contracts (Priority: P3)

A developer implementing the next phase can rely on written, testable contracts for
every boundary the worker touches: where events come from, where pending rows are
written, how far progress has advanced, how many downstream calls may be in flight,
which rollouts are enabled, and what is measured. Because the event bus delivers
at-least-once, each contract documents its idempotency key, and contract tests with
test doubles prove duplicate delivery and replay converge to the same end state.

**Why this priority**: the at-least-once bus makes idempotency the core correctness
property; pinning it now prevents the fan-out phase from inventing conflicting keys.

**Independent Test**: Run the contract test suite against the provided test doubles;
each port's documented idempotency key and validation behavior is asserted, and no
real infrastructure is required.

**Acceptance Scenarios**:

1. **Given** the same pending-index write submitted twice with identical values,
   **When** the in-memory writer double is exercised, **Then** the stored row state
   is identical to a single write (no duplicate effect).
2. **Given** a write attempted against a stale concurrency token, **When** the
   compare-and-set is exercised, **Then** the write is rejected rather than
   silently overwriting newer state.
3. **Given** progress written against a publish id, **When** the process is
   simulated as restarted, **When** progress is read again, **Then** the previously
   written value is returned unchanged.
4. **Given** a downstream capacity budget of C permits, **When** more than C calls
   are attempted concurrently, **Then** admission beyond C is refused until a
   permit is released, and C is configurable.
5. **Given** the fake downstream load client configured with a delay and injected
   failures, **When** a load call is made, **Then** the call waits the configured
   simulated latency (on the order of one minute) and fails as instructed.
6. **Given** any counter or timer hook, **When** it is invoked, **Then** it records
   nothing harmful (no-op behavior) and never accepts engagement content.

---

### Edge Cases

- Duplicate, delayed, and reordered event delivery (at-least-once bus): replaying
  the same publish or withdraw must be detectable via its identity and must not
  produce divergent state.
- Natural keys supplied as null, empty string, or whitespace-only: construction
  must fail fast with a clear rejection, not create a half-valid record.
- Stale concurrency token on a row write (another writer advanced the row between
  read and write): write must be rejected via compare-and-set, never merged blindly.
- Checkpoint read for a key never written: must return an explicit "no progress"
  answer, distinguishable from a stored value.
- Capacity budget fully consumed: further acquisition must not exceed the
  configured maximum and must expose a way for callers to wait or fail cleanly.
- Halt switch engaged: the switch is observable through its contract so future
  phases can stop admission without redeploy.
- Downstream slow or failing: the fake must reproduce ~1-minute latency and
  injectable failures so off-hot-path behavior can be tested without the real
  shared service.
- Engagement content in telemetry: counters, timers, and log-shaped outputs must
  accept only identifiers and measurements, never engagement content.

## Requirements *(mandatory)*

### Functional Requirements

**Foundation build**

- **FR-001**: The system MUST deliver a buildable project named `fanout-worker`
  whose single automated verification command completes with zero test failures on
  a clean machine, with no external services required.
- **FR-002**: The build MUST target Java 21 (LTS) with Spring Boot 3.5.x as the
  parent framework line, and MUST use JUnit 5 with Spring Boot Test support.
- **FR-003**: All delivered artifacts MUST be self-contained: no references to
  external documents or intake materials.

**Data contracts (immutable records with validation)**

- **FR-010**: The system MUST provide an immutable record for a published template
  event carrying publish id, product id, from version, to version, and publish
  timestamp.
- **FR-011**: The system MUST provide an immutable record for a withdrawn template
  event carrying withdraw id, product id, version, and withdraw timestamp.
- **FR-012**: The system MUST provide an immutable record identifying an engagement
  (engagement id, firm id, product id, current version, region) used only as a
  reference handle off the hot path.
- **FR-013**: The system MUST provide an immutable pending-index row record
  carrying engagement id, firm id, product id, current version, offered version,
  state (PENDING, APPLIED, DECLINED, or CREATED), decision, pinned ranges, summary
  key, the set of contributing publish ids, row version, and update timestamp.
- **FR-014**: Every record MUST reject construction when any natural key
  (identifier field) is null, empty, or whitespace-only, with a clear error.
- **FR-015**: Collection-typed fields (publish id set, pinned ranges) MUST be
  defensively copied so records stay immutable after construction.
- **FR-016**: Pending-index rows MUST hold metadata identifiers only; engagement
  content MUST NOT be representable in them.
- **FR-017**: The system MUST provide an immutable fan-out task record binding a
  publish id to a batch identity (batch id and position within the chunked run) for
  later chunked processing.
- **FR-018**: The system MUST provide an immutable publish outcome record carrying
  publish id, rows written, rows skipped, failures count, and a status indicating
  whether the publish completed, partially completed, or failed.

**Ports (boundary interfaces with documented idempotency keys)**

- **FR-020**: The system MUST define an event-source port able to poll/receive both
  published-template and withdrawn-template events, documenting that its delivery
  is at-least-once and that consumers must be idempotent on event identity
  (publish id or withdraw id plus product id).
- **FR-021**: The system MUST define an index-writer port whose upsert is
  idempotent on the key (engagement id, offered version, state) and uses
  compare-and-set on row version, rejecting stale writes.
- **FR-022**: The system MUST define a downstream-load port returning the current
  template version for an engagement reference; it is documented as off-hot-path
  only (never called while writing pending rows) with simulated ~1 minute latency
  in test doubles.
- **FR-023**: The system MUST define a checkpoint port that reads and writes
  external progress keyed separately by publish id and by backfill batch id, and
  that is documented to survive process restart; an in-memory implementation is
  delivered for now.
- **FR-024**: The system MUST define a capacity-budget port with acquire/release
  semantics, a maximum in-flight value C defaulting to 20, and C configurable.
- **FR-025**: The system MUST define a feature-flag port exposing enablement by
  product id, enablement by firm id, and a global halt switch.
- **FR-026**: The system MUST define a metrics port with counter and timer hooks
  implemented as no-ops for now, and MUST document that engagement content is never
  accepted as an argument.
- **FR-027**: Every port MUST carry Javadoc (or equivalent) stating its
  idempotency key, its consistency expectation, and where it may be called
  (hot path vs off hot path).

**Test doubles and contract tests**

- **FR-030**: Every port MUST have a test fake: an in-memory index writer, an
  in-memory checkpoint store, a fake downstream load client with configurable
  latency and failure injection, plus doubles for event source, capacity budget,
  feature flags, and metrics.
- **FR-031**: Contract tests MUST pin each port's documented idempotency key by
  demonstrating that a duplicate submission yields the same end state as a single
  submission.
- **FR-032**: Contract tests MUST pin validation behavior: null/blank natural keys
  are rejected for every record, and stale compare-and-set writes are rejected by
  the index writer.
- **FR-033**: Contract tests MUST require no real infrastructure and MUST run
  inside the single verification command from FR-001.

**Safety and telemetry invariants**

- **FR-040**: Logs and metrics MUST NEVER contain engagement content; only
  identifiers and measurements are permitted.
- **FR-041**: The publish hot path MUST be documented as touching only the pending
  update index (metadata rows); loading engagement content on the hot path is
  prohibited.

### Key Entities

- **TemplatePublished**: a product team's act of publishing a template version
  from one version to another; identified by publish id and product id; the unit
  of work the worker will later fan out.
- **TemplateWithdrawn**: retraction of a previously published template version;
  identified by withdraw id, product id, and version; must interact safely with
  publishes already in flight.
- **EngagementRef**: a lightweight handle to one engagement file (which firm, which
  product, which current version, which region); the only thing the worker may
  carry about an engagement on the hot path.
- **PendingIndexRow**: one metadata row in the pending update index saying "this
  engagement has been offered version X of product Y, in state Z, as a result of
  these publishes"; carries a row version for safe concurrent updates; never holds
  engagement content.
- **FanOutTask**: one chunk of a publish's fan-out work (publish id + batch
  identity), the schedulable unit for later phases.
- **PublishOutcome**: the report of what one publish achieved (rows written,
  skipped, failures, final status), used for coverage measurement and alerting.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: The project's automated verification completes with 100% of tests
  passing and zero failures on a clean machine, with no external system access.
- **SC-002**: For every record type, 100% of null/blank natural-key inputs are
  rejected at construction; zero invalid records can be created.
- **SC-003**: Given any duplicate submission of the same indexed write, the end
  state is byte-for-byte identical to the single-submission end state (0 divergent
  states across all contract tests).
- **SC-004**: Progress written before a simulated process restart is readable
  unchanged after the restart in 100% of checkpoint contract tests.
- **SC-005**: The capacity contract never admits more than C in-flight calls in
  any test run, with C adjustable from its default of 20.
- **SC-006**: Zero engagement-content values appear in any metric name, tag, or
  log-shaped output across the entire test suite.
- **SC-007**: Every defined port has at least one contract test asserting its
  documented idempotency key or validation rule (100% port coverage by contract
  tests).
- **SC-008**: All artifacts pass a self-containment review: zero references to
  external documents anywhere in the delivered project.

## Assumptions

- The worker's language, build tool, framework line, and test stack are fixed by
  the project's ratified implementation scope (Java 21 LTS, Maven, Spring Boot
  3.5.x, JUnit 5, Spring Boot Test); this spec does not re-decide them, it only
  requires that the delivered build satisfy them (FR-002).
- Scale context assumed correct: ~4,000 firms, ~800,000 active engagements,
  40 products, ~20,000 engagement files per publish, median firm ~40 engagements,
  largest firm ~40,000.
- The event bus is at-least-once; exactly-once delivery is never assumed.
- Downstream engagement-load service takes ~1 minute per call, is owned by another
  team, and has an in-flight concurrency budget of C = 20 (configurable
  placeholder).
- Freshness SLO for the finished system: publish-to-pending p99 < 60 seconds;
  this phase delivers no fan-out behavior, so the SLO is not yet measurable and is
  carried forward for later phases.
- No maintenance window ever exists; everything delivered must be compatible with
  continuous operation.
- "No-op metrics" and "in-memory checkpoint store" are placeholder implementations
  for this phase; replacing them with real adapters is a later phase.
- Default region values are opaque identifiers; residency enforcement is a later
  phase concern.

## Out of Scope

The following are explicitly excluded from this phase and MUST NOT be built now:

- Event consumption logic (polling loops, acknowledgment, deduplication flow)
- Scheduling and chunk dispatch
- Retries and backoff
- Rate limiting
- Backfill of existing engagements
- Real metrics/logging implementation and real infrastructure adapters
- User interface
- Applying template content to engagement files
- The freshness SLO measurement itself (no fan-out runs yet)
