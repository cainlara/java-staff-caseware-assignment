package com.example.fanout.ports;

/**
 * Staged enablement and emergency halt — a bad fan-out MUST be halt-able
 * without redeploy.
 *
 * <p>{@link #haltSwitch()} {@code true} means admission of new fan-out work MUST
 * stop immediately, system-wide; the halt dominates both {@code enabled} checks.
 *
 * <p>{@link #enabled(String)} / {@link #enabledFirm(String)}: {@code false} means
 * the worker MUST NOT fan out to that product / firm. Unknown ids default to
 * {@code false} (opt-in rollout). Null/blank ids are rejected.
 */
public interface FeatureFlags {

    boolean enabled(String productId);

    boolean enabledFirm(String firmId);

    boolean haltSwitch();
}
