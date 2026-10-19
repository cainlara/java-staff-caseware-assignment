package com.example.fanout.fakes;

import com.example.fanout.domain.DeadLetterRecord;
import com.example.fanout.ports.DeadLetterStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Synchronized in-memory {@link DeadLetterStore} fake
 * (contracts/dead-letter-store.md): {@code store} appends, {@code byEvent}
 * filters in store order; test hooks {@code all()} and {@code count()}.
 */
public final class FakeDeadLetterStore implements DeadLetterStore {

    private final List<DeadLetterRecord> records = new ArrayList<>();

    @Override
    public synchronized void store(DeadLetterRecord record) {
        Objects.requireNonNull(record, "record must not be null");
        records.add(record);
    }

    @Override
    public synchronized List<DeadLetterRecord> byEvent(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId must be non-blank");
        }
        return records.stream().filter(r -> r.eventId().equals(eventId)).toList();
    }

    public synchronized List<DeadLetterRecord> all() {
        return List.copyOf(records);
    }

    public synchronized int count() {
        return records.size();
    }
}
