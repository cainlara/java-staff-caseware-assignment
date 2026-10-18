# Phase 0 Research: Fan-Out Worker Scaffold & Contracts

**Feature**: 001-fanout-worker-scaffold | **Date**: 2026-10-18

All Technical Context fields resolved from the constitution's Implementation
Scope and the feature spec — **zero NEEDS CLARIFICATION items remain**. Decisions
below are recorded with rationale and alternatives, plus the constitution
Principle VIII disclosures.

## Decisions

### D1. Build and language stack

- **Decision**: Maven single module named `fanout-worker`, parent
  `spring-boot-starter-parent` 3.5.x, `<java.version>21</java.version>`, test
  dependencies `spring-boot-starter-test` (brings JUnit 5 + AssertJ +
  Mockito). No other dependencies.
- **Rationale**: Fixed by constitution Implementation Scope and spec FR-001/
  FR-002; Spring Boot parent supplies dependency management, compiler plugin
  release 21, and Surefire configured for JUnit 5 with zero extra config.
- **Alternatives considered**: Gradle (rejected — constitution says Maven);
  Spring Boot BOM without parent (more config, no benefit at one module);
  adding Lombok for records (rejected — records make it unnecessary, fewer deps).

### D2. Validation placement for immutable records

- **Decision**: Java `record` types with compact constructors that validate:
  null/blank natural keys and null timestamps throw `IllegalArgumentException`
  (blank check via `String.isBlank()`); collection fields (`publish_ids`,
  `pinned_ranges`) defensively copied with `List.copyOf`/`Set.copyOf` and stored
  unmodifiable; null collections rejected where mandatory.
- **Rationale**: Compact-constructor validation is the idiomatic zero-dependency
  way to guarantee "reject at construction" (FR-014). CopyOf gives
  fail-fast-on-null plus immutability (FR-015) in one call. Plain JDK keeps the
  module self-contained.
- **Alternatives considered**: Bean Validation annotations (javax/jakarta
  `@NotBlank`) — requires validator engine wiring, does not run on plain
  construction, weaker guarantee; builder classes with validation — extra
  ceremony for fixed-shape records; factory static methods only — records still
  need constructor guards or callers bypass factories.

### D3. PendingIndexRow shape details

- **Decision**: `state` as enum `PendingIndexState { PENDING, APPLIED, DECLINED, CREATED }`.
  `decision` and `summary_key` as nullable Strings (metadata identifiers;
  absent until a decision/summary exists). `pinned_ranges` as `List<String>`
  (opaque range identifiers, e.g. version-ancestry tokens; never content).
  `publish_ids` as non-null `Set<String>` (at least one contributing publish).
  `row_version` as `long`, starting at 1 for new rows, incremented on each
  successful CAS update. `updated_at` as `Instant`.
- **Rationale**: Spec names all fields but leaves types open; these are the
  minimal faithful types. `row_version: long` matches compare-and-set semantics
  on the idempotency key (engagement_id, offered_version, state) per FR-021.
- **Alternatives considered**: `pinned_ranges` as structured record pairs —
  premature until decline semantics defined in a later phase; `row_version`
  as UUID — CAS on monotonic long is simpler to reason about and test.

### D4. Port signatures (summary; full contracts in `contracts/`)

- **Decision**:
  - `EventSource`: `Optional<TemplatePublished> pollPublish()`,
    `Optional<TemplateWithdrawn> pollWithdraw()`; Javadoc: at-least-once,
    idempotency key = publish_id / withdraw_id + product_id.
  - `IndexWriter`: `WriteResult upsert(PendingIndexRow row)` returning
    WRITTEN / SKIPPED_DUPLICATE / REJECTED_STALE; Javadoc: idempotent on
    (engagement_id, offered_version, state), CAS on row_version, hot path only.
  - `DownstreamLoadClient`: `String loadEngagement(EngagementRef ref)` returns
    current template version identifier; Javadoc: off hot path only, ~1 min
    latency expected.
  - `CheckpointStore`: `Optional<String> read(CheckpointKey key)` /
    `void write(CheckpointKey key, String value)`; `CheckpointKey` is a small
    record (kind = PUBLISH | BACKFILL_BATCH, id); Javadoc: must survive
    process restart.
  - `CapacityBudget`: `boolean tryAcquire()` / `void release()` /
    `int available()`; max in-flight C configurable (default 20).
  - `FeatureFlags`: `boolean enabled(String productId)`,
    `boolean enabledFirm(String firmId)`, `boolean haltSwitch()`.
  - `FanOutMetrics`: `void increment(String name, long delta, String... tags)` /
    `void recordTime(String name, Duration d, String... tags)`; Javadoc:
    engagement content forbidden as name/tag/duration argument source.
- **Rationale**: Minimal surfaces that already pin the contract test vectors
  (duplicate write, stale CAS, missing checkpoint, capacity exhaustion).
  `WriteResult` enum makes skip-vs-write observable without exceptions for the
  normal duplicate case — essential for at-least-once idempotency (FR-031).
- **Alternatives considered**: `upsert` returning boolean — loses
  stale-rejection vs duplicate-skip distinction; checked exceptions for stale
  CAS — exceptions for expected hot-path flow add noise and cost; separate
  `readPublish`/`readBackfill` methods — a typed key keeps one method pair and
  documents both key domains (spec: "keyed by publish_id and by backfill batch id").

### D5. Fake strategy and determinism

- **Decision**:
  - `InMemoryIndexWriter` (test scope): `ConcurrentHashMap` keyed by the
    idempotency triple; CAS via `compute` — reject when stored row_version >=
    incoming (stale), skip when identical effective state already present,
    else store with row_version = expected+1 semantics.
  - `InMemoryCheckpointStore` (main scope, per spec "in-memory impl for now"):
    `ConcurrentHashMap<CheckpointKey, String>`; contract test simulates
    "restart" by constructing a fresh writer instance over the same map to
    prove durability is the impl's responsibility boundary.
  - `FakeDownstreamLoadClient` (test scope): constructor/configurable
    `Duration latency` (default 60s simulated), failure injection via
    configurable failure count or predicate; latency simulated with a
    `Clock`-injected or virtual wait — tests pass `Duration.ZERO` or small
    values by default, one dedicated test asserts the configured default is
    ~60s without actually sleeping (assert on the configured field/behavior,
    use zero latency in execution paths to keep `mvn verify` fast).
  - `FakeEventSource`, `FakeCapacityBudget` (reuses real semaphore-style
    logic), `FakeFeatureFlags`, `RecordingFanOutMetrics` (captures calls so
    tests can assert no engagement content keys appear).
- **Rationale**: Determinism and fast `mvn verify`; 60 s real sleeps are
  unacceptable in unit contract tests. Failure injection needed for later
  phases' tests, provided now per spec FR-030.
- **Alternatives considered**: Awaitility-based real-time latency test — slow
  and flaky in CI; Mockito mocks for every port — weaker behavioral pinning
  than stateful in-memory doubles for idempotency tests.

### D6. Package/module layout

- **Decision**: `com.example.fanout` root; sub-packages `domain`, `ports`,
  `adapters.inmemory` (main), test tree mirrors with `fakes` and `ports`
  contract tests. No Spring `@Configuration` beans yet — this phase is
  framework-neutral interfaces; Spring Boot present as build foundation only
  (no `@SpringBootTest` context required for contract tests).
- **Rationale**: Spec says focus on interfaces and correctness; premature
  bean wiring adds tests-without-behavior. Spring Boot Test dependency is
  available (FR-002 satisfied) and used if a later phase needs context tests.
- **Alternatives considered**: `@ConfigurationProperties` for C now —
  config plumbing with nothing to configure at runtime this phase; separate
  `fanout-contracts` + `fanout-worker` modules — overkill for one deliverable
  named `fanout-worker`.

### D7. Telemetry safety pattern

- **Decision**: `FanOutMetrics` Javadoc states the invariant; contract test
  uses `RecordingFanOutMetrics` to assert recorded names/tags come only from
  an allowlist pattern (identifiers/measurements) — this phase the test pins
  that the recording double is what contract tests observe and documents the
  no-content rule. No logger configuration needed (no log statements this
  phase).
- **Rationale**: FR-040 is best enforced now as a written contract plus a
  test-shaped seam; real enforcement (structured logging config, redaction)
  belongs to the metrics-implementation phase.
- **Alternatives considered**: custom argument type `MetricTag` whitelist now —
  constrains future metric design prematurely; logback redaction filter now —
  nothing logs yet.

## Carried-Forward Obligations (not this phase, tracked honestly)

1. **Cost arithmetic (Constitution IV)**: full per-event/per-engagement/month
   arithmetic is required in the fan-out design document, not the scaffold.
   Scaffold run cost ≈ 0 (build-time only). Budget USD 5,000/month remains the
   binding constraint for later phases.
2. **Freshness SLO measurement (Constitution VI)**: p99 < 60 s becomes
   measurable only when fan-out runs; `FanOutMetrics` is the seam.
3. **Residency enforcement (Constitution V)**: region stays an opaque id on
   `EngagementRef`; routing/residency rules belong with real adapters.
4. **Durability of checkpoints**: `InMemoryCheckpointStore` does NOT survive
   process restart by nature; the *contract* demands it, and a future adapter
   (DB/queue-backed) fulfills it. Contract tests pin the interface semantics;
   the restart guarantee is deferred with the adapter, explicitly.

## Principle VIII Disclosures

- **Riskiest element**: the (engagement_id, offered_version, state) +
  row_version CAS contract. If a later phase invents a conflicting key or
  treats row_version as advisory, at-least-once duplicate publishes corrupt
  pending state. Mitigation: contract tests pin it now; contracts/ docs name
  the key explicitly.
- **Deliberately left out**: event consumption, scheduling, retries, rate
  limiting, backfill, real metrics/adapters, UI, content application (all
  spec-mandated out-of-scope). Also left out: Spring bean wiring, config
  files, and any runtime main() — nothing runs this phase.
- **The one requirement challenged**: **C = 20 concurrent downstream calls with
  ~1 min latency vs. publish-to-pending p99 < 60 s**. At C=20 and 60 s/call,
  throughput is ~20 engagements/s ≈ 1,200/min; a 20,000-row publish needs
  ~17 min end-to-end if every row required a downstream load. That cannot meet
  p99 < 60 s if downstream loads sit on the critical path to *pending*
  creation. Working assumption (to confirm in the fan-out design doc): the
  hot path writes PENDING rows from index metadata without downstream loads;
  `DownstreamLoadClient` is used only off the hot path (e.g. revalidation,
  summaries, backfill). Spec FR-022/FR-041 already encode this; the scaffold
  is safe either way, but the design document MUST make the arithmetic
  explicit. If the assumption is wrong, freshness SLO and C=20 are jointly
  infeasible and one must move.
- **Assumptions and impact if wrong**: see research carried-forward list and
  spec Assumptions; highest-impact items: at-least-once (idempotency keys
  become mandatory, not optional), C=20 (throughput ceiling), ~1/min update
  frequency (monthly volume ~40 publishes × 20k rows = 800k row-writes/month,
  trivial for index metadata writes).
