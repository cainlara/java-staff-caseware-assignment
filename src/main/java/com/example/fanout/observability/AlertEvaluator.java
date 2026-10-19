package com.example.fanout.observability;

import com.example.fanout.domain.FanOutRunStatus;
import com.example.fanout.ports.AlertSink;
import com.example.fanout.ports.AlertThresholdProvider;
import com.example.fanout.ports.DeadLetterStore;
import com.example.fanout.ports.StructuredLogSink;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * Edge-triggered alert evaluator (spec FR-006/FR-007, contracts/alert-hooks.md).
 * Pure and clock-injected: no threads, no timers — a runtime shell ticks
 * {@link #evaluate}; tests call directly.
 *
 * <p>Keeps an in-memory active set: each call returns FIRE edges for
 * newly-true conditions and CLEAR edges for newly-false ones; steady state
 * returns nothing. Restart resets the set (next true condition re-fires —
 * safe). Intentionally halted backfill never satisfies the stall predicate.
 * Saturation requires two consecutive consistent samples inside the window,
 * spanning the window start, pinned at capacity with a zero completion
 * delta.
 */
public final class AlertEvaluator {

    /** Worst-sample freshness observation; empty = no ring data. */
    public record FreshnessSample(Duration p99, String productId, String publishId) {
    }

    /** One run for the coverage predicate. */
    public record CoverageSample(String publishId, String productId, FanOutRunStatus status,
                                 Instant publishedAt) {
    }

    /** Newest backfill checkpoint observation. */
    public record BackfillSample(Instant updatedAt, String firmId) {
    }

    @FunctionalInterface
    public interface FreshnessSource {
        Optional<FreshnessSample> freshness();
    }

    @FunctionalInterface
    public interface CoverageSource {
        List<CoverageSample> coverage();
    }

    @FunctionalInterface
    public interface BackfillSource {
        Optional<BackfillSample> newestCheckpoint();
    }

    private record SaturationSample(Instant at, int inFlight, long completions) {
    }

    private final FreshnessSource freshness;
    private final CoverageSource coverage;
    private final DeadLetterStore deadLetters;
    private final BackfillSource backfill;
    private final BooleanSupplier backfillEnabled;
    private final BooleanSupplier backfillHalted;
    private final IntSupplier downstreamInFlight;
    private final int saturationCapacity;
    private final LongSupplier downstreamCompletions;
    private final AlertThresholdProvider thresholds;
    private final AlertSink alerts;
    private final StructuredLogSink logSink;

    private final EnumSet<AlertCondition> active = EnumSet.noneOf(AlertCondition.class);
    private final Deque<SaturationSample> saturationSamples = new ArrayDeque<>();
    private Instant backfillEmptySince;

    public AlertEvaluator(
            FreshnessSource freshness,
            CoverageSource coverage,
            DeadLetterStore deadLetters,
            BackfillSource backfill,
            BooleanSupplier backfillEnabled,
            BooleanSupplier backfillHalted,
            IntSupplier downstreamInFlight,
            int saturationCapacity,
            LongSupplier downstreamCompletions,
            AlertThresholdProvider thresholds,
            AlertSink alerts) {
        this(freshness, coverage, deadLetters, backfill, backfillEnabled, backfillHalted,
                downstreamInFlight, saturationCapacity, downstreamCompletions, thresholds,
                alerts, new NoOpStructuredLogSink());
    }

    /** Log-injecting constructor (spec 005, FR-004): alert.fired/cleared events. */
    public AlertEvaluator(
            FreshnessSource freshness,
            CoverageSource coverage,
            DeadLetterStore deadLetters,
            BackfillSource backfill,
            BooleanSupplier backfillEnabled,
            BooleanSupplier backfillHalted,
            IntSupplier downstreamInFlight,
            int saturationCapacity,
            LongSupplier downstreamCompletions,
            AlertThresholdProvider thresholds,
            AlertSink alerts,
            StructuredLogSink logSink) {
        this.freshness = Objects.requireNonNull(freshness, "freshness must not be null");
        this.coverage = Objects.requireNonNull(coverage, "coverage must not be null");
        this.deadLetters = Objects.requireNonNull(deadLetters, "deadLetters must not be null");
        this.backfill = Objects.requireNonNull(backfill, "backfill must not be null");
        this.backfillEnabled = Objects.requireNonNull(backfillEnabled, "backfillEnabled must not be null");
        this.backfillHalted = Objects.requireNonNull(backfillHalted, "backfillHalted must not be null");
        this.downstreamInFlight = Objects.requireNonNull(downstreamInFlight,
                "downstreamInFlight must not be null");
        if (saturationCapacity < 1) {
            throw new IllegalArgumentException("saturationCapacity must be >= 1");
        }
        this.saturationCapacity = saturationCapacity;
        this.downstreamCompletions = Objects.requireNonNull(downstreamCompletions,
                "downstreamCompletions must not be null");
        this.thresholds = Objects.requireNonNull(thresholds, "thresholds must not be null");
        this.alerts = Objects.requireNonNull(alerts, "alerts must not be null");
        this.logSink = Objects.requireNonNull(logSink, "logSink must not be null");
    }

    /**
     * Evaluate all five conditions at {@code now} and emit the edge
     * transitions (fire/clear) to the sink and log pipeline.
     *
     * @return the edges emitted by this call, in condition order; empty at
     *         steady state
     */
    public List<AlertEvaluation> evaluate(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        AlertThresholds limits = thresholds.current();
        List<AlertEvaluation> edges = new ArrayList<>();

        evaluateFreshness(now, limits, edges);
        evaluateCoverage(now, limits, edges);
        evaluateDlq(now, edges);
        evaluateBackfill(now, limits, edges);
        evaluateSaturation(now, limits, edges);

        return List.copyOf(edges);
    }

    // ---- conditions ----

    private void evaluateFreshness(Instant now, AlertThresholds limits,
                                   List<AlertEvaluation> edges) {
        Optional<FreshnessSample> worst = freshness.freshness();
        boolean activeNow = worst
                .map(sample -> sample.p99().compareTo(limits.freshnessP99Limit()) > 0)
                .orElse(false);
        Map<IdentifierField, String> identifiers = worst
                .map(sample -> identifiers(IdentifierField.product_id, sample.productId(),
                        IdentifierField.publish_id, sample.publishId()))
                .orElse(Map.of());
        transition(AlertCondition.FRESHNESS_BURN, activeNow, now, identifiers, edges);
    }

    private void evaluateCoverage(Instant now, AlertThresholds limits,
                                  List<AlertEvaluation> edges) {
        Optional<CoverageSample> worst = coverage.coverage().stream()
                .filter(sample -> sample.status() != FanOutRunStatus.COMPLETE)
                .filter(sample -> Duration.between(sample.publishedAt(), now)
                        .compareTo(limits.coverageAgeLimit()) > 0)
                .max(Comparator.comparingLong(
                        sample -> Duration.between(sample.publishedAt(), now).toNanos()));
        Map<IdentifierField, String> identifiers = worst
                .map(sample -> identifiers(IdentifierField.publish_id, sample.publishId(),
                        IdentifierField.product_id, sample.productId()))
                .orElse(Map.of());
        transition(AlertCondition.COVERAGE_BREACH, worst.isPresent(), now, identifiers, edges);
    }

    private void evaluateDlq(Instant now, List<AlertEvaluation> edges) {
        // depth and newest-record ids are carried by the ops sink adapter via
        // the store read (contracts/alert-hooks.md); the edge itself is bare
        transition(AlertCondition.DLQ_NON_EMPTY, deadLetters.count() > 0, now, Map.of(), edges);
    }

    private void evaluateBackfill(Instant now, AlertThresholds limits,
                                  List<AlertEvaluation> edges) {
        boolean enabled = backfillEnabled.getAsBoolean();
        boolean halted = backfillHalted.getAsBoolean();
        Optional<BackfillSample> newest = backfill.newestCheckpoint();
        boolean activeNow;
        Map<IdentifierField, String> identifiers = Map.of();
        if (!enabled || halted) {
            backfillEmptySince = null;
            activeNow = false;
        } else if (newest.isPresent()) {
            backfillEmptySince = null;
            activeNow = Duration.between(newest.get().updatedAt(), now)
                    .compareTo(limits.backfillStallLimit()) > 0;
            if (activeNow && newest.get().firmId() != null && !newest.get().firmId().isBlank()) {
                identifiers = identifiers(IdentifierField.firm_id, newest.get().firmId());
            }
        } else {
            if (backfillEmptySince == null) {
                backfillEmptySince = now;
            }
            activeNow = Duration.between(backfillEmptySince, now)
                    .compareTo(limits.backfillStallLimit()) > 0;
        }
        transition(AlertCondition.BACKFILL_STALLED, activeNow, now, identifiers, edges);
    }

    private void evaluateSaturation(Instant now, AlertThresholds limits,
                                    List<AlertEvaluation> edges) {
        saturationSamples.addLast(new SaturationSample(now, downstreamInFlight.getAsInt(),
                downstreamCompletions.getAsLong()));
        Instant windowStart = now.minus(limits.saturationWindow());
        while (saturationSamples.size() >= 2
                && saturationSamples.stream().skip(1).findFirst().orElseThrow().at()
                        .isBefore(windowStart)) {
            saturationSamples.removeFirst();
        }
        List<SaturationSample> inWindow = saturationSamples.stream()
                .filter(sample -> !sample.at().isBefore(windowStart))
                .toList();
        boolean allPinned = inWindow.stream()
                .allMatch(sample -> sample.inFlight() == saturationCapacity)
                && downstreamCompletions.getAsLong() == inWindow.getFirst().completions();
        // activation needs the window spanned by >= 2 consistent samples;
        // once active, any pinned+zero-delta window keeps it (no flapping on
        // tick jitter)
        boolean activeNow = active.contains(AlertCondition.DOWNSTREAM_SATURATION)
                ? allPinned
                : allPinned && inWindow.size() >= 2
                        && !inWindow.getFirst().at().isAfter(windowStart);
        transition(AlertCondition.DOWNSTREAM_SATURATION, activeNow, now,
                identifiers(IdentifierField.state, "saturated"), edges);
    }

    // ---- edge emission ----

    private void transition(AlertCondition condition, boolean activeNow, Instant at,
                            Map<IdentifierField, String> identifiers,
                            List<AlertEvaluation> edges) {
        boolean wasActive = active.contains(condition);
        if (activeNow && !wasActive) {
            active.add(condition);
            alerts.fire(condition, at, identifiers);
            logEdge("fired", condition, at, identifiers);
            edges.add(new AlertEvaluation(condition, true, at, identifiers));
        } else if (!activeNow && wasActive) {
            active.remove(condition);
            alerts.clear(condition, at, identifiers);
            logEdge("cleared", condition, at, identifiers);
            edges.add(new AlertEvaluation(condition, false, at, identifiers));
        }
    }

    private void logEdge(String edge, AlertCondition condition, Instant at,
                         Map<IdentifierField, String> identifiers) {
        logSink.log(LogLevel.INFO,
                "alert." + edge + "." + condition.name().toLowerCase(Locale.ROOT),
                at, identifiers);
    }

    private static Map<IdentifierField, String> identifiers(
            IdentifierField first, String firstValue, IdentifierField second, String secondValue) {
        Map<IdentifierField, String> map = new EnumMap<>(IdentifierField.class);
        map.put(first, firstValue);
        map.put(second, secondValue);
        return map;
    }

    private static Map<IdentifierField, String> identifiers(IdentifierField field, String value) {
        Map<IdentifierField, String> map = new EnumMap<>(IdentifierField.class);
        map.put(field, value);
        return map;
    }
}
