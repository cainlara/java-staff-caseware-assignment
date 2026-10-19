package com.example.fanout.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.example.fanout.adapters.inmemory.InMemoryAlertSink;
import com.example.fanout.adapters.inmemory.InMemoryAlertThresholdProvider;
import com.example.fanout.domain.DeadLetterRecord;
import com.example.fanout.domain.DeadLetterUnitKind;
import com.example.fanout.domain.FanOutRunStatus;
import com.example.fanout.fakes.FakeDeadLetterStore;
import com.example.fanout.fakes.InMemoryStructuredLogSink;
import com.example.fanout.ports.StructuredLogSink;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Pins contracts/alert-hooks.md test vectors 1-9: five conditions fire-once /
 * clear-once, freshness boundary, coverage age, DLQ depth, backfill stall +
 * halt exclusion, saturation window, threshold hot-swap, no-data freshness,
 * and identifier confidentiality.
 */
class AlertEvaluatorTest {

    private static final Instant T0 = Instant.parse("2026-10-19T12:00:00Z");

    private final MutableFreshness freshness = new MutableFreshness();
    private final MutableCoverage coverage = new MutableCoverage();
    private final FakeDeadLetterStore deadLetters = new FakeDeadLetterStore();
    private final MutableBackfill backfill = new MutableBackfill();
    private final AtomicBoolean backfillEnabled = new AtomicBoolean();
    private final AtomicBoolean backfillHalted = new AtomicBoolean();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicLong completions = new AtomicLong();
    private final InMemoryAlertSink alerts = new InMemoryAlertSink();
    private final InMemoryAlertThresholdProvider thresholds = new InMemoryAlertThresholdProvider();
    private final InMemoryStructuredLogSink logs = new InMemoryStructuredLogSink();
    private final StructuredLogSink validating = new WhitelistValidatingLogSink(logs);

    private AlertEvaluator newEvaluator() {
        return new AlertEvaluator(
                freshness::get,
                coverage::samples,
                deadLetters,
                backfill::get,
                backfillEnabled::get,
                backfillHalted::get,
                inFlight::get,
                2,
                completions::get,
                thresholds,
                alerts,
                validating);
    }

    // ---- vector 1 + 2: fire once / clear once for all five conditions ----

    @Test
    void everyConditionFiresOnceAndClearsOnce() {
        AlertEvaluator evaluator = newEvaluator();

        freshness.set(new AlertEvaluator.FreshnessSample(
                Duration.ofSeconds(90), "prod-1", "pub-1"));
        coverage.set(List.of(new AlertEvaluator.CoverageSample(
                "pub-1", "prod-1", FanOutRunStatus.RUNNING,
                T0.minus(Duration.ofHours(25)))));
        deadLetters.store(new DeadLetterRecord(
                "pub-1", "prod-1", 0,
                DeadLetterUnitKind.CHUNK_WRITE, 5,
                "java.lang.RuntimeException", "boom", T0));
        backfillEnabled.set(true);
        backfill.set(new AlertEvaluator.BackfillSample(
                T0.minus(Duration.ofMinutes(20)), "firm-0001"));
        inFlight.set(2);

        List<AlertEvaluation> first = evaluator.evaluate(T0);
        assertThat(first).extracting(AlertEvaluation::condition)
                .containsExactlyInAnyOrder(AlertCondition.FRESHNESS_BURN,
                        AlertCondition.COVERAGE_BREACH, AlertCondition.DLQ_NON_EMPTY,
                        AlertCondition.BACKFILL_STALLED);
        assertThat(alerts.fires()).extracting(AlertEvaluation::condition)
                .containsExactlyInAnyOrder(AlertCondition.FRESHNESS_BURN,
                        AlertCondition.COVERAGE_BREACH, AlertCondition.DLQ_NON_EMPTY,
                        AlertCondition.BACKFILL_STALLED);

        Map<AlertCondition, Map<IdentifierField, String>> fireIds = new EnumMap<>(
                AlertCondition.class);
        for (AlertEvaluation evaluation : first) {
            fireIds.put(evaluation.condition(), evaluation.identifiers());
        }
        assertThat(fireIds.get(AlertCondition.FRESHNESS_BURN))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        IdentifierField.product_id, "prod-1",
                        IdentifierField.publish_id, "pub-1"));
        assertThat(fireIds.get(AlertCondition.COVERAGE_BREACH))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        IdentifierField.publish_id, "pub-1",
                        IdentifierField.product_id, "prod-1"));
        assertThat(fireIds.get(AlertCondition.BACKFILL_STALLED))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        IdentifierField.firm_id, "firm-0001"));

        // steady state: still true, no edges
        assertThat(evaluator.evaluate(T0.plus(Duration.ofSeconds(1)))).isEmpty();
        assertThat(alerts.fires()).hasSize(4);
        assertThat(alerts.clears()).isEmpty();

        // clear all
        freshness.clear();
        coverage.set(List.of());
        deadLetters.clear();
        backfillEnabled.set(false);
        List<AlertEvaluation> cleared = evaluator.evaluate(T0.plus(Duration.ofMinutes(30)));
        assertThat(cleared).extracting(AlertEvaluation::condition)
                .containsExactlyInAnyOrder(AlertCondition.FRESHNESS_BURN,
                        AlertCondition.COVERAGE_BREACH, AlertCondition.DLQ_NON_EMPTY,
                        AlertCondition.BACKFILL_STALLED);
        assertThat(cleared).allMatch(e -> !e.active());
        assertThat(evaluator.evaluate(T0.plus(Duration.ofMinutes(31)))).isEmpty();
    }

    @Test
    void saturationFiresOnceAndClearsOnce() {
        AlertEvaluator evaluator = newEvaluator();
        inFlight.set(2);

        evaluator.evaluate(T0);
        evaluator.evaluate(T0.plus(Duration.ofMinutes(1)));
        assertThat(alerts.fires()).isEmpty();
        List<AlertEvaluation> fired = evaluator.evaluate(T0.plus(Duration.ofMinutes(2)));
        assertThat(fired).extracting(AlertEvaluation::condition)
                .containsExactly(AlertCondition.DOWNSTREAM_SATURATION);
        assertThat(fired.getFirst().identifiers())
                .containsExactly(Map.entry(IdentifierField.state, "saturated"));
        assertThat(evaluator.evaluate(T0.plus(Duration.ofMinutes(2)).plusSeconds(1)))
                .isEmpty();

        inFlight.set(0);
        assertThat(evaluator.evaluate(T0.plus(Duration.ofMinutes(3))))
                .extracting(AlertEvaluation::condition)
                .containsExactly(AlertCondition.DOWNSTREAM_SATURATION);
        assertThat(evaluator.evaluate(T0.plus(Duration.ofMinutes(4)))).isEmpty();
    }

    // ---- vector 3: freshness boundary at exactly 60 s ----

    @Test
    void freshnessBoundaryIsInclusiveOfTheLimitAndExcludesNoData() {
        AlertEvaluator evaluator = newEvaluator();

        freshness.set(new AlertEvaluator.FreshnessSample(
                Duration.ofSeconds(60), "prod-1", "pub-1"));
        assertThat(evaluator.evaluate(T0)).isEmpty();

        // nearest-rank over {10s x 39, 90s x 1}: p99 = 90s > 60s
        freshness.set(new AlertEvaluator.FreshnessSample(
                Duration.ofSeconds(90), "prod-1", "pub-1"));
        List<AlertEvaluation> fired = evaluator.evaluate(T0.plus(Duration.ofSeconds(1)));
        assertThat(fired).extracting(AlertEvaluation::condition)
                .containsExactly(AlertCondition.FRESHNESS_BURN);

        freshness.clear();
        assertThat(evaluator.evaluate(T0.plus(Duration.ofSeconds(2))))
                .extracting(AlertEvaluation::condition)
                .containsExactly(AlertCondition.FRESHNESS_BURN);
    }

    // ---- vector 4: coverage only for aged non-COMPLETE runs ----

    @Test
    void coverageFiresOnlyForAgedNonCompleteRuns() {
        AlertEvaluator evaluator = newEvaluator();

        coverage.set(List.of(new AlertEvaluator.CoverageSample(
                "pub-old", "prod-1", FanOutRunStatus.COMPLETE,
                T0.minus(Duration.ofHours(25)))));
        assertThat(evaluator.evaluate(T0)).isEmpty();

        coverage.set(List.of(
                new AlertEvaluator.CoverageSample(
                        "pub-old", "prod-1", FanOutRunStatus.COMPLETE,
                        T0.minus(Duration.ofHours(25))),
                new AlertEvaluator.CoverageSample(
                        "pub-run", "prod-1", FanOutRunStatus.RUNNING,
                        T0.minus(Duration.ofHours(25)))));
        List<AlertEvaluation> fired = evaluator.evaluate(T0.plus(Duration.ofSeconds(1)));
        assertThat(fired).extracting(AlertEvaluation::condition)
                .containsExactly(AlertCondition.COVERAGE_BREACH);
        assertThat(fired.getFirst().identifiers())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        IdentifierField.publish_id, "pub-run",
                        IdentifierField.product_id, "prod-1"));
    }

    // ---- vector 5: DLQ empty = no fire ----

    @Test
    void dlqAlertsOnlyWhenDepthIsNonZero() {
        AlertEvaluator evaluator = newEvaluator();

        assertThat(deadLetters.count()).isZero();
        assertThat(evaluator.evaluate(T0)).isEmpty();

        deadLetters.store(new DeadLetterRecord(
                "pub-1", "prod-1", 0,
                DeadLetterUnitKind.CHUNK_WRITE, 5,
                "java.lang.RuntimeException", "boom", T0));
        assertThat(deadLetters.count()).isEqualTo(1);
        List<AlertEvaluation> fired = evaluator.evaluate(T0.plus(Duration.ofSeconds(1)));
        assertThat(fired).extracting(AlertEvaluation::condition)
                .containsExactly(AlertCondition.DLQ_NON_EMPTY);
    }

    // ---- vector 6: backfill stall + halt exclusion + advance clears ----

    @Test
    void backfillStallRespectsHaltExclusionAndClearsOnAdvance() {
        AlertEvaluator evaluator = newEvaluator();
        backfillEnabled.set(true);
        backfill.set(new AlertEvaluator.BackfillSample(
                T0.minus(Duration.ofMinutes(16)), "firm-0001"));

        List<AlertEvaluation> fired = evaluator.evaluate(T0);
        assertThat(fired).extracting(AlertEvaluation::condition)
                .containsExactly(AlertCondition.BACKFILL_STALLED);

        // halt excludes the stall predicate and clears the active alert
        backfillHalted.set(true);
        assertThat(evaluator.evaluate(T0.plus(Duration.ofSeconds(1))))
                .extracting(AlertEvaluation::condition)
                .containsExactly(AlertCondition.BACKFILL_STALLED);
        assertThat(alerts.clears()).extracting(AlertEvaluation::condition)
                .containsExactly(AlertCondition.BACKFILL_STALLED);

        // empty history while enabled fires only after the limit elapses
        backfillHalted.set(false);
        backfill.clear();
        evaluator.evaluate(T0.plus(Duration.ofMinutes(2)));
        evaluator.evaluate(T0.plus(Duration.ofMinutes(17)));
        assertThat(alerts.fires()).hasSize(1);
        evaluator.evaluate(T0.plus(Duration.ofMinutes(18)));
        assertThat(alerts.fires()).hasSize(2);

        // advance clears
        backfill.set(new AlertEvaluator.BackfillSample(
                T0.plus(Duration.ofMinutes(18)), "firm-0001"));
        assertThat(evaluator.evaluate(T0.plus(Duration.ofMinutes(19))))
                .extracting(AlertEvaluation::condition)
                .containsExactly(AlertCondition.BACKFILL_STALLED);
    }

    // ---- vector 7: saturation pinned window + zero completions ----

    @Test
    void saturationNeedsPinnedWindowAndZeroCompletions() {
        AlertEvaluator evaluator = newEvaluator();
        inFlight.set(2);

        evaluator.evaluate(T0);
        evaluator.evaluate(T0.plus(Duration.ofMinutes(1)));
        assertThat(alerts.fires()).isEmpty();
        evaluator.evaluate(T0.plus(Duration.ofMinutes(2)));
        assertThat(alerts.fires()).extracting(AlertEvaluation::condition)
                .containsExactly(AlertCondition.DOWNSTREAM_SATURATION);
        assertThat(alerts.fires().getFirst().identifiers())
                .containsExactly(Map.entry(IdentifierField.state, "saturated"));

        // recovery clears
        inFlight.set(0);
        assertThat(evaluator.evaluate(T0.plus(Duration.ofMinutes(3))))
                .extracting(AlertEvaluation::condition)
                .containsExactly(AlertCondition.DOWNSTREAM_SATURATION);
    }

    @Test
    void saturationRequiresAtLeastTwoSamplesAndZeroCompletionDelta() {
        AlertEvaluator evaluator = newEvaluator();
        inFlight.set(2);

        // a single tick covering the window is not enough
        evaluator.evaluate(T0);
        evaluator.evaluate(T0.plus(Duration.ofMinutes(5)));
        assertThat(alerts.fires()).extracting(AlertEvaluation::condition)
                .doesNotContain(AlertCondition.DOWNSTREAM_SATURATION);

        // any completion inside the window suppresses the fire
        inFlight.set(2);
        evaluator.evaluate(T0.plus(Duration.ofMinutes(6)));
        completions.incrementAndGet();
        evaluator.evaluate(T0.plus(Duration.ofMinutes(8)));
        assertThat(alerts.fires()).extracting(AlertEvaluation::condition)
                .doesNotContain(AlertCondition.DOWNSTREAM_SATURATION);
    }

    // ---- vector 8: threshold hot-swap mid-test (FR-014) ----

    @Test
    void thresholdHotSwapIsPickedUpWithoutRedeploy() {
        AlertEvaluator evaluator = newEvaluator();
        freshness.set(new AlertEvaluator.FreshnessSample(
                Duration.ofSeconds(45), "prod-1", "pub-1"));

        assertThat(evaluator.evaluate(T0)).isEmpty();

        thresholds.set(new AlertThresholds(
                Duration.ofSeconds(30),
                Duration.ofMinutes(5),
                Duration.ofHours(24),
                Duration.ofMinutes(10),
                Duration.ofMinutes(1),
                Duration.ofMinutes(15),
                Duration.ofMinutes(5),
                Duration.ofMinutes(2)));

        assertThat(evaluator.evaluate(T0.plus(Duration.ofSeconds(1))))
                .extracting(AlertEvaluation::condition)
                .containsExactly(AlertCondition.FRESHNESS_BURN);
    }

    @Test
    void invalidThresholdHotSwapFailsLoudAtConstruction() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AlertThresholds(
                        Duration.ofSeconds(60),
                        Duration.ofMinutes(5),
                        Duration.ofMinutes(30), // < 1 h
                        Duration.ofMinutes(10),
                        Duration.ofMinutes(1),
                        Duration.ofMinutes(15),
                        Duration.ofMinutes(5),
                        Duration.ofMinutes(2)))
                .withMessageContaining("coverageAgeLimit");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AlertThresholds(
                        Duration.ZERO,
                        Duration.ofMinutes(5),
                        Duration.ofHours(24),
                        Duration.ofMinutes(10),
                        Duration.ofMinutes(1),
                        Duration.ofMinutes(15),
                        Duration.ofMinutes(5),
                        Duration.ofMinutes(2)))
                .withMessageContaining("freshnessP99Limit");
    }

    // ---- vector 9: confidentiality of emitted identifiers ----

    @Test
    void emittedIdentifiersAreWhitelistOnlyAndContentFree() {
        AlertEvaluator evaluator = newEvaluator();
        freshness.set(new AlertEvaluator.FreshnessSample(
                Duration.ofSeconds(90), "prod-1", "pub-1"));
        coverage.set(List.of(new AlertEvaluator.CoverageSample(
                "pub-1", "prod-1", FanOutRunStatus.RUNNING,
                T0.minus(Duration.ofHours(25)))));
        deadLetters.store(new DeadLetterRecord(
                "pub-1", "prod-1", 0,
                DeadLetterUnitKind.CHUNK_WRITE, 5,
                "java.lang.RuntimeException", "boom", T0));
        backfillEnabled.set(true);
        backfill.set(new AlertEvaluator.BackfillSample(
                T0.minus(Duration.ofMinutes(20)), "firm-0001"));
        inFlight.set(2);
        evaluator.evaluate(T0);
        evaluator.evaluate(T0.plus(Duration.ofMinutes(1)));
        evaluator.evaluate(T0.plus(Duration.ofMinutes(2)));

        List<AlertEvaluation> all = new ArrayList<>(alerts.fires());
        all.addAll(alerts.clears());
        assertThat(all).isNotEmpty();
        for (AlertEvaluation evaluation : all) {
            for (Map.Entry<IdentifierField, String> entry
                    : evaluation.identifiers().entrySet()) {
                assertThat(IdentifierField.values()).contains(entry.getKey());
                assertThat(entry.getValue())
                        .as("alert %s field %s content-free", evaluation.condition(), entry.getKey())
                        .doesNotContainPattern("\\s{3,}")
                        .doesNotContainPattern("(?i)workpaper|confidential|trial balance");
            }
            assertThat(evaluation.identifiers())
                    .as("alert %s never carries engagement_id", evaluation.condition())
                    .doesNotContainKey(IdentifierField.engagement_id);
        }
        // evaluator log events validate through the whitelist decorator
        assertThat(logs.events()).allMatch(event -> event.event().startsWith("alert."));
    }

    // ---- mutable source fakes ----

    private static final class MutableFreshness {
        private Optional<AlertEvaluator.FreshnessSample> sample = Optional.empty();

        void set(AlertEvaluator.FreshnessSample value) {
            sample = Optional.of(value);
        }

        void clear() {
            sample = Optional.empty();
        }

        Optional<AlertEvaluator.FreshnessSample> get() {
            return sample;
        }
    }

    private static final class MutableCoverage {
        private List<AlertEvaluator.CoverageSample> samples = List.of();

        void set(List<AlertEvaluator.CoverageSample> value) {
            samples = List.copyOf(value);
        }

        List<AlertEvaluator.CoverageSample> samples() {
            return samples;
        }
    }

    private static final class MutableBackfill {
        private Optional<AlertEvaluator.BackfillSample> sample = Optional.empty();

        void set(AlertEvaluator.BackfillSample value) {
            sample = Optional.of(value);
        }

        void clear() {
            sample = Optional.empty();
        }

        Optional<AlertEvaluator.BackfillSample> get() {
            return sample;
        }
    }
}
