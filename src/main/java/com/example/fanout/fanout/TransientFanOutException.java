package com.example.fanout.fanout;

/**
 * Unchecked signal that one work-unit attempt failed transiently and may be
 * retried (research.md D8): wraps writer, eligibility-stream, cursor, plan,
 * and downstream errors. Message carries identifiers only — never engagement
 * content (spec FR-070).
 */
public class TransientFanOutException extends RuntimeException {

    public TransientFanOutException(String message) {
        super(message);
    }

    public TransientFanOutException(String message, Throwable cause) {
        super(message, cause);
    }
}
