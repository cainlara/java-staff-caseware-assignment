package com.example.fanout.ports;

import java.util.Objects;
import java.util.Optional;

/**
 * Reads/writes external progress so work survives process restart.
 *
 * <p><b>Idempotency key</b>: {@link CheckpointKey} {@code (kind, id)}.
 * {@code write} is last-write-wins on the same key (progress is a cursor, not a
 * multi-writer structure); concurrent writers to the same key are a caller bug.
 *
 * <p><b>Durability</b>: production semantics REQUIRE values to survive process
 * restart (the store is external to process memory). The in-memory placeholder
 * implementation shipped now does NOT provide that guarantee; a real adapter
 * (database/queue-backed) fulfills this contract in a later phase.
 *
 * <p><b>Missing keys</b>: {@code read} of a never-written key returns
 * {@link Optional#empty()} — distinguishable from a stored empty string
 * ({@code Optional.of("")}).
 */
public interface CheckpointStore {

    Optional<String> read(CheckpointKey key);

    void write(CheckpointKey key, String value);

    /**
     * Atomic claim: writes {@code value} only if the key is absent.
     *
     * <p><b>Semantics</b>: returns {@code true} if the value was written (key
     * was absent); {@code false} if a value already existed (left unchanged —
     * first claim wins). Concurrent callers for the same absent key MUST have
     * exactly one observe {@code true}.
     *
     * <p><b>Default implementation</b> is a non-atomic read-then-write
     * provided for source compatibility of existing implementations.
     * Implementations that back event ingestion (starting with
     * {@code InMemoryCheckpointStore}) MUST override this method with an
     * atomic claim (e.g. {@code ConcurrentHashMap.putIfAbsent}; a database
     * adapter maps it to an {@code INSERT} against a unique constraint on
     * {@code (kind, id)}).
     *
     * <p>Null key or null value are rejected.
     */
    default boolean writeIfAbsent(CheckpointKey key, String value) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(value, "value must not be null");
        if (read(key).isPresent()) {
            return false;
        }
        write(key, value);
        return true;
    }
}
