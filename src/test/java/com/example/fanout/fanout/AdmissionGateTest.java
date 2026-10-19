package com.example.fanout.fanout;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.fanout.fakes.FakeFeatureFlags;
import com.example.fanout.fakes.RecordingFanOutMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verdict precedence and rejection counters for the admission seam
 * (contracts/admission-gates.md, spec B6/B9): halt dominates both gates, the
 * product gate dominates the firm gate, every non-ADMIT verdict increments the
 * matching {@code {fanout|backfill}.admit.reject.{halt,product,firm}} counter
 * once, and verdicts are pure observations of live flag state.
 */
class AdmissionGateTest {

    private static final String PRODUCT = "prod-1";
    private static final String FIRM = "firm-1";

    private FakeFeatureFlags flags;
    private RecordingFanOutMetrics metrics;

    @BeforeEach
    void setUp() {
        flags = new FakeFeatureFlags();
        metrics = new RecordingFanOutMetrics();
    }

    @Test
    void admitsWhenHaltOffAndBothGatesEnabled() {
        flags.setEnabled(PRODUCT, true);
        flags.setEnabledFirm(FIRM, true);

        AdmissionGate gate = new AdmissionGate(flags, metrics);

        assertThat(gate.admit(PRODUCT, FIRM)).isEqualTo(AdmissionGate.Verdict.ADMIT);
        assertThat(metrics.counterNames()).isEmpty();
    }

    @Test
    void haltDominatesBothGates() {
        flags.setHalt(true);

        AdmissionGate gate = new AdmissionGate(flags, metrics);

        assertThat(gate.admit(PRODUCT, FIRM)).isEqualTo(AdmissionGate.Verdict.HALTED);
        assertThat(metrics.total("fanout.admit.reject.halt")).isEqualTo(1);
        assertThat(metrics.total("fanout.admit.reject.product")).isZero();
        assertThat(metrics.total("fanout.admit.reject.firm")).isZero();
    }

    @Test
    void productGateBeatsFirmGate() {
        flags.setEnabledFirm(FIRM, true);

        AdmissionGate gate = new AdmissionGate(flags, metrics);

        assertThat(gate.admit(PRODUCT, FIRM)).isEqualTo(AdmissionGate.Verdict.GATED_PRODUCT);
        assertThat(metrics.total("fanout.admit.reject.product")).isEqualTo(1);
        assertThat(metrics.total("fanout.admit.reject.firm")).isZero();
    }

    @Test
    void firmGateRejectsWhenProductEnabled() {
        flags.setEnabled(PRODUCT, true);

        AdmissionGate gate = new AdmissionGate(flags, metrics);

        assertThat(gate.admit(PRODUCT, FIRM)).isEqualTo(AdmissionGate.Verdict.GATED_FIRM);
        assertThat(metrics.total("fanout.admit.reject.firm")).isEqualTo(1);
        assertThat(metrics.total("fanout.admit.reject.product")).isZero();
    }

    @Test
    void rejectionTagsCarryIdentifiersOnly() {
        AdmissionGate gate = new AdmissionGate(flags, metrics);

        gate.admit(PRODUCT, FIRM);

        RecordingFanOutMetrics.CounterCall call = metrics.getCounters().getFirst();
        assertThat(call.name()).isEqualTo("fanout.admit.reject.product");
        assertThat(call.tags()).containsExactly("product_id", PRODUCT, "firm_id", FIRM);
    }

    @Test
    void forBackfillEmitsBackfillCounters() {
        AdmissionGate gate = AdmissionGate.forBackfill(flags, metrics);

        assertThat(gate.admit(PRODUCT, FIRM)).isEqualTo(AdmissionGate.Verdict.GATED_PRODUCT);
        assertThat(metrics.total("backfill.admit.reject.product")).isEqualTo(1);
        assertThat(metrics.total("fanout.admit.reject.product")).isZero();
    }

    @Test
    void admitFirmIgnoresProductGateBecauseProductIsUnknownBeforeFetch() {
        flags.setEnabledFirm(FIRM, true);
        flags.setEnabled(PRODUCT, false);

        AdmissionGate gate = AdmissionGate.forBackfill(flags, metrics);

        assertThat(gate.admitFirm(FIRM)).isEqualTo(AdmissionGate.Verdict.ADMIT);
        assertThat(metrics.counterNames()).isEmpty();
    }

    @Test
    void admitFirmStillObservesHaltAndFirmGate() {
        AdmissionGate gate = AdmissionGate.forBackfill(flags, metrics);
        assertThat(gate.admitFirm(FIRM)).isEqualTo(AdmissionGate.Verdict.GATED_FIRM);

        flags.setEnabledFirm(FIRM, true);
        flags.setHalt(true);
        assertThat(gate.admitFirm(FIRM)).isEqualTo(AdmissionGate.Verdict.HALTED);

        assertThat(metrics.total("backfill.admit.reject.firm")).isEqualTo(1);
        assertThat(metrics.total("backfill.admit.reject.halt")).isEqualTo(1);
    }

    @Test
    void verdictsAreLiveObservationsNeverCached() {
        flags.setEnabled(PRODUCT, true);
        flags.setEnabledFirm(FIRM, true);
        AdmissionGate gate = new AdmissionGate(flags, metrics);

        assertThat(gate.admit(PRODUCT, FIRM)).isEqualTo(AdmissionGate.Verdict.ADMIT);
        flags.setEnabledFirm(FIRM, false);
        assertThat(gate.admit(PRODUCT, FIRM)).isEqualTo(AdmissionGate.Verdict.GATED_FIRM);
        flags.setEnabledFirm(FIRM, true);
        flags.setHalt(true);
        assertThat(gate.admit(PRODUCT, FIRM)).isEqualTo(AdmissionGate.Verdict.HALTED);
        flags.setHalt(false);
        assertThat(gate.admit(PRODUCT, FIRM)).isEqualTo(AdmissionGate.Verdict.ADMIT);
    }

    @Test
    void eachRejectedAdmissionAttemptCountsOnce() {
        AdmissionGate gate = new AdmissionGate(flags, metrics);

        gate.admit(PRODUCT, FIRM);
        gate.admit(PRODUCT, FIRM);
        gate.admit(PRODUCT, FIRM);

        assertThat(metrics.total("fanout.admit.reject.product")).isEqualTo(3);
    }
}
