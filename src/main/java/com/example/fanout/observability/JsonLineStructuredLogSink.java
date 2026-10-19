package com.example.fanout.observability;

import com.example.fanout.ports.StructuredLogSink;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Objects;

/**
 * One JSON object per line (spec FR-004, contracts/structured-logging.md test
 * vector 2). Keys: {@code ts}, {@code level}, {@code event}, then identifiers
 * in {@link IdentifierField} declaration order — deterministic output.
 *
 * <p>Core processing MUST NOT fail because a log line failed to append
 * (constitution II): a writer exception is swallowed. Chunk threads emit
 * concurrently, so appends serialize on the writer.
 */
public final class JsonLineStructuredLogSink implements StructuredLogSink {

    /** Destination for one complete JSON line (including trailing newline). */
    @FunctionalInterface
    public interface LineWriter {
        void write(String line);
    }

    private final LineWriter writer;

    public JsonLineStructuredLogSink(LineWriter writer) {
        this.writer = Objects.requireNonNull(writer, "writer must not be null");
    }

    @Override
    public void log(LogLevel level, String event, Instant at,
                    Map<IdentifierField, String> identifiers) {
        String line = toJson(level, event, at, identifiers) + "\n";
        try {
            synchronized (writer) {
                writer.write(line);
            }
        } catch (RuntimeException dropped) {
            // observability is a read-only side channel: never fail the caller
        }
    }

    private static String toJson(LogLevel level, String event, Instant at,
                                 Map<IdentifierField, String> identifiers) {
        StringBuilder json = new StringBuilder(128);
        json.append("{\"ts\":\"").append(DateTimeFormatter.ISO_INSTANT.format(at))
                .append("\",\"level\":\"").append(level.name())
                .append("\",\"event\":\"").append(escape(event)).append('"');
        for (IdentifierField field : IdentifierField.values()) {
            String value = identifiers.get(field);
            if (value != null) {
                json.append(",\"").append(field.name()).append("\":\"")
                        .append(escape(value)).append('"');
            }
        }
        return json.append('}').toString();
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
