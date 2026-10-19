package com.example.fanout.fakes;

import com.example.fanout.ports.FeatureFlags;

/**
 * {@link FeatureFlags} seam for crash tests: {@code enabled} (engagement
 * admission) and {@code enabledFirm} (batch and engagement admission) count as
 * kill events; {@code haltSwitch} does NOT (the guard probes it once per
 * pause — noise, and admission decisions are what the seam models).
 * Delegates every read to the wrapped fake, so staged-enablement behaviour is
 * unchanged.
 */
public final class KillingFeatureFlags implements FeatureFlags {

    private final FeatureFlags delegate;
    private final KillController controller;

    public KillingFeatureFlags(FeatureFlags delegate, KillController controller) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        if (controller == null) {
            throw new IllegalArgumentException("controller must not be null");
        }
        this.delegate = delegate;
        this.controller = controller;
    }

    @Override
    public boolean enabled(String productId) {
        controller.event("admission.product");
        return delegate.enabled(productId);
    }

    @Override
    public boolean enabledFirm(String firmId) {
        controller.event("admission.firm");
        return delegate.enabledFirm(firmId);
    }

    @Override
    public boolean haltSwitch() {
        return delegate.haltSwitch();
    }
}
