# Contract: EventSource

**Port**: `com.example.fanout.ports.EventSource`

Receives template lifecycle events from the at-least-once event bus.

## Signature (conceptual)

```
Optional<TemplatePublished> pollPublish()
Optional<TemplateWithdrawn> pollWithdraw()
```

- `Optional.empty()` means "no event currently available" — not an error.

## Idempotency key

| Event | Key |
|-------|-----|
| TemplatePublished | `publishId` (scoped by `productId`) |
| TemplateWithdrawn | `withdrawId` (scoped by `productId`) |

## Consistency expectations

- Delivery is **at-least-once**: the same event may be observed multiple times,
  in any order, with arbitrary delay. Duplicate, delayed, reordered, and
  missing events must all be tolerated by consumers.
- The source MUST NOT claim exactly-once; consumers MUST deduplicate by the
  key above before applying effects.
- An empty poll MUST NOT be treated as end-of-stream; there is no
  terminal state in a no-maintenance-window system.

## Hot path

Polling is on the publish hot path only in the sense that received events are
the trigger; the hot path itself may only write pending-index metadata rows
(never load engagement content).

## Errors

- Transport/runtime failures surface as unchecked exceptions from poll calls;
  retry policy is a later phase (out of scope here).

## Contract test vectors

1. Returning the same `TemplatePublished` twice yields equal natural keys
   (consumers can deduplicate on `publishId`).
2. Empty poll returns `Optional.empty()` without throwing.
3. Blank `publishId` / `withdrawId` values cannot be constructed into events
   (domain validation), so the source can never emit an unkeyed event.
