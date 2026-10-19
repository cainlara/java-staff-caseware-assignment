package com.example.fanout.adapters.inmemory;

import com.example.fanout.observability.AlertThresholds;
import com.example.fanout.ports.AlertThresholdProvider;
import java.util.Objects;

/**
 * Settable {@link AlertThresholdProvider} (spec FR-014): proves runtime
 * threshold change without redeploy in tests; {@link AlertThresholds}
 * construction validation rejects a bad hot-swap before it can be observed.
 */
public final class InMemoryAlertThresholdProvider implements AlertThresholdProvider {

    private volatile AlertThresholds current;

    public InMemoryAlertThresholdProvider() {
        this(AlertThresholds.defaults());
    }

    public InMemoryAlertThresholdProvider(AlertThresholds initial) {
        this.current = Objects.requireNonNull(initial, "initial must not be null");
    }

    public void set(AlertThresholds thresholds) {
        this.current = Objects.requireNonNull(thresholds, "thresholds must not be null");
    }

    @Override
    public AlertThresholds current() {
        return current;
    }
}
