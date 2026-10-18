# Contract: CapacityBudget

**Port**: `com.example.fanout.ports.CapacityBudget`

Bounds in-flight calls to the shared downstream engagement-load service.

## Signature (conceptual)

```
boolean tryAcquire()     // non-blocking: true = permit taken
void release()           // give back one permit; must pair with successful acquire
int available()          // remaining permits, for tests/metrics
```

## Idempotency / accounting

- Permits are counted resources, not idempotent keys: every successful
  `tryAcquire()` MUST be matched by exactly one `release()`.
- `release()` without a held permit MUST throw (illegal state), never
  silently inflate the budget.
- Thread-safe: concurrent acquire/release must never admit more than C
  in-flight.

## Capacity value

- Max in-flight `C`, configurable, **default 20**.
- When exhausted, `tryAcquire()` returns `false`; callers must back off or
  pause admission (backpressure is a later-phase behavior; no unbounded
  buffering is permitted by design).

## Hot path

- The publish hot path (index writes) does NOT consume permits.
- Only off-hot-path downstream loads acquire permits.

## Contract test vectors

1. C=3: three `tryAcquire()` succeed, fourth returns `false`; `available()`=0.
2. After one `release()`, next `tryAcquire()` succeeds.
3. `release()` with no held permit throws.
4. Concurrent hammering (many threads, C permits): observed in-flight never
   exceeds C; all acquires eventually releasable.
5. Configured C respected (construct with C=1, C=50, verify bounds).
