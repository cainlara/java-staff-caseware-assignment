package com.example.fanout.observability;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A validated structured-log event (spec FR-004, contracts/structured-
 * logging.md). Immutable: construction performs every content-shapedness and
 * field-value guard so that any sink downstream can trust its input.
 *
 * <p>Violations throw {@link IllegalArgumentException} naming the offending
 * event and field (test vector 1).
 */
public record LogEvent(LogLevel level, String event, Instant at,
                       Map<IdentifierField, String> identifiers) {

    /** Event names are dotted lower_snake ({@code fanout.run.started}). */
    private static final Pattern DOTTED_LOWER_SNAKE =
            Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*");

    /** Identifier values ≤ 128 chars (data-model volume discipline). */
    private static final int MAX_VALUE_LENGTH = 128;

    /** Free-text heuristic: 3+ consecutive whitespace. */
    private static final Pattern FREE_TEXT_SHAPE = Pattern.compile("\\s{3,}");

    /** Error-path field: only retry/dead-letter events may carry it. */
    private static final Set<String> ENGAGEMENT_ID_EVENTS =
            Set.of("fanout.retry", "fanout.dlq");

    public LogEvent {
        if (level == null) {
            throw new IllegalArgumentException("log level must not be null for event " + event);
        }
        if (event == null || event.isBlank()) {
            throw new IllegalArgumentException("event must not be null or blank");
        }
        if (!DOTTED_LOWER_SNAKE.matcher(event).matches()) {
            throw new IllegalArgumentException(
                    "event must be dotted lower_snake: " + event);
        }
        if (at == null) {
            throw new IllegalArgumentException("at must not be null for event " + event);
        }
        if (identifiers == null) {
            throw new IllegalArgumentException(
                    "identifiers must not be null for event " + event);
        }
        Map<IdentifierField, String> copy = new EnumMap<>(IdentifierField.class);
        for (Map.Entry<IdentifierField, String> entry : identifiers.entrySet()) {
            IdentifierField field = entry.getKey();
            String value = entry.getValue();
            if (field == null) {
                throw new IllegalArgumentException("identifier field must not be null in " + event);
            }
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(
                        "identifier value must not be null or blank for field "
                                + field.name() + " of event " + event);
            }
            if (value.length() > MAX_VALUE_LENGTH) {
                throw new IllegalArgumentException(
                        "identifier value exceeds " + MAX_VALUE_LENGTH
                                + " chars for field " + field.name() + " of event " + event);
            }
            if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException(
                        "identifier value must not contain CR/LF/NUL for field "
                                + field.name() + " of event " + event);
            }
            if (FREE_TEXT_SHAPE.matcher(value).find()) {
                throw new IllegalArgumentException(
                        "identifier value looks free-text for field "
                                + field.name() + " of event " + event);
            }
            if (field == IdentifierField.engagement_id
                    && !ENGAGEMENT_ID_EVENTS.contains(event)) {
                throw new IllegalArgumentException(
                        "field engagement_id is only allowed on " + ENGAGEMENT_ID_EVENTS
                                + ", not on event " + event);
            }
            copy.put(field, value);
        }
        identifiers = Map.copyOf(copy);
    }
}
