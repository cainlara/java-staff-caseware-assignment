# Contract: CapacityShares and Halt-Aware Permit Guard

**Phase**: 4 · **Spec**: B5, FR-020–FR-024, FR-031, SC-004/SC-005 ·
**Research**: D5, D9

## CapacityShares

Partition of the shared downstream capacity `C` (other team's agreement:
total in-flight ≤ C = 20) into two disjoint permit pools.

```java
public record CapacityShares(int parentC, int backfill, int repair) {
    // compact constructor: parentC >= 1, backfill >= 1, repair >= 0,
    // backfill + repair <= parentC  (else IllegalArgumentException at startup, FR-021)
    public CapacityBudget backfillBudget(); // capacity = backfill
    public CapacityBudget repairBudget();  // capacity = repair
}
```

Defaults: `CapacityShares(20, 18, 2)`. Pacing arithmetic (constitution IV):
800,000 loads × 1 min / 18 ≈ 30.9 days; / 20 ≈ 27.8 days — `backfill` is the
knob.

### Invariants (SC-005)

1. Two independent budgets; each is the delivered `CapacityBudget` contract
   (counted permits, exactly-once release, release-without-acquire throws).
2. `inFlight(backfill) ≤ backfill`, `inFlight(repair) ≤ repair`, so
   `total ≤ parentC` at every instant — by construction, no shared state.
3. A waiter on one share can never acquire from the other (separate
   semaphores, separate wait queues). Proven by test: a repair waiter
   acquires while all 18 backfill permits are held.
4. `BackfillWorker` is constructed only with `backfillBudget()`. The publish
   hot path constructs neither. A future real-time repair caller uses
   `repairBudget()` via its own `PermitGuardedDownstream` instance.

## PermitGuardedDownstream (extended)

The delivered single sanctioned downstream-call path gains a halt probe:

```java
public PermitGuardedDownstream(..., BooleanSupplier haltProbe)  // new overload
// existing constructor delegates with () -> false
```

Sequence (unchanged except the probe):

1. Acquire loop: `budget.tryAcquire()`; on failure increment
   `*.backpressure`, check `haltProbe.getAsBoolean()` — true ⇒ throw
   `AdmissionHaltedException` (nothing held), else pause via `BackoffWaiter`
   and re-loop.
2. Probe is also checked once before the first acquire attempt.
3. On success: invoke `loadEngagement`; wrap non-transient failures as
   `TransientFanOutException`; `finally` decrements in-flight and releases
   exactly once.

### AdmissionHaltedException (new)

- Unchecked, **non-transient**, distinct type — `RetryExecutor` does not
  catch it (only `TransientFanOutException` retries), it is never
  dead-lettered, and it is never wrapped by the guard.
- Callers treat it as "admission withdrawn": count
  `backfill.halt.abandoned`, leave the unit unresolved behind its cursor.
- Holding no permit when thrown (thrown only inside the failed-acquire loop).

### Invariants

1. Permit held only for the duration of the `loadEngagement` call — not
   across retry backoff (unchanged, delivered).
2. Release exactly once per acquire on every path (success, failure, halt
   abandonment after a prior successful acquire in the same call cannot
   occur — the probe fires only while un-permitted).
3. HWM counter semantics unchanged (`*.downstream.inflight.hwm`).
4. Hot-path prohibition unchanged: `FanOutEngine` has no dependency on this
   class; publish tests keep asserting an empty downstream call log.
