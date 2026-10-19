package com.example.fanout.fanout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.example.fanout.domain.EngagementRef;
import com.example.fanout.domain.FanOutOutcomeStatus;
import com.example.fanout.domain.FanOutRunKind;
import com.example.fanout.domain.FanOutRunStatus;
import com.example.fanout.domain.PendingIndexRow;
import com.example.fanout.domain.PendingIndexState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * US5 withdraw supersede (spec SC-008, FR-060…FR-063, R7, research.md D7):
 * rows offering withdrawn version V re-offer the next eligible non-withdrawn
 * version chosen by phase-2 (publishedAt, publishId) ordering - never by
 * version-string arithmetic - or return to CREATED when none exists, with
 * decision/pinnedRanges/summaryKey preserved byte-identical; same
 * chunk/fairness/checkpoint/retry machinery; crashed supersede resumes per
 * cursor rules; duplicate withdraw idempotent via marker append-if-absent;
 * zero downstream calls in every test (spec FR-032, no sleeps FR-072).
 */
class WithdrawSupersedeTest {

    private static final Instant T_10 = Instant.parse("2026-10-18T10:00:00Z");
    private static final Instant T_11 = Instant.parse("2026-10-18T11:00:00Z");
    private static final Instant T_12 = Instant.parse("2026-10-18T12:00:00Z");

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
    void withdrawMovesOfferingRowsToNextVersionSelectedByTimeNotVersionString() {
        // candidates after withdrawing v1: v9 @11:00 and v10 @12:00.
        // version-string max would be "v9"; phase-2 ordering picks "v10".
        fixture.seedPublish("pub-1", FanOutFixture.PRODUCT, "v1", T_10);
        fixture.seedPublish("pub-9", FanOutFixture.PRODUCT, "v9", T_11);
        fixture.seedPublish("pub-10", FanOutFixture.PRODUCT, "v10", T_12);
        List<EngagementRef> refs = fixture.seedEligible(25, 40);
        fixture.eligibility.seedOffering(FanOutFixture.PRODUCT, "v1", refs);
        for (EngagementRef ref : refs) {
            seedHistory(ref, "v1");
        }

        var outcome = fixture.newEngine()
                .runWithdrawSupersede(fixture.withdrawTrigger("wd-1", "pub-1"));

        assertThat(outcome.status()).isEqualTo(FanOutOutcomeStatus.COMPLETED);
        assertThat(outcome.chunkCount()).isEqualTo(2); // same chunk machinery (FR-062)
        assertThat(outcome.rowsResolved()).isEqualTo(1_000);
        assertThat(outcome.deadLetterCount()).isZero();

        for (EngagementRef ref : refs) {
            var target = fixture.index.find(ref.engagementId(), "v10", PendingIndexState.PENDING);
            assertThat(target).isPresent();
            assertHistoryPreserved(target.get());
            assertThat(target.get().publishIds())
                    .containsExactlyInAnyOrder("pub-1", "wd-1");
            assertThat(target.get().rowVersion()).isEqualTo(1);

            // the withdrawn-version row stays untouched (no delete port)
            var withdrawn = fixture.index.find(ref.engagementId(), "v1", PendingIndexState.PENDING);
            assertThat(withdrawn).isPresent();
            assertHistoryPreserved(withdrawn.get());
        }

        var cursor = fixture.cursors.readRun(FanOutRunKind.WITHDRAW_SUPERSEDE,
                "wd-1", FanOutFixture.PRODUCT).orElseThrow();
        assertThat(cursor.status()).isEqualTo(FanOutRunStatus.COMPLETE);
        assertThat(cursor.totalChunks()).isEqualTo(2);
        assertThat(fixture.cursors.withdrawnVersions(FanOutFixture.PRODUCT))
                .containsExactly("v1"); // marker recorded before work (D7)
        assertThat(fixture.downstream.getCallCount()).isZero();
    }

    @Test
    void equalPublishedAtTieBreaksOnPublishIdNotVersionString() {
        // vX publishes "v9" and pub-y publishes "v8" at the same instant:
        // id order (pub-y wins) selects "v8"; version-string max would be "v9".
        fixture.seedPublish("pub-1", FanOutFixture.PRODUCT, "v1", T_10);
        fixture.seedPublish("pub-x", FanOutFixture.PRODUCT, "v9", T_11);
        fixture.seedPublish("pub-y", FanOutFixture.PRODUCT, "v8", T_11);
        EngagementRef ref = FanOutFixture.ref(0, 0);
        fixture.eligibility.seedOffering(
                FanOutFixture.PRODUCT, "v1", List.of(ref));
        seedHistory(ref, "v1");

        var outcome = fixture.newEngine()
                .runWithdrawSupersede(fixture.withdrawTrigger("wd-1", "pub-1"));

        assertThat(outcome.status()).isEqualTo(FanOutOutcomeStatus.COMPLETED);
        var target = fixture.index.find(ref.engagementId(), "v8", PendingIndexState.PENDING);
        assertThat(target).isPresent();
        assertHistoryPreserved(target.get());
        assertThat(fixture.index.find(ref.engagementId(), "v9", PendingIndexState.PENDING))
                .isEmpty();
        assertThat(fixture.downstream.getCallCount()).isZero();
    }

    @Test
    void withdrawWithoutNextVersionReturnsRowsToCreatedPreservingHistory() {
        fixture.seedPublish("pub-1", FanOutFixture.PRODUCT, "v1", T_10);
        List<EngagementRef> refs = fixture.seedEligible(5, 4);
        fixture.eligibility.seedOffering(FanOutFixture.PRODUCT, "v1", refs);
        for (EngagementRef ref : refs) {
            seedHistory(ref, "v1");
        }

        var outcome = fixture.newEngine()
                .runWithdrawSupersede(fixture.withdrawTrigger("wd-1", "pub-1"));

        assertThat(outcome.status()).isEqualTo(FanOutOutcomeStatus.COMPLETED);
        assertThat(outcome.rowsResolved()).isEqualTo(20);
        for (EngagementRef ref : refs) {
            var created = fixture.index.find(ref.engagementId(), "v1", PendingIndexState.CREATED);
            assertThat(created).isPresent();
            assertThat(created.get().state()).isEqualTo(PendingIndexState.CREATED);
            assertHistoryPreserved(created.get());
            assertThat(created.get().publishIds())
                    .containsExactlyInAnyOrder("pub-1", "wd-1");
            // original PENDING row is untouched
            var original = fixture.index.find(ref.engagementId(), "v1", PendingIndexState.PENDING);
            assertThat(original).isPresent();
            assertHistoryPreserved(original.get());
        }
        assertThat(fixture.downstream.getCallCount()).isZero();
    }

    @Test
    void withdrawWithNoRowsOfferingVersionCompletesEmptyWithCursor() {
        fixture.seedPublish("pub-1", FanOutFixture.PRODUCT, "v1", T_10);

        var outcome = fixture.newEngine()
                .runWithdrawSupersede(fixture.withdrawTrigger("wd-9", "pub-1"));

        assertThat(outcome.status()).isEqualTo(FanOutOutcomeStatus.COMPLETED_EMPTY);
        assertThat(outcome.chunkCount()).isZero();
        assertThat(outcome.rowsResolved()).isZero();
        assertThat(fixture.writer.writeCount()).isZero();
        var cursor = fixture.cursors.readRun(FanOutRunKind.WITHDRAW_SUPERSEDE,
                "wd-9", FanOutFixture.PRODUCT).orElseThrow();
        assertThat(cursor.status()).isEqualTo(FanOutRunStatus.COMPLETE);
        assertThat(cursor.totalChunks()).isZero();
        // marker still recorded durably before the (empty) work
        assertThat(fixture.cursors.withdrawnVersions(FanOutFixture.PRODUCT))
                .containsExactly("v1");
        assertThat(fixture.downstream.getCallCount()).isZero();
    }

    @Test
    void crashedSupersedeResumesAtNextChunkWithoutReprocessing() {
        fixture.seedPublish("pub-1", FanOutFixture.PRODUCT, "v1", T_10);
        fixture.seedPublish("pub-2", FanOutFixture.PRODUCT, "v2", T_11);
        List<EngagementRef> refs = fixture.seedEligible(25, 40);
        fixture.eligibility.seedOffering(FanOutFixture.PRODUCT, "v1", refs);
        var trigger = fixture.withdrawTrigger("wd-1", "pub-1");

        fixture.writer.dieAfterWrites(500);
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> fixture.newEngine().runWithdrawSupersede(trigger))
                .isInstanceOf(
                        com.example.fanout.fakes.FailingIndexWriter.SimulatedCrashException.class);
        assertThat(fixture.writer.writeCount()).isEqualTo(501);
        var crashed = fixture.cursors.readRun(FanOutRunKind.WITHDRAW_SUPERSEDE,
                "wd-1", FanOutFixture.PRODUCT).orElseThrow();
        assertThat(crashed.status()).isEqualTo(FanOutRunStatus.RUNNING);
        assertThat(crashed.lastCompletedChunkIndex()).isEqualTo(0);

        fixture.writer.dieAfterWrites(Integer.MAX_VALUE - 1);
        var outcome = fixture.newEngine().runWithdrawSupersede(trigger);
        assertThat(outcome.status()).isEqualTo(FanOutOutcomeStatus.COMPLETED);
        assertThat(outcome.rowsResolved()).isEqualTo(500); // only chunk 1 re-ran
        assertThat(fixture.writer.writeCount()).isEqualTo(1_001);
        for (EngagementRef ref : refs) {
            assertThat(fixture.index.find(ref.engagementId(), "v2", PendingIndexState.PENDING))
                    .isPresent();
        }
        assertThat(fixture.cursors.withdrawnVersions(FanOutFixture.PRODUCT))
                .containsExactly("v1"); // marker append-if-absent on resume
        var resumed = fixture.cursors.readRun(FanOutRunKind.WITHDRAW_SUPERSEDE,
                "wd-1", FanOutFixture.PRODUCT).orElseThrow();
        assertThat(resumed.status()).isEqualTo(FanOutRunStatus.COMPLETE);
        assertThat(resumed.lastCompletedChunkIndex()).isEqualTo(1);
        assertThat(fixture.downstream.getCallCount()).isZero();
    }

    @Test
    void duplicateWithdrawTriggerIsIdempotentWithSingleMarkerAndNoNewWrites() {
        fixture.seedPublish("pub-1", FanOutFixture.PRODUCT, "v1", T_10);
        fixture.seedPublish("pub-2", FanOutFixture.PRODUCT, "v2", T_11);
        EngagementRef ref = FanOutFixture.ref(0, 0);
        fixture.eligibility.seedOffering(FanOutFixture.PRODUCT, "v1", List.of(ref));
        seedHistory(ref, "v1");
        var trigger = fixture.withdrawTrigger("wd-1", "pub-1");

        var first = fixture.newEngine().runWithdrawSupersede(trigger);
        assertThat(first.status()).isEqualTo(FanOutOutcomeStatus.COMPLETED);
        int writesAfterFirst = fixture.writer.writeCount();
        List<PendingIndexRow> snapshot = List.of(
                fixture.index.find(ref.engagementId(), "v2", PendingIndexState.PENDING).orElseThrow());

        var second = fixture.newEngine().runWithdrawSupersede(trigger);
        assertThat(second.status()).isEqualTo(FanOutOutcomeStatus.SKIPPED_DUPLICATE);
        assertThat(second.rowsResolved()).isZero();
        assertThat(fixture.writer.writeCount()).isEqualTo(writesAfterFirst);
        assertThat(fixture.index.find(ref.engagementId(), "v2", PendingIndexState.PENDING))
                .isEqualTo(Optional.of(snapshot.getFirst()));
        assertThat(fixture.cursors.withdrawnVersions(FanOutFixture.PRODUCT))
                .containsExactly("v1");
        assertThat(fixture.metrics.total("fanout.duplicate.run")).isGreaterThanOrEqualTo(1);
        assertThat(fixture.downstream.getCallCount()).isZero();
    }

    @Test
    void supersedeReplayAfterRewoundCursorConvergesWithoutBumpingRows() {
        fixture.seedPublish("pub-1", FanOutFixture.PRODUCT, "v1", T_10);
        fixture.seedPublish("pub-2", FanOutFixture.PRODUCT, "v2", T_11);
        List<EngagementRef> refs = fixture.seedEligible(5, 4);
        fixture.eligibility.seedOffering(FanOutFixture.PRODUCT, "v1", refs);
        for (EngagementRef ref : refs) {
            seedHistory(ref, "v1");
        }
        var trigger = fixture.withdrawTrigger("wd-1", "pub-1");

        var first = fixture.newEngine().runWithdrawSupersede(trigger);
        assertThat(first.status()).isEqualTo(FanOutOutcomeStatus.COMPLETED);
        List<PendingIndexRow> snapshot = new ArrayList<>();
        for (EngagementRef ref : refs) {
            snapshot.add(fixture.index
                    .find(ref.engagementId(), "v2", PendingIndexState.PENDING).orElseThrow());
        }

        // simulate crash after all writes, before cursor completion
        fixture.cursors.advance(FanOutRunKind.WITHDRAW_SUPERSEDE, "wd-1",
                FanOutFixture.PRODUCT, 1, -1);

        var replayed = fixture.newEngine().runWithdrawSupersede(trigger);
        assertThat(replayed.status()).isEqualTo(FanOutOutcomeStatus.COMPLETED);
        assertThat(replayed.rowsResolved()).isEqualTo(20);
        for (int i = 0; i < refs.size(); i++) {
            var row = fixture.index.find(refs.get(i).engagementId(), "v2",
                    PendingIndexState.PENDING);
            assertThat(row).isPresent();
            assertThat(row.get().rowVersion()).isEqualTo(1);
            assertThat(row).isEqualTo(Optional.of(snapshot.get(i)));
        }
        assertThat(fixture.cursors.withdrawnVersions(FanOutFixture.PRODUCT))
                .containsExactly("v1");
        assertThat(fixture.downstream.getCallCount()).isZero();
    }

    @Test
    void wrongKindForSupersedeEntryPointRejected() {
        fixture.seedPublish("pub-1", FanOutFixture.PRODUCT, "v1", T_10);
        var engine = fixture.newEngine();
        var publishLike = fixture.publishTrigger("pub-1");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> engine.runWithdrawSupersede(publishLike))
                .withMessageContaining("kind");
        assertThat(fixture.downstream.getCallCount()).isZero();
    }

    // ---- helpers ----

    /** Seed a withdrawn-version row carrying recorded decision history. */
    private void seedHistory(EngagementRef ref, String version) {
        fixture.index.upsert(new PendingIndexRow(
                ref.engagementId(), ref.firmId(), ref.productId(), ref.currentVersion(),
                version, PendingIndexState.PENDING,
                "declined", List.of("r1", "r2"), "sum-9",
                Set.of("pub-1"), 1L, fixture.clock.instant()));
    }

    private static void assertHistoryPreserved(PendingIndexRow row) {
        assertThat(row.decision()).isEqualTo("declined");
        assertThat(row.pinnedRanges()).containsExactly("r1", "r2");
        assertThat(row.summaryKey()).isEqualTo("sum-9");
    }
}
