# Feature Specification: Fan-Out Observability, README & Compliance

**Feature Branch**: `observability` (spec dir: `005-fanout-observability-compliance`)

**Created**: 2026-10-19

**Status**: Draft

## User Scenarios & Testing *(mandatory)*

Operators, reviewers, and future maintainers of the template-publish fan-out worker need to see whether the worker is meeting its freshness and coverage objectives, detect stalls and saturation before users complain, verify that no confidential engagement data leaks into telemetry, and understand the component's design tradeoffs from a self-contained README. This feature finishes the worker (phases 1–4 deliver the engine) with measurement, alerting, proof-of-correctness tests, documentation, and a constitution compliance pass.

### User Story 1 - Measure SLOs with metrics (Priority: P1)

An operator watches the fan-out worker through metrics and can answer, at any time: how far behind ingest is, how many deliveries were duplicates, how far each publish has progressed and at what rate, how many downstream calls are in flight, how many retries and dead-letter items exist, how far checkpoints lag, how fresh each publish end-to-end is, and how far backfill has advanced — without reading logs or querying another team's storage.

**Why this priority**: The constitution requires SLOs to be measurable before they can be claimed; every other story (alerts, demo, README) depends on these measurements existing.

**Independent Test**: Run a simulated publish through the worker; assert each required measurement is emitted with identifier-only dimensions and correct values (progress counters reach totals, in-flight gauge never exceeds capacity C, freshness is recorded per publish).

**Acceptance Scenarios**:

1. **Given** a TemplatePublished event is ingested, **When** fan-out completes, **Then** ingest lag, fan-out progress and rate, per-publish freshness (ingest-to-last-index-write), and row-write counts are observable and match the simulated work.
2. **Given** downstream calls are simulated, **When** retries and dead-lettering occur, **Then** retry count and dead-letter depth are observable and match the injected failures.
3. **Given** checkpoints advance during processing, **When** the publish finishes, **Then** checkpoint lag is observable and backfill progress is reported when a backfill runs.
4. **Given** any emitted measurement, **When** inspected, **Then** its dimensions carry identifiers only (publish, product, firm, region, chunk, state) — never engagement content, never unbounded per-engagement dimensions that would explode storage cost.

---

### User Story 2 - Confidentiality-proof structured logging (Priority: P2)

All worker log events are structured and restricted to a whitelist of identifier fields. A test fails the build if any log event contains a field outside the whitelist or a payload shaped like engagement content — so confidentiality of telemetry is enforced by the build, not by convention.

**Why this priority**: Confidentiality breaches are unrecoverable (constitution V); an automated build-time guard is the difference between a promise and a guarantee, and it gates any future telemetry change.

**Independent Test**: Run the full test suite with log capture enabled; assert every emitted event contains only whitelisted identifier fields (publish_id, product_id, firm_id, engagement_id, region, chunk id, state) and no content-shaped values.

**Acceptance Scenarios**:

1. **Given** the worker processes events across all code paths (ingest, fan-out, retry, dead-letter, supersede, backfill), **When** the confidentiality test runs, **Then** every captured log event passes the whitelist and no-content check.
2. **Given** a developer adds a new log statement with a non-whitelisted field, **When** tests run, **Then** the confidentiality test fails and names the offending field.
3. **Given** any telemetry emission path, **When** inspected, **Then** no path emits engagement content or client-identifying material to logs, metrics, or traces.

---

### User Story 3 - Alert hooks with documented thresholds (Priority: P3)

An alerting integration can subscribe to worker alert conditions through a defined interface. Each condition has a documented threshold and evaluation window: freshness burn (per-publish p99 above 60s), coverage breach (rows unprocessed after 24 hours), dead-letter queue non-empty, backfill stalled (no checkpoint advance in a window), and downstream saturation (in-flight pinned at capacity C with zero completions).

**Why this priority**: Measurement without alerting only helps someone who is already looking; saturation and coverage breaches must surface proactively. Depends on Story 1's metrics as signal source.

**Independent Test**: Drive the worker into each of the five fault conditions in simulation; assert the alert interface receives the correct condition with the correct identifiers, and that the condition clears when the fault is removed.

**Acceptance Scenarios**:

1. **Given** a publish whose end-to-end freshness p99 exceeds 60 seconds, **When** the evaluation window closes, **Then** a freshness-burn alert fires carrying the publish identifier.
2. **Given** a publish with rows still unprocessed 24 hours after ingest, **When** evaluated, **Then** a coverage-breach alert fires.
3. **Given** the dead-letter queue holds at least one item, **When** evaluated, **Then** a DLQ-non-empty alert fires with depth information.
4. **Given** a backfill whose checkpoint has not advanced for the configured window, **When** evaluated, **Then** a backfill-stalled alert fires.
5. **Given** in-flight downstream calls pinned at C with zero completions over the window, **When** evaluated, **Then** a downstream-saturation alert fires.
6. **Given** the fault condition clears, **When** evaluated, **Then** the alert clears (no permanent sticky state).

---

### User Story 4 - End-to-end demo proving the engine under observation (Priority: P4)

A single simulated end-to-end test demonstrates a TemplatePublished event fanning out to 40,000 engagements through the real engine and asserts the observable outcome: all eligible rows written, zero downstream calls on the hot path, in-flight never exceeded C, and freshness was measured. This test is the runnable "demo" referenced by the README.

**Why this priority**: This is the evidence that observability wraps the real engine rather than a toy path, and it protects against regressions in the capacity and hot-path guarantees. Depends on Stories 1–2.

**Independent Test**: Run the demo test alone; it must complete without external infrastructure and assert every listed outcome.

**Acceptance Scenarios**:

1. **Given** a simulated publish covering 40,000 engagements, **When** the engine fans out, **Then** every eligible engagement ends in pending state (row written or superseded), matching the eligibility set exactly.
2. **Given** the hot path (ingest to pending-index write), **When** observed, **Then** zero downstream calls occur on that path.
3. **Given** capacity C, **When** the fan-out runs, **Then** the in-flight gauge never exceeds C at any sample point.
4. **Given** the completed run, **When** metrics are read, **Then** end-to-end freshness for the publish is measurable and recorded.

---

### User Story 5 - Self-contained README (Priority: P5)

A maintainer or reviewer reads a roughly two-page README in the worker's documentation and learns what the component does, the key implementation tradeoffs (index-only hot path, capacity budget and rate limiter, chunk-cursor external checkpoints, ancestry-range decline pins, separate real-time and backfill pools, at-least-once plus idempotency over exactly-once), the known weaknesses, the cost arithmetic against the USD 5,000/month budget, the placeholder assumption C = 20 pending downstream-owner confirmation, how to run tests and the demo — with no references to external documents.

**Why this priority**: Required to finish the component and to survive hand-off and later review; depends on the feature set being final enough to describe truthfully.

**Independent Test**: Review the README against the tradeoff, weakness, cost-arithmetic, and run-instruction checklists; every required topic is present and self-contained.

**Acceptance Scenarios**:

1. **Given** the README, **When** reviewed, **Then** it states what the component does, each named tradeoff, the known weaknesses, the riskiest element, the C = 20 placeholder assumption, and the budget arithmetic.
2. **Given** the README, **When** a new maintainer follows its instructions, **Then** they can run the test suite and the demo without consulting any other document.
3. **Given** the README, **When** searched, **Then** it contains no references to external documents as sources of required information.

---

### User Story 6 - Constitution compliance pass with explicit TODOs (Priority: P6)

A compliance pass reviews the whole worker and the README against every constitution principle (data ownership, idempotency, capacity-awareness, cost, confidentiality, observability, safe evolution, honest tradeoffs). Every gap found is recorded as an explicit TODO; no gap is silently omitted.

**Why this priority**: Governance requires re-check at the worker-complete milestone; this is the last gate before hand-off. Depends on Stories 1–5 so there is a complete surface to review.

**Independent Test**: Audit the worker and README against the constitution checklist; every principle is either satisfied with cited evidence or tracked as an explicit TODO.

**Acceptance Scenarios**:

1. **Given** the constitution principles, **When** the pass runs, **Then** each principle has a recorded outcome: compliant (with evidence location) or gap (with TODO).
2. **Given** a discovered gap, **When** recorded, **Then** it appears as an explicit TODO in the project's tracked artifacts — never as silent omission.
3. **Given** the cost principle, **When** reviewed, **Then** the arithmetic shows no per-engagement inference and total worker run cost within USD 5,000/month, with the numbers stated.

---

### Edge Cases

- **Duplicate publish delivery**: the same publish event delivered twice must increment the duplicate-delivery measurement and leave row state unchanged (idempotent).
- **Publish with zero eligible engagements**: metrics must record completion (progress at zero of zero) without false alerts or divide-by-zero failures.
- **Metric dimension explosion**: engagement identifiers must not become metric dimensions (800,000+ engagements would breach cost); logs may carry engagement identifiers, metrics must stay low-cardinality.
- **Dead-letter queue empty**: depth reads zero rather than being absent, so alerting sees a real zero.
- **Downstream saturation**: in-flight pinned at C with zero completions must produce the saturation alert within its window, not be mistaken for healthy slow progress.
- **All engagements declined** (every row superseded or declined): freshness is still recorded; coverage is satisfied without any pending rows.
- **Backfill halted mid-run**: checkpoint lag and stalled alerting must reflect the halt; resume must not re-alert as stalled while intentionally halted.
- **Process crash mid-publish**: externally recorded checkpoints make progress observable after restart; measurements must not double-count written rows on resume.
- **Non-whitelisted field in a new log statement**: build fails via the confidentiality test rather than shipping.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST expose a metrics implementation covering: ingest lag, duplicate deliveries, fan-out progress and rate, in-flight downstream count, retry count, dead-letter depth, checkpoint lag, per-publish freshness (from publish ingest to last index write), and backfill progress.
- **FR-002**: Per-publish freshness MUST be recorded as a distribution (histogram-style) so p99 against the 60-second SLO is computable per publish and in aggregate.
- **FR-003**: All emitted measurements MUST carry only identifier dimensions (publish, product, firm, region, chunk, state) and MUST NOT use engagement identifiers as metric dimensions.
- **FR-004**: System MUST emit structured log events restricted to a whitelist of identifier fields: publish_id, product_id, firm_id, engagement_id, region, chunk id, state.
- **FR-005**: A build-enforced test MUST assert that every log event emitted during the test suite contains no field outside the whitelist and no engagement-content-shaped payload; the test MUST fail with the offending field named.
- **FR-006**: System MUST provide an alert interface with defined conditions, thresholds, and evaluation windows for: freshness burn (per-publish p99 > 60s), coverage breach (unprocessed rows after 24h), dead-letter queue non-empty, backfill stalled (no checkpoint advance within the configured window), downstream saturation (in-flight pinned at C with zero completions over the window).
- **FR-007**: Alerts MUST carry identifiers (publish, product, firm, region as applicable) and MUST clear when the underlying condition clears.
- **FR-008**: System MUST include an end-to-end simulated demo test that fans a TemplatePublished event out to 40,000 engagements through the real engine and asserts: eligible rows written exactly, zero downstream calls on the hot path, in-flight never exceeding C, and freshness measurable.
- **FR-009**: System MUST include a self-contained README covering: component purpose; the implementation tradeoffs (index-only hot path, capacity budget and rate limiter, chunk-cursor external checkpoints, ancestry-range decline pins, separate real-time and backfill pools, at-least-once plus idempotency over exactly-once); known weaknesses; the riskiest element; the placeholder assumption C = 20 pending downstream-owner confirmation; cost arithmetic against the USD 5,000/month budget including the worker's ~USD 150/month compute baseline; and how to run tests and the demo.
- **FR-010**: README MUST NOT rely on references to external documents for any required information; artifacts MUST be self-contained.
- **FR-011**: System MUST record a compliance pass over the whole worker and README against every constitution principle, marking each as compliant (with evidence location) or gap (with an explicit TODO); gaps MUST NOT be silently omitted.
- **FR-012**: Observability additions MUST NOT introduce per-engagement inference or any cost path that breaches the USD 5,000/month total budget; the arithmetic MUST be stated in the README.
- **FR-013**: Metrics, logs, and traces MUST carry identifiers only — never engagement content or client-identifying material — on every code path including error, retry, and dead-letter paths.
- **FR-014**: Alert condition configuration (thresholds, evaluation windows) MUST be changeable at runtime without redeploy, consistent with the safe-evolution principle.
- **FR-015**: The demo test MUST run without real cloud infrastructure, external message brokers, or real downstream systems (simulated downstream only).

### Key Entities *(include if feature involves data)*

- **Metric observation**: a single measurement emitted by the worker; attributes are identifiers only (publish_id, product_id, firm_id, region, chunk id, state); type is counter, gauge, or distribution.
- **Log event**: one structured record; fields restricted to the identifier whitelist; produced on ingest, fan-out, retry, dead-letter, supersede, checkpoint, and backfill paths.
- **Alert condition**: named condition (freshness burn, coverage breach, DLQ non-empty, backfill stalled, downstream saturation) with threshold, evaluation window, affected identifiers, and active/cleared state.
- **Demo publish result**: outcome of the 40,000-engagement simulated publish — rows written, downstream hot-path call count, in-flight peak, freshness value.
- **Compliance finding**: one constitution principle reviewed; outcome is compliant-with-evidence or gap-with-TODO.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Every measurement listed in FR-001 is emitted and value-verified by automated tests for a simulated publish; none is defined-but-never-fired.
- **SC-002**: Per-publish p99 freshness is computable from emitted data; the 60-second threshold is asserted in tests (breach fires alert, pass does not).
- **SC-003**: The confidentiality test passes across the full suite with zero non-whitelisted fields and zero content-shaped payloads; introducing a violating log statement makes the build fail.
- **SC-004**: All five alert conditions fire within their documented evaluation windows in simulation and clear when the fault clears — 5 of 5 conditions verified.
- **SC-005**: The 40,000-engagement demo completes with: 100% of eligible engagements in pending state, zero hot-path downstream calls, in-flight peak ≤ C, freshness recorded — 4 of 4 assertions pass.
- **SC-006**: README covers all topics in FR-009 in about two pages, contains the budget arithmetic showing compliance, and contains no required-information references to external documents.
- **SC-007**: Compliance pass covers 100% of constitution principles; every gap is an explicit TODO; zero silent omissions.
- **SC-008**: Observability overhead stays within budget: no per-engagement inference, no per-engagement metric dimensions; stated arithmetic shows total run cost ≤ USD 5,000/month.

## Assumptions

- Phases 1–4 (contracts, idempotent ingestion, capacity-aware engine, backfill with halt and staged enablement) already exist in this worker; this feature extends them and does not rebuild them.
- Downstream capacity C = 20 is a placeholder pending confirmation from the downstream-capacity owner; all code and docs treat C as configurable, defaulting to 20.
- Alert *delivery* (paging, email, chat) is out of scope; only the in-process interface, conditions, thresholds, and evaluation windows are delivered.
- Metrics storage, dashboards, and trace backends are out of scope; the worker emits measurements, a backend consumes them.
- Scale and SLOs are as stated: ~4,000 firms, ~800,000 engagements, ~20,000 rows per typical publish, ~40 publishes/week; freshness p99 < 60s publish-to-pending; unprocessed rows after 24h breach coverage.
- Budget: total feature run cost < USD 5,000/month, worker compute baseline ≈ USD 150/month; observability cost (metric series count, log volume) is included in the arithmetic.
- Engagement content never leaves its region; index rows are metadata only; telemetry carries identifiers only — these constraints pre-exist and are verified, not re-designed, by this feature.
- Runtime alert configuration is read from existing worker configuration mechanisms; no new configuration system is introduced.
- The simulated demo uses the existing simulation/test-double patterns from phases 1–4 rather than introducing a new simulation framework.
- Compliance TODOs are tracked in the repository's existing tracked artifacts (README TODO section and/or spec checklist notes), not an external tracker.
