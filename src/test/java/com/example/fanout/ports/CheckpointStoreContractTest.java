package com.example.fanout.ports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.example.fanout.adapters.inmemory.InMemoryCheckpointStore;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
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
        var barrier = new java.util.concurrent.CyclicBarrier(threads);
        var winners = new java.util.concurrent.atomic.AtomicInteger();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
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
            assertThat(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(winners.get()).isEqualTo(1);
    }

    @Test
    void defaultMethodCompatibleWithReadOnlyImplementations() {
        CheckpointStore legacy = new CheckpointStore() {
            private final java.util.Map<CheckpointKey, String> map = new java.util.HashMap<>();

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
}
