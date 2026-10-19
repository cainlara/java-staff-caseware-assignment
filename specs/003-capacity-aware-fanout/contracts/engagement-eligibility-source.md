# Contract: EngagementEligibilitySource

**Component**: `com.example.fanout.ports.EngagementEligibilitySource` (new port)

Streams engagement eligibility rows from Pending Update Index metadata for one
publish (hot path) or one offered version (withdraw supersede). Never loads
engagement content; never calls the downstream engagement-load client.

## Signature (conceptual)

```
Stream<EngagementRef> streamEligible(productId: non-blank String,
                                     publishId: non-blank String)
Stream<EngagementRef> streamOffering(productId: non-blank String,
                                     version: non-blank String)
```

Throws only on null/blank arguments (defensive path) and on backing-store
failures, which surface as unchecked exceptions and are retried by the engine
per the retry contract (`ELIGIBILITY_STREAM` unit kind).

## Ordering (normative, spec R1 / FR-003)

Both streams MUST yield rows ordered by: firm id ascending, then engagement id
ascending within firm. Ordering is the basis for positional chunking and
crash-resume alignment; a real adapter MUST sort (or serve pre-sorted
metadata). Same inputs MUST yield the same order across calls (determinism).

## Content rules (spec FR-001, FR-070)

- Rows are `EngagementRef` handles: engagement id, firm id, product id,
  current version, region — identifiers only. No engagement content in any
  element, exception message, or log.
- `streamEligible` MUST NOT call `DownstreamLoadClient` and MUST NOT load
  engagement data from customer stores — index metadata only.
- A publish with no eligible rows yields an empty stream (not an error).
- `streamOffering` MAY be empty (version not offered) — not an error.

## Fake: `FakeEligibilitySource` (test)

- Backed by in-memory lists keyed `(productId, publishId)` and
  `(productId, version)`; seeding helpers per test.
- Programmatic generator: `seedScale(productId, publishId, bigFirmId,
  bigFirmCount, smallFirmCount, smallFirmCount...)` style helpers for the
  40,000-engagement simulation (spec FR-072).
- Failure injection: `failNextStream(n)` — next n stream calls throw a
  transient exception (retry tests).

## Contract test vectors

1. Seeded rows stream in firm-id/engagement-id order regardless of insert
   order.
2. Empty eligibility set -> empty stream, no error.
3. `streamOffering` returns exactly the seeded rows for that version.
4. Injected failure surfaces as an exception; after injection window, streams
   again (retryable).
5. Call log / dependency inspection: zero `DownstreamLoadClient` calls (spec
   FR-032 asserted by every consuming test as well).
