package com.example.fanout.ports;

import java.util.Objects;

/**
 * Natural key for {@link CheckpointStore}: {@code (kind, id)}.
 * Progress for publish {@code X} and backfill batch {@code X} never collide
 * because {@code kind} differs. {@code id} is mandatory, non-blank.
 */
public record CheckpointKey(CheckpointKind kind, String id) {

    public CheckpointKey {
        Objects.requireNonNull(kind, "kind must not be null");
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must be non-blank");
        }
    }
}
