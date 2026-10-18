# Diagram inventory

| File | Purpose | Referenced by |
|------|---------|---------------|
| `architecture.mmd` | Components, stores, ownership, event bus; hot path vs load pool | Design §1 (embedded) |
| `event-flow.mmd` | Publish → summary + fan-out → decision → withdraw sequence | Design §2 |
| `state-machine.mmd` | Pending/decision states and transitions incl. withdrawn supersede | Design §2 (embedded) |
| `backfill-sequence.mmd` | Rate-limited, resumable, haltable backfill against load pool | Design "Migration and backfill" |

Render with any Mermaid-capable viewer.
