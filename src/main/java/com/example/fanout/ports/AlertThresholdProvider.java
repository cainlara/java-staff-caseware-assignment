package com.example.fanout.ports;

import com.example.fanout.observability.AlertThresholds;

/**
 * Supplies runtime-changeable alert thresholds (spec FR-014,
 * contracts/alert-hooks.md): operators retune without redeploy. Invalid
 * records are rejected by {@link AlertThresholds} construction, so a bad
 * hot-swap fails loud at the provider boundary.
 */
public interface AlertThresholdProvider {

    AlertThresholds current();
}
