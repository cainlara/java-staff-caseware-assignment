package com.example.fanout.fanout;

import com.example.fanout.domain.DeadLetterRecord;
import java.util.Objects;

/**
 * Thrown when a work unit exhausts its retry budget (research.md D8). Carries
 * the fully identified {@link DeadLetterRecord} so the caller can store it in
 * the {@code DeadLetterStore} and increment the {@code fanout.dlq} alert
 * counter — never a silent drop (spec FR-041).
 */
public class FanOutExhaustedException extends RuntimeException {

    private final transient DeadLetterRecord record;

    public FanOutExhaustedException(String message, DeadLetterRecord record) {
        super(message);
        this.record = Objects.requireNonNull(record, "record must not be null");
    }

    public DeadLetterRecord record() {
        return record;
    }
}
