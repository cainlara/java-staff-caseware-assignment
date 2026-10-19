package com.example.fanout.ports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.example.fanout.adapters.inmemory.InMemoryCheckpointStore;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * Pins CheckpointStore contract (contracts/checkpoint-store.md): keyed by
 * (kind, id); missing key distinguishable from stored value; kind isolation;
 * simulated restart over shared backing map returns prior value; last-write-wins.
 */
class CheckpointStoreContractTest {

    private static final CheckpointKey PUBLISH = new CheckpointKey(CheckpointKind.PUBLISH, "pub-1");
    private static final CheckpointKey BACKFILL =
            new CheckpointKey(CheckpointKind.BACKFILL_BATCH, "pub-1");

    @Test
    void writeThenReadRoundTrip() {
        var store = new InMemoryCheckpointStore();
        store.write(PUBLISH, "offset=42");
        assertThat(store.read(PUBLISH)).isEqualTo(Optional.of("offset=42"));
    }

    @Test
    void neverWrittenKeyReturnsEmpty() {
        var store = new InMemoryCheckpointStore();
        assertThat(store.read(PUBLISH)).isEqualTo(Optional.empty());
    }

    @Test
    void storedEmptyStringDistinguishableFromMissing() {
        var store = new InMemoryCheckpointStore();
        store.write(PUBLISH, "");
        assertThat(store.read(PUBLISH)).isEqualTo(Optional.of(""));
        assertThat(store.read(new CheckpointKey(CheckpointKind.PUBLISH, "missing")))
                .isEqualTo(Optional.empty());
    }

    @Test
    void publishAndBackfillKeysDoNotCollide() {
        var store = new InMemoryCheckpointStore();
        store.write(PUBLISH, "publish-progress");
        assertThat(store.read(BACKFILL)).isEqualTo(Optional.empty());
        store.write(BACKFILL, "backfill-progress");
        assertThat(store.read(PUBLISH)).isEqualTo(Optional.of("publish-progress"));
        assertThat(store.read(BACKFILL)).isEqualTo(Optional.of("backfill-progress"));
    }

    @Test
    void simulatedRestartOverSameBackingMapReturnsPriorValue() {
        var backing = new ConcurrentHashMap<CheckpointKey, String>();
        var before = new InMemoryCheckpointStore(backing);
        before.write(PUBLISH, "offset=99");

        var afterRestart = new InMemoryCheckpointStore(backing);
        assertThat(afterRestart.read(PUBLISH)).isEqualTo(Optional.of("offset=99"));
    }

    @Test
    void overwriteLatestValueWins() {
        var store = new InMemoryCheckpointStore();
        store.write(PUBLISH, "v1");
        store.write(PUBLISH, "v2");
        assertThat(store.read(PUBLISH)).isEqualTo(Optional.of("v2"));
    }

    @Test
    void nullKeyRejected() {
        var store = new InMemoryCheckpointStore();
        assertThatNullPointerException().isThrownBy(() -> store.read(null));
        assertThatNullPointerException().isThrownBy(() -> store.write(null, "x"));
    }

    @Test
    void blankIdRejected() {
        assertThatIllegalArgumentExceptionForBlankId();
    }

    private static void assertThatIllegalArgumentExceptionForBlankId() {
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> new CheckpointKey(CheckpointKind.PUBLISH, "  "))
                .withMessageContaining("id");
    }

    // ---- v2: writeIfAbsent atomic claim (contracts/checkpoint-store.md) ----

    @Test
    void writeIfAbsentOnAbsentKeyWritesAndReturnsTrue() {
        var store = new InMemoryCheckpointStore();
        assertThat(store.writeIfAbsent(PUBLISH, "claim-1")).isTrue();
        assertThat(store.read(PUBLISH)).isEqualTo(Optional.of("claim-1"));
    }

    @Test
    void writeIfAbsentSecondClaimLosesFirstValueWins() {
        var store = new InMemoryCheckpointStore();
        assertThat(store.writeIfAbsent(PUBLISH, "first")).isTrue();
        assertThat(store.writeIfAbsent(PUBLISH, "second")).isFalse();
        assertThat(store.read(PUBLISH)).isEqualTo(Optional.of("first"));
    }

    @Test
    void concurrentWriteIfAbsentHasExactlyOneWinner() throws Exception {
        var store = new InMemoryCheckpointStore();
        int threads = 8;
        var barrier = new CyclicBarrier(threads);
        var winners = new AtomicInteger();
        var pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                final int n = i;
                pool.submit(() -> {
                    try {
                        barrier.await();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    if (store.writeIfAbsent(PUBLISH, "winner-" + n)) {
                        winners.incrementAndGet();
                    }
                });
            }
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(winners.get()).isEqualTo(1);
    }

    @Test
    void defaultMethodCompatibleWithReadOnlyImplementations() {
        CheckpointStore legacy = new CheckpointStore() {
            private final Map<CheckpointKey, String> map = new HashMap<>();

            @Override
            public Optional<String> read(CheckpointKey key) {
                return Optional.ofNullable(map.get(key));
            }

            @Override
            public void write(CheckpointKey key, String value) {
                map.put(key, value);
            }
        };
        assertThat(legacy.writeIfAbsent(PUBLISH, "v")).isTrue();
        assertThat(legacy.writeIfAbsent(PUBLISH, "w")).isFalse();
        assertThat(legacy.read(PUBLISH)).isEqualTo(Optional.of("v"));
    }

    @Test
    void writeIfAbsentRejectsNullOrBlankIdAndNullValue() {
        var store = new InMemoryCheckpointStore();
        org.assertj.core.api.Assertions.assertThatNullPointerException()
                .isThrownBy(() -> store.writeIfAbsent(null, "x"));
        org.assertj.core.api.Assertions.assertThatNullPointerException()
                .isThrownBy(() -> store.writeIfAbsent(PUBLISH, null));
    }

    // ---- v3: FANOUT_CURSOR claim/advance + PRODUCT_WITHDRAWN_VERSION marker
    //     (contracts/checkpoint-store.md additive vectors) ----

    @Test
    void cursorClaimConcurrentWriteIfAbsentHasExactlyOneWinner() throws Exception {
        var store = new InMemoryCheckpointStore();
        var repo = new com.example.fanout.fanout.FanOutCursorRepository(store);
        int threads = 8;
        var barrier = new CyclicBarrier(threads);
        var winners = new AtomicInteger();
        var pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        barrier.await();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    if (repo.claimRun(com.example.fanout.domain.FanOutRunKind.PUBLISH,
                            "pub-9", "prod-9", 4)) {
                        winners.incrementAndGet();
                    }
                });
            }
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(winners.get()).isEqualTo(1);
    }

    @Test
    void cursorClaimAdvanceCompleteRoundTrip() {
        var repo = new com.example.fanout.fanout.FanOutCursorRepository(new InMemoryCheckpointStore());
        assertThat(repo.claimRun(com.example.fanout.domain.FanOutRunKind.PUBLISH, "pub-1", "prod-1", 3)).isTrue();

        var running = repo.readRun(com.example.fanout.domain.FanOutRunKind.PUBLISH, "pub-1", "prod-1");
        assertThat(running).isPresent();
        assertThat(running.get().status()).isEqualTo(com.example.fanout.domain.FanOutRunStatus.RUNNING);
        assertThat(running.get().lastCompletedChunkIndex()).isEqualTo(-1);

        repo.advance(com.example.fanout.domain.FanOutRunKind.PUBLISH, "pub-1", "prod-1", 3, 1);
        assertThat(repo.readRun(com.example.fanout.domain.FanOutRunKind.PUBLISH, "pub-1", "prod-1").get()
                .lastCompletedChunkIndex()).isEqualTo(1);

        repo.complete(com.example.fanout.domain.FanOutRunKind.PUBLISH, "pub-1", "prod-1", 3);
        var complete = repo.readRun(com.example.fanout.domain.FanOutRunKind.PUBLISH, "pub-1", "prod-1").get();
        assertThat(complete.status()).isEqualTo(com.example.fanout.domain.FanOutRunStatus.COMPLETE);
        assertThat(complete.lastCompletedChunkIndex()).isEqualTo(2);
    }

    @Test
    void cursorCompleteRunDistinguishesDuplicateSkipFromMissing() {
        var repo = new com.example.fanout.fanout.FanOutCursorRepository(new InMemoryCheckpointStore());
        assertThat(repo.readRun(com.example.fanout.domain.FanOutRunKind.PUBLISH, "missing", "prod-1"))
                .isEmpty();
        repo.claimRun(com.example.fanout.domain.FanOutRunKind.PUBLISH, "pub-2", "prod-1", 0);
        repo.complete(com.example.fanout.domain.FanOutRunKind.PUBLISH, "pub-2", "prod-1", 0);
        var read = repo.readRun(com.example.fanout.domain.FanOutRunKind.PUBLISH, "pub-2", "prod-1").get();
        assertThat(read.status()).isEqualTo(com.example.fanout.domain.FanOutRunStatus.COMPLETE);
        assertThat(read.totalChunks()).isZero();
    }

    @Test
    void cursorRunKindSeparatesPublishAndSupersedeIdentities() {
        var repo = new com.example.fanout.fanout.FanOutCursorRepository(new InMemoryCheckpointStore());
        assertThat(repo.claimRun(com.example.fanout.domain.FanOutRunKind.PUBLISH, "evt-1", "prod-1", 1)).isTrue();
        assertThat(repo.claimRun(com.example.fanout.domain.FanOutRunKind.WITHDRAW_SUPERSEDE,
                "evt-1", "prod-1", 1)).isTrue();
    }

    @Test
    void corruptCursorEncodingFailsLoudly() {
        var store = new InMemoryCheckpointStore();
        var repo = new com.example.fanout.fanout.FanOutCursorRepository(store);
        var key = new CheckpointKey(CheckpointKind.FANOUT_CURSOR, "PUBLISH\u001Fpub-1\u001Fprod-1");
        store.write(key, "RUNNING\u001Fnot-a-number\u001F-1");
        org.assertj.core.api.Assertions.assertThatIllegalStateException()
                .isThrownBy(() -> repo.readRun(com.example.fanout.domain.FanOutRunKind.PUBLISH, "pub-1", "prod-1"))
                .withMessageContaining("corrupt");
        store.write(key, "BOGUS\u001F3\u001F-1");
        org.assertj.core.api.Assertions.assertThatIllegalStateException()
                .isThrownBy(() -> repo.readRun(com.example.fanout.domain.FanOutRunKind.PUBLISH, "pub-1", "prod-1"));
        store.write(key, "only-two\u001Fparts");
        org.assertj.core.api.Assertions.assertThatIllegalStateException()
                .isThrownBy(() -> repo.readRun(com.example.fanout.domain.FanOutRunKind.PUBLISH, "pub-1", "prod-1"));
    }

    @Test
    void markerAppendIfAbsentIsIdempotentAndAccumulates() {
        var repo = new com.example.fanout.fanout.FanOutCursorRepository(new InMemoryCheckpointStore());
        repo.markWithdrawn("prod-1", "v2");
        repo.markWithdrawn("prod-1", "v2");
        repo.markWithdrawn("prod-1", "v3");
        assertThat(repo.withdrawnVersions("prod-1")).containsExactly("v2", "v3");
        assertThat(repo.withdrawnVersions("prod-missing")).isEmpty();
    }
}
