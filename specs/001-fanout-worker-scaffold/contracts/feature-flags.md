# Contract: FeatureFlags

**Port**: `com.example.fanout.ports.FeatureFlags`

Staged enablement and emergency halt — constitution Principle VII requires
halting a bad fan-out without redeploy.

## Signature (conceptual)

```
boolean enabled(String productId)     // product-scoped rollout gate
boolean enabledFirm(String firmId)    // firm-scoped rollout gate
boolean haltSwitch()                  // global kill switch
```

## Semantics

- `haltSwitch() == true` means: admission of new fan-out work MUST stop
  immediately, system-wide. It dominates both `enabled` checks. Enforcement
  is a later phase; this phase pins observability of the switch.
- `enabled(productId)` / `enabledFirm(firmId)`: false means the worker MUST
  NOT fan out to that product / firm. Unknown ids default to **false**
  (opt-in rollout; safest default for a live system).
- Blank/null ids rejected (fail fast, consistent with domain validation).

## Contract test vectors

1. Unknown product id -> `enabled` false.
2. Known enabled product id -> true; disable it -> false (flip without
   redeploy).
3. Firm gate independent of product gate.
4. Halt switch true is observable; fake supports toggling.
5. Null/blank id rejected.
