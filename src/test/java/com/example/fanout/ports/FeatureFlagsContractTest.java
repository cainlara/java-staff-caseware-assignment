package com.example.fanout.ports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.example.fanout.fakes.FakeFeatureFlags;
import org.junit.jupiter.api.Test;

/**
 * Pins FeatureFlags contract (contracts/feature-flags.md): unknown ids default
 * false; enable/disable flips; firm gate independent; halt observable;
 * null/blank ids rejected.
 */
class FeatureFlagsContractTest {

    @Test
    void unknownProductIdDefaultsFalse() {
        var flags = new FakeFeatureFlags();
        assertThat(flags.enabled("unknown-prod")).isFalse();
        assertThat(flags.enabledFirm("unknown-firm")).isFalse();
    }

    @Test
    void enableDisableFlipWithoutRedeploy() {
        var flags = new FakeFeatureFlags();
        flags.setEnabled("prod-1", true);
        assertThat(flags.enabled("prod-1")).isTrue();
        flags.setEnabled("prod-1", false);
        assertThat(flags.enabled("prod-1")).isFalse();
    }

    @Test
    void firmGateIndependentOfProductGate() {
        var flags = new FakeFeatureFlags();
        flags.setEnabled("prod-1", true);
        assertThat(flags.enabled("prod-1")).isTrue();
        assertThat(flags.enabledFirm("firm-1")).isFalse();

        flags.setEnabledFirm("firm-1", true);
        assertThat(flags.enabledFirm("firm-1")).isTrue();
        assertThat(flags.enabled("prod-1")).isTrue();
    }

    @Test
    void haltSwitchToggleObservable() {
        var flags = new FakeFeatureFlags();
        assertThat(flags.haltSwitch()).isFalse();
        flags.setHalt(true);
        assertThat(flags.haltSwitch()).isTrue();
        flags.setHalt(false);
        assertThat(flags.haltSwitch()).isFalse();
    }

    @Test
    void nullOrBlankIdRejected() {
        var flags = new FakeFeatureFlags();
        assertThatIllegalArgumentException().isThrownBy(() -> flags.enabled(null));
        assertThatIllegalArgumentException().isThrownBy(() -> flags.enabled(" "));
        assertThatIllegalArgumentException().isThrownBy(() -> flags.enabledFirm(null));
        assertThatIllegalArgumentException().isThrownBy(() -> flags.enabledFirm(""));
    }
}
