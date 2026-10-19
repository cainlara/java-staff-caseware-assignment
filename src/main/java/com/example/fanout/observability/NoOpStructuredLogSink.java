package com.example.fanout.observability;

import com.example.fanout.ports.StructuredLogSink;
import java.time.Instant;
import java.util.Map;

/**
 * Null-object sink (constitution II): observability MUST never block or fail
 * core processing, so every production constructor defaults here until a
 * composition root injects a real pipeline. No validation, no output — cheap
 * enough for the hot path.
 */
public final class NoOpStructuredLogSink implements StructuredLogSink {

    @Override
    public void log(LogLevel level, String event, Instant at,
                    Map<IdentifierField, String> identifiers) {
        // deliberately empty: read-only side channel, dropped by default
    }
}
