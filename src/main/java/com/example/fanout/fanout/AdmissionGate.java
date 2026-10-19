package com.example.fanout.fanout;

import com.example.fanout.ports.FanOutMetrics;
import com.example.fanout.ports.FeatureFlags;
import java.util.Objects;

/**
 * Halt and staged-enablement admission seam (spec B6/B9, research.md D8,
 * contracts/admission-gates.md): one pure observation of live flag state at an
 * admission decision.
 *
 * <p>Verdict precedence (normative): {@code haltSwitch()} true
 * {@code => HALTED}; else product flag false {@code => GATED_PRODUCT}; else
 * firm flag false {@code => GATED_FIRM}; else {@code => ADMIT}. The gate stores
 * nothing — every call re-reads the flags, so a flip is visible at the very
 * next admission decision without any deploy.
 *
 * <p>Every non-ADMIT verdict increments the matching
 * {@code {fanout|backfill}.admit.reject.{halt,product,firm}} counter exactly
 * once per rejected admission attempt, tagged with identifiers only.
 *
 * <p>The {@link #forBackfill} variant routes the same counters to the
 * {@code backfill.*} names so both paths stay independently observable.
 */
public final class AdmissionGate {

    public enum Verdict {
        ADMIT,
        GATED_PRODUCT,
        GATED_FIRM,
        HALTED
    }

    private final FeatureFlags flags;
    private final FanOutMetrics metrics;
    private final String side;

    public AdmissionGate(FeatureFlags flags, FanOutMetrics metrics) {
        this(flags, metrics, "fanout");
    }

    private AdmissionGate(FeatureFlags flags, FanOutMetrics metrics, String side) {
        this.flags = Objects.requireNonNull(flags, "flags must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        this.side = Objects.requireNonNull(side, "side must not be null");
    }

    /**
     * Gate that admits everything (halt off, every product and firm enabled):
     * the delivered default wiring for callers that have no flag source yet.
     */
    public static AdmissionGate admitAll(FanOutMetrics metrics) {
        Objects.requireNonNull(metrics, "metrics must not be null");
        return new AdmissionGate(new FeatureFlags() {
            @Override
            public boolean enabled(String productId) {
                return true;
            }

            @Override
            public boolean enabledFirm(String firmId) {
                return true;
            }

            @Override
            public boolean haltSwitch() {
                return false;
            }
        }, metrics);
    }

    /** Backfill-path gate: identical precedence, {@code backfill.*} counters. */
    public static AdmissionGate forBackfill(FeatureFlags flags, FanOutMetrics metrics) {
        return new AdmissionGate(flags, metrics, "backfill");
    }

    /** Full admission decision for one (product, firm) pair. */
    public Verdict admit(String productId, String firmId) {
        Objects.requireNonNull(productId, "productId must not be null");
        Objects.requireNonNull(firmId, "firmId must not be null");
        Verdict verdict = verdict(productId, firmId);
        if (verdict != Verdict.ADMIT) {
            reject(verdict, productId, firmId);
        }
        return verdict;
    }

    /**
     * Firm-level admission used by the backfill batch pick, before the batch is
     * fetched and the product ids are therefore still unknown: halt and the
     * per-firm gate apply; the per-product gate is re-checked once the batch
     * (or each engagement) supplies its product id.
     */
    public Verdict admitFirm(String firmId) {
        Objects.requireNonNull(firmId, "firmId must not be null");
        Verdict verdict;
        if (flags.haltSwitch()) {
            verdict = Verdict.HALTED;
        } else if (!flags.enabledFirm(firmId)) {
            verdict = Verdict.GATED_FIRM;
        } else {
            verdict = Verdict.ADMIT;
        }
        if (verdict != Verdict.ADMIT) {
            reject(verdict, null, firmId);
        }
        return verdict;
    }

    /** Live halt observation for run-level admission checks. */
    public boolean isHalted() {
        return flags.haltSwitch();
    }

    private Verdict verdict(String productId, String firmId) {
        if (flags.haltSwitch()) {
            return Verdict.HALTED;
        }
        if (!flags.enabled(productId)) {
            return Verdict.GATED_PRODUCT;
        }
        if (!flags.enabledFirm(firmId)) {
            return Verdict.GATED_FIRM;
        }
        return Verdict.ADMIT;
    }

    private void reject(Verdict verdict, String productId, String firmId) {
        String reason = switch (verdict) {
            case HALTED -> "halt";
            case GATED_PRODUCT -> "product";
            case GATED_FIRM -> "firm";
            case ADMIT -> throw new IllegalStateException("ADMIT is never a rejection");
        };
        if (productId == null) {
            metrics.increment(side + ".admit.reject." + reason, 1, "firm_id", firmId);
            return;
        }
        metrics.increment(side + ".admit.reject." + reason, 1,
                "product_id", productId, "firm_id", firmId);
    }
}
