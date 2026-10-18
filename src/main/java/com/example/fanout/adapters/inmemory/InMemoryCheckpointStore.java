package com.example.fanout.adapters.inmemory;

import com.example.fanout.ports.CheckpointKey;
import com.example.fanout.ports.CheckpointStore;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link CheckpointStore} placeholder.
 *
 * <p>Thread-safe last-write-wins per key. <b>Does NOT survive process restart</b>
 * by nature — the {@link CheckpointStore} contract requires durability; a real
 * adapter fulfills it in a later phase. The backing map is injectable so contract
 * tests can simulate a restart by constructing a new store instance over the same
 * map.
 */
public final class InMemoryCheckpointStore implements CheckpointStore {

    private final Map<CheckpointKey, String> backing;

    public InMemoryCheckpointStore() {
        this(new ConcurrentHashMap<>());
    }

    public InMemoryCheckpointStore(Map<CheckpointKey, String> backing) {
        this.backing = Objects.requireNonNull(backing, "backing must not be null");
    }

    @Override
    public Optional<String> read(CheckpointKey key) {
        Objects.requireNonNull(key, "key must not be null");
        return Optional.ofNullable(backing.get(key));
    }

    @Override
    public void write(CheckpointKey key, String value) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(value, "value must not be null");
        backing.put(key, value);
    }
}
