package com.example.fanout.ports;

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
}
