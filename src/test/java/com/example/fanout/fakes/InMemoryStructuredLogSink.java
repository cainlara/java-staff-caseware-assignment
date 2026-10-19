package com.example.fanout.fakes;

import com.example.fanout.observability.IdentifierField;
import com.example.fanout.observability.LogEvent;
import com.example.fanout.observability.LogLevel;
import com.example.fanout.ports.StructuredLogSink;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Capturing sink for suite-level confidentiality assertions
 * (LogConfidentialityTest). Placed AFTER
 * {@code WhitelistValidatingLogSink} in the pipeline so violations are caught
 * by the decorator; captures {@link LogEvent} for whitelist/field audits.
 */
public final class InMemoryStructuredLogSink implements StructuredLogSink {

    private final List<LogEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void log(LogLevel level, String event, Instant at,
                    Map<IdentifierField, String> identifiers) {
        events.add(new LogEvent(level, event, at, identifiers));
    }

    public List<LogEvent> events() {
        return List.copyOf(events);
    }

    public List<LogEvent> eventsFor(String event) {
        return events.stream().filter(e -> e.event().equals(event)).toList();
    }
}
