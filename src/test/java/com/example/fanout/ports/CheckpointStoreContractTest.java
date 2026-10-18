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
}
