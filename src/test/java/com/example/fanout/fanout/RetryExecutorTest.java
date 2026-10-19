package com.example.fanout.fanout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.fanout.domain.DeadLetterUnitKind;
import com.example.fanout.domain.FanOutConfig;
import com.example.fanout.domain.FanOutOutcomeStatus;
import com.example.fanout.domain.PendingIndexRow;
import com.example.fanout.domain.PendingIndexState;
import com.example.fanout.fakes.SeededJitter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * US4 retry bounds (spec SC-006/SC-007, FR-040/FR-041/FR-042/FR-043,
 * research.md D8): attempts within the configured budget, exponential delays
 * capped at max and inside the computed jitter bounds under FakeClock +
 * scripted/seeded jitter, exhaustion stores a full-identity dead-letter with
 * fanout.dlq incremented, engine-level stream/writer exhaustion ends
 * FAILED_DEAD_LETTERED and a replayed unit converges to the
 * first-attempt-success state. Zero sleep-based synchronization (FR-072).
 */
class RetryExecutorTest {

    private FanOutFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new FanOutFixture();
    }

    @AfterEach
    void hotPathInvariants() {
        fixture.assertHotPathInvariants();
    }

    @Test
    void retriesStayWithinBudgetAndDelaysMatchExponentialJitterBounds() {
        List<Duration> delays = new ArrayList<>();
        RetryExecutor executor = executorWith(scriptedJitter(0.25, 0.75), delays);
        AtomicInteger calls = new AtomicInteger();
        Supplier<String> unit = () -> {
            if (calls.incrementAndGet() <= 3) {
                throw new TransientFanOutException("injected unit failure");
            }
            return "ok";
        };

        String result = executor.callWithRetry("pub-9", "prod-9", 0,
                DeadLetterUnitKind.CHUNK_WRITE, unit);

        assertThat(result).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(4); // 1 + 3 retries, budget 5 not reached
        assertThat(delays).containsExactly(
                Duration.ofMillis(75),   // 100 ms * factor 0.75
                Duration.ofMillis(250),  // 200 ms * factor 1.25
                Duration.ofMillis(300)); // 400 ms * factor 0.75
        // every delay inside the computed jitter bound [0.5, 1.5) x capped base
        Duration base = fixture.config.retryBaseDelay();
        for (int i = 0; i < delays.size(); i++) {
            Duration uncapped = uncappedDelay(base, i);
            Duration cap = uncapped.compareTo(fixture.config.retryMaxDelay()) > 0
                    ? fixture.config.retryMaxDelay() : uncapped;
            assertThat(delays.get(i).toNanos())
                    .isBetween(Math.round(cap.toNanos() * 0.5),
                            Math.round(cap.toNanos() * 1.5) - 1);
        }
        assertThat(fixture.clock.instant())
                .isEqualTo(java.time.Instant.parse("2026-10-18T12:00:00Z")
                        .plus(Duration.ofMillis(625))); // 75 + 250 + 300
        assertThat(fixture.metrics.total("fanout.retry")).isEqualTo(3);
        assertThat(fixture.metrics.total("fanout.dlq")).isZero();
        assertThat(fixture.deadLetters.count()).isZero();
    }

    @Test
    void delaysGrowExponentiallyAndCapAtMaxDelay() {
        FanOutConfig config = new FanOutConfig(500, 16, 2, 5,
                Duration.ofSeconds(10), Duration.ofSeconds(30), 0.5, 1.5);
        List<Duration> delays = new ArrayList<>();
        var jitter = scriptedJitter(0.5); // factor exactly 1.0
        RetryExecutor executor = new RetryExecutor(config,
                delay -> delays.add(delay), fixture.clock, jitter,
                fixture.metrics, fixture.deadLetters);
        AtomicInteger calls = new AtomicInteger();
        Supplier<String> unit = () -> {
            if (calls.incrementAndGet() <= 3) {
                throw new TransientFanOutException("injected unit failure");
            }
            return "ok";
        };

        assertThat(executor.callWithRetry("pub-9", "prod-9", 0,
                DeadLetterUnitKind.CHUNK_WRITE, unit)).isEqualTo("ok");
        assertThat(delays).containsExactly(
                Duration.ofSeconds(10), // base
                Duration.ofSeconds(20), // base * 2
                Duration.ofSeconds(30)); // base * 4 capped at maxDelay
    }

    @Test
    void exhaustionStoresFullIdentityDeadLetterAndIncrementsAlert() {
        List<Duration> delays = new ArrayList<>();
        RetryExecutor executor = executorWith(new SeededJitter(42L), delays);
        Supplier<String> unit = () -> {
            throw new TransientFanOutException("boom-42");
        };

        assertThatThrownBy(() -> executor.callWithRetry("pub-7", "prod-7", 3,
                DeadLetterUnitKind.CHUNK_WRITE, unit))
                .isInstanceOf(FanOutExhaustedException.class)
                .satisfies(thrown -> {
                    var record = ((FanOutExhaustedException) thrown).record();
                    assertThat(record.eventId()).isEqualTo("pub-7");
                    assertThat(record.productId()).isEqualTo("prod-7");
                    assertThat(record.chunkIndex()).isEqualTo(3);
                    assertThat(record.unitKind()).isEqualTo(DeadLetterUnitKind.CHUNK_WRITE);
                    assertThat(record.attemptCount()).isEqualTo(5);
                    assertThat(record.lastFailureType())
                            .isEqualTo(TransientFanOutException.class.getName());
                    assertThat(record.lastFailureMessage()).isEqualTo("boom-42");
                    assertThat(record.recordedAt()).isEqualTo(fixture.clock.instant());
                });

        assertThat(fixture.deadLetters.byEvent("pub-7")).hasSize(1);
        assertThat(fixture.deadLetters.byEvent("pub-7").getFirst().unitKind())
                .isEqualTo(DeadLetterUnitKind.CHUNK_WRITE);
        assertThat(fixture.metrics.total("fanout.dlq")).isEqualTo(1);
        assertThat(fixture.metrics.total("fanout.retry")).isEqualTo(4);
        assertThat(delays).hasSize(4);
    }

    @Test
    void exhaustionOfEligibilityStreamUnitKeepsItsIdentity() {
        RetryExecutor executor = executorWith(new SeededJitter(42L), new ArrayList<>());

        assertThatThrownBy(() -> executor.callWithRetry("pub-8", "prod-8", -1,
                DeadLetterUnitKind.ELIGIBILITY_STREAM,
                () -> {
                    throw new TransientFanOutException("stream down");
                }))
                .isInstanceOf(FanOutExhaustedException.class);

        var record = fixture.deadLetters.byEvent("pub-8").getFirst();
        assertThat(record.unitKind()).isEqualTo(DeadLetterUnitKind.ELIGIBILITY_STREAM);
        assertThat(record.chunkIndex()).isEqualTo(-1);
        assertThat(record.attemptCount()).isEqualTo(5);
    }

    @Test
    void nonTransientFailuresPropagateWithoutRetryOrDeadLetter() {
        List<Duration> delays = new ArrayList<>();
        RetryExecutor executor = executorWith(new SeededJitter(42L), delays);

        assertThatThrownBy(() -> executor.callWithRetry("pub-9", "prod-9", 0,
                DeadLetterUnitKind.CHUNK_WRITE,
                () -> {
                    throw new IllegalStateException("programming error");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("programming error");

        assertThat(delays).isEmpty();
        assertThat(fixture.metrics.total("fanout.retry")).isZero();
        assertThat(fixture.deadLetters.count()).isZero();
    }

    @Test
    void streamExhaustionEndsFailedDeadLetteredAndReplayConverges() {
        fixture.seedEligible(25, 40);
        fixture.seedPublish(FanOutFixture.PUBLISH, FanOutFixture.TO_VERSION);
        fixture.eligibility.failNextStream(99);

        var exhausted = fixture.newEngine()
                .runPublish(fixture.publishTrigger(FanOutFixture.PUBLISH));

        assertThat(exhausted.status()).isEqualTo(FanOutOutcomeStatus.FAILED_DEAD_LETTERED);
        assertThat(exhausted.deadLetterCount()).isEqualTo(1);
        assertThat(exhausted.rowsResolved()).isZero();
        assertThat(fixture.writer.writeCount()).isZero();
        var record = fixture.deadLetters.byEvent(FanOutFixture.PUBLISH).getFirst();
        assertThat(record.unitKind()).isEqualTo(DeadLetterUnitKind.ELIGIBILITY_STREAM);
        assertThat(record.attemptCount()).isEqualTo(5);
        assertThat(fixture.metrics.total("fanout.dlq")).isEqualTo(1);
        assertThat(fixture.metrics.total("fanout.retry")).isEqualTo(4);
        assertThat(fixture.cursors.readRun(
                com.example.fanout.domain.FanOutRunKind.PUBLISH,
                FanOutFixture.PUBLISH, FanOutFixture.PRODUCT)).isEmpty();

        // failure window over: the replayed run converges (FR-043)
        fixture.eligibility.failNextStream(0);
        var replayed = fixture.newEngine()
                .runPublish(fixture.publishTrigger(FanOutFixture.PUBLISH));
        assertThat(replayed.status()).isEqualTo(FanOutOutcomeStatus.COMPLETED);
        assertThat(replayed.rowsResolved()).isEqualTo(1_000);
        assertThat(fixture.metrics.total("fanout.dlq")).isEqualTo(1); // no new alerts

        FanOutFixture reference = new FanOutFixture();
        reference.seedEligible(25, 40);
        reference.seedPublish(FanOutFixture.PUBLISH, FanOutFixture.TO_VERSION);
        assertThat(reference.newEngine()
                .runPublish(reference.publishTrigger(FanOutFixture.PUBLISH)).status())
                .isEqualTo(FanOutOutcomeStatus.COMPLETED);
        for (int f = 0; f < 25; f++) {
            for (int e = 0; e < 40; e++) {
                var ref = FanOutFixture.ref(f, e);
                var row = fixture.index.find(ref.engagementId(), FanOutFixture.TO_VERSION,
                        PendingIndexState.PENDING);
                var expected = reference.index.find(ref.engagementId(),
                        FanOutFixture.TO_VERSION, PendingIndexState.PENDING);
                assertThat(row).isPresent();
                assertThat(withoutUpdatedAt(row.get()))
                        .isEqualTo(withoutUpdatedAt(expected.orElseThrow()));
            }
        }
        assertThat(fixture.downstream.getCallCount()).isZero();
    }

    @Test
    void transientWriterFailuresRetryAndConvergeToReferenceState() {
        fixture.seedEligible(25, 40);
        fixture.seedPublish(FanOutFixture.PUBLISH, FanOutFixture.TO_VERSION);
        fixture.writer.failTimes(3);

        var outcome = fixture.newEngine()
                .runPublish(fixture.publishTrigger(FanOutFixture.PUBLISH));

        assertThat(outcome.status()).isEqualTo(FanOutOutcomeStatus.COMPLETED);
        assertThat(outcome.rowsResolved()).isEqualTo(1_000);
        assertThat(outcome.deadLetterCount()).isZero();
        assertThat(fixture.metrics.total("fanout.retry")).isEqualTo(3);
        assertThat(fixture.metrics.total("fanout.dlq")).isZero();

        FanOutFixture reference = new FanOutFixture();
        reference.seedEligible(25, 40);
        reference.seedPublish(FanOutFixture.PUBLISH, FanOutFixture.TO_VERSION);
        assertThat(reference.newEngine()
                .runPublish(reference.publishTrigger(FanOutFixture.PUBLISH)).status())
                .isEqualTo(FanOutOutcomeStatus.COMPLETED);
        for (int f = 0; f < 25; f++) {
            for (int e = 0; e < 40; e++) {
                var ref = FanOutFixture.ref(f, e);
                var row = fixture.index.find(ref.engagementId(), FanOutFixture.TO_VERSION,
                        PendingIndexState.PENDING);
                var expected = reference.index.find(ref.engagementId(),
                        FanOutFixture.TO_VERSION, PendingIndexState.PENDING);
                assertThat(row).isPresent();
                assertThat(withoutUpdatedAt(row.get()))
                        .isEqualTo(withoutUpdatedAt(expected.orElseThrow()));
                assertThat(row.get().rowVersion()).isEqualTo(1);
            }
        }
        assertThat(fixture.downstream.getCallCount()).isZero();
    }

    // ---- helpers ----

    private static PendingIndexRow withoutUpdatedAt(PendingIndexRow row) {
        return new PendingIndexRow(row.engagementId(), row.firmId(), row.productId(),
                row.currentVersion(), row.offeredVersion(), row.state(), row.decision(),
                row.pinnedRanges(), row.summaryKey(), row.publishIds(), row.rowVersion(),
                java.time.Instant.EPOCH);
    }

    private RetryExecutor executorWith(JitterSource jitter, List<Duration> delays) {
        BackoffWaiter waiter = delay -> {
            delays.add(delay);
            fixture.clock.advance(delay);
        };
        return new RetryExecutor(fixture.config, waiter, fixture.clock, jitter,
                fixture.metrics, fixture.deadLetters);
    }

    private static JitterSource scriptedJitter(double... values) {
        return new SeededJitter(values);
    }

    private static Duration uncappedDelay(Duration base, int retryIndex) {
        return base.multipliedBy(1L << retryIndex);
    }
}
