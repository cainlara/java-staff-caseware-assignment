package com.example.fanout.fakes;

import com.example.fanout.ports.FeatureFlags;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mutable {@link FeatureFlags} fake: opt-in defaults (unknown ids false),
 * independent product/firm gates, observable halt toggle. Null/blank ids rejected.
 */
public final class FakeFeatureFlags implements FeatureFlags {

    private final Set<String> enabledProducts = ConcurrentHashMap.newKeySet();
    private final Set<String> enabledFirms = ConcurrentHashMap.newKeySet();
    private volatile boolean halt;

    @Override
    public boolean enabled(String productId) {
        requireNonBlank(productId, "productId");
        return enabledProducts.contains(productId);
    }

    @Override
    public boolean enabledFirm(String firmId) {
        requireNonBlank(firmId, "firmId");
        return enabledFirms.contains(firmId);
    }

    @Override
    public boolean haltSwitch() {
        return halt;
    }

    public void setEnabled(String productId, boolean value) {
        requireNonBlank(productId, "productId");
        if (value) {
            enabledProducts.add(productId);
        } else {
            enabledProducts.remove(productId);
        }
    }

    public void setEnabledFirm(String firmId, boolean value) {
        requireNonBlank(firmId, "firmId");
        if (value) {
            enabledFirms.add(firmId);
        } else {
            enabledFirms.remove(firmId);
        }
    }

    public void setHalt(boolean halt) {
        this.halt = halt;
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must be non-blank");
        }
    }
}
