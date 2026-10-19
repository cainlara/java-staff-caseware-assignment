<!--
Sync Impact Report
- Version change: (template placeholder) → 1.0.0
- Modified principles: none (initial ratification; template placeholders replaced)
- Added sections:
  - Core Principles: eight principles (was five template slots)
    I. Data Ownership & Module Boundaries
    II. Idempotent & Resilient Event Handling
    III. Capacity-Aware Fan-Out
    IV. Cost-Aware Design with Documented Arithmetic
    V. Confidentiality & Data Residency
    VI. Observability & SLOs
    VII. Safe Evolution of Live Systems
    VIII. Honest Tradeoffs & Assumptions
  - Design Document Requirements
  - Implementation Scope: Template-Publish Fan-Out Worker
  - Governance
- Removed sections: none
- Deferred TODOs: none
- Notes: scratch report; remove before commit per workflow instructions.
-->

# Pending Template Update Notification System Constitution

## Core Principles

### I. Data Ownership & Module Boundaries

Every component MUST declare the data it owns and MUST NOT read or write data owned by
another component except through that owner's published interface.

- The product template store owns template IDs, versions, and content. It MUST NOT gain
  knowledge of engagement files.
- Customer databases own engagement files. Engagement data MUST NOT leave its
  customer-scoped store, and MUST NOT be copied into shared infrastructure.
- Cross-system interaction MUST happen via hooks or events published by the owning
  system, never by direct queries into another team's storage.
- Each module MUST have a single, documented responsibility so it can be tested and
  reasoned about in isolation.

Rationale: ownership blur is the root cause of coupling, residency violations, and
irreversible data leaks in a multi-tenant platform.

### II. Idempotent & Resilient Event Handling

Every consumer of template-publish or engagement events MUST be idempotent and MUST
tolerate duplicate, delayed, reordered, and missing events.

- Processing MUST be keyed on a stable identity (template ID + version, engagement ID)
  so replaying an event produces the same end state.
- Failures MUST NOT lose work: unacknowledged events MUST be retried with bounded
  backoff, and retry exhaustion MUST surface to an alert, not to silent drop.
- State transitions (pending, applied, declined) MUST be explicit and MUST record what
  a "declined" decision pins (specific template version or ancestry), so accumulated
  updates resolve deterministically.
- Event delivery MUST be assumed unreliable; correctness MUST NOT depend on
  exactly-once delivery.

Rationale: live systems cannot be paused; at-least-once delivery plus idempotent
consumers is the only tractable correctness model.

### III. Capacity-Aware Fan-Out

Any fan-out work MUST be budgeted against downstream capacity before it is scheduled,
and MUST degrade gracefully when the downstream is slow or unavailable.

- The engagement-loading dependency takes approximately 1 minute per file and is owned
  by another team; concurrency MUST be rate-limited to an explicitly stated limit
  derived from that team's published capacity.
- A single template publish MAY fan out to a very large engagement set (firms range
  from ~40 to ~40,000 engagements); the worker MUST chunk, queue, and checkpoint work
  so no burst can starve the downstream or exhaust local resources.
- Backpressure MUST propagate: when the downstream refuses or slows, the worker MUST
  pause admission rather than buffer unboundedly.
- Work units MUST be resumable after process crash, with progress recorded outside
  process memory.

Rationale: an unbounded fan-out against a 1-minute, shared-capacity dependency will
serialize or crash the dependency; explicit budgets keep the system live.

### IV. Cost-Aware Design with Documented Arithmetic

Every design MUST include explicit cost arithmetic against the stated scale, and total
monthly run cost — including any AI/LLM inference spend — MUST stay within the
assumed budget of USD 5,000 per month.

- Arithmetic MUST use the real numbers: 4,000 firms, 800,000 active engagement files,
  40 products, median ~40 and largest ~40,000 engagements per firm, template updates
  ~1 per product per week, freshness target within seconds of publish.
- Design MUST state per-event, per-engagement, and per-month cost estimates for
  compute, storage, network, and inference, and MUST show the budget is not exceeded.
- Where cost and freshness conflict, the tradeoff MUST be stated explicitly in the
  design document rather than assumed away.

Rationale: unstated cost assumptions are the most common way a correct design becomes
an unshippable one.

### V. Confidentiality & Data Residency

Engagement content is client-confidential audit workpaper material. It MUST be handled
as confidential data and MUST honor contractual and legal residency requirements
(EU, Canada).

- Engagement data MUST remain in-region and MUST NOT be transmitted to, or processed
  in, another region.
- Product template content is not firm-specific; only template metadata and diffs
  derived from it MAY cross regions, and only when no engagement data is embedded.
- Access MUST follow least privilege; logs, metrics, and traces MUST NOT contain
  engagement content or client-identifying material.
- LLM-based summaries MUST be generated only from template diffs or from data cleared
  for the target region, and prompts MUST NOT exfiltrate workpaper content to
  non-approved providers.

Rationale: a residency or confidentiality breach is unrecoverable and typically a
contract breach as well.

### VI. Observability & SLOs

The system MUST ship with defined SLOs and the instrumentation needed to measure them.

- Freshness SLO: a published template update MUST appear as pending for affected
  engagement files within seconds of publish (state the exact target, e.g. p99 < 60s,
  in the design document).
- Coverage SLO: every published update MUST eventually produce pending state for every
  eligible engagement; lag MUST be measurable per publish.
- Structured logs, metrics, and traces MUST cover ingest, fan-out progress, downstream
  latency, retry and dead-letter counts, and summary-generation success rate.
- Alerts MUST fire on SLO burn, downstream saturation, and stalled fan-out, not only
  on process crash.

Rationale: sub-second-to-seconds freshness claims are unverifiable without measurement;
regulatory review in a later month requires evidence of correct operation in March.

### VII. Safe Evolution of Live Systems

All systems are live with no maintenance window. Every change MUST be deployable
without downtime and MUST be reversible.

- Schema, event, and state changes MUST be backward compatible across at least one
  release: expand, migrate, contract — never break readers or writers in place.
- Backfill and migration for existing engagement files MUST be incremental,
  rate-limited, resumable, and MUST NOT require loading all 800,000 engagements at
  once.
- Feature rollout MUST support staged enablement (per firm or per product) so a bad
  fan-out or summarization change can be halted without redeploy.
- The pending-update indicator MUST work for engagement files created before the
  feature existed, not only newly created ones.

Rationale: without a maintenance window, "deploy and hope" is not a migration plan.

### VIII. Honest Tradeoffs & Assumptions

Every design and implementation MUST document its tradeoffs, assumptions, and known
weaknesses plainly.

- The design MUST name the riskiest part of the design, what was deliberately left
  out, and the one requirement the author would challenge.
- Assumptions (including the cost budget, downstream capacity, and update frequency)
  MUST be listed with the impact if each proves wrong.
- Summaries, diffs, and estimates MUST be reproducible: given the same template
  versions, regeneration MUST yield the same summary, or the divergence MUST be
  explained and versioned.
- Ambiguity MUST be flagged as an explicit TODO rather than papered over with
  confident-sounding prose.

Rationale: a summary shown to a user in March may need to be defended during a
regulatory review in November; only documented, reproducible reasoning survives that.

### IX. Java Import and Type Reference Conventions

- **MUST** use imported simple type names instead of fully qualified class or interface names in Java source code.
- **MUST** add the required `import` declarations at the beginning of the compilation unit and use the corresponding simple names in fields, method signatures, parameters, local variables, generic type arguments, annotations, and object construction.
- **MUST NOT** use fully qualified names such as `java.util.Set`, `java.util.List`, or `java.util.HashMap` inline in declarations or executable code when a normal import can be used.
- **MUST** prefer explicit imports over wildcard imports (`import java.util.*;`).
- **MUST** resolve simple-name conflicts using a clear, consistent strategy. If two types have the same simple name, use an explicit project-approved resolution strategy rather than fully qualifying types indiscriminately.
- **MUST** review newly created and modified Java files for violations before considering an implementation complete.

**Examples**

Preferred:
```java
import java.util.HashSet;
import java.util.Set;

public Set<String> findRoles() {
    Set<String> roles = new HashSet<>();
    return roles;
}
```

Prohibited when imports can be used:
```java
public java.util.Set<String> findRoles() {
    java.util.Set<String> roles = new java.util.HashSet<>();
    return roles;
}
```

**Scope:** All production and test Java source files, including nested generic types and method signatures.

**Exceptions:** Fully qualified names are permitted only when required to disambiguate conflicting simple names or to satisfy a specific documented technical constraint. Any exception should be minimal and justified.

## Governance

- This constitution supersedes all other practices and preferences for this project.
  Where any other instruction, template, or convention conflicts with a principle
  here, this constitution wins.
- Amendments MUST be made through the `speckit.constitution` workflow: update the
  document, increment the version per the versioning policy, and record the change in
  a Sync Impact Report at the top of the file for review, then remove the report
  before committing.
- Versioning policy (semantic):
  - MAJOR: backward-incompatible governance changes, including principle removal or
    redefinition.
  - MINOR: new principle or section added, or materially expanded guidance.
  - PATCH: clarifications, wording, typo fixes, non-semantic refinements.
- Compliance review: every pull request and review MUST verify compliance with all
  principles here. Complexity and cost beyond the stated budget MUST be justified in
  the design document before merge.
- Review cadence: compliance MUST be re-checked at each milestone (design complete,
  worker complete, hand-off). Unresolved items MUST be tracked as explicit TODOs.
- Use the design document for runtime development guidance; this constitution governs
  what the design and code MUST satisfy, not how to implement each detail.

**Version**: 1.0.0 | **Ratified**: 2026-10-16 | **Last Amended**: 2026-10-16
