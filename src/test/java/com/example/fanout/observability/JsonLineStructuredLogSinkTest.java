package com.example.fanout.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.fanout.ports.StructuredLogSink;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Pins contracts/structured-logging.md test vector 2: one JSON object per
 * line, identifiers serialized in enum declaration order, escaping applied,
 * and a failed append never blocks chunk processing.
 */
class JsonLineStructuredLogSinkTest {

    private static final Instant AT = Instant.parse("2026-10-19T12:00:00Z");

    private final StringBuilder out = new StringBuilder();
    private final StructuredLogSink sink = new JsonLineStructuredLogSink(out::append);

    @Test
    void singleLineWithIdentifiersInEnumOrder() {
        sink.log(LogLevel.INFO, "fanout.chunk.completed", AT,
                Map.of(IdentifierField.product_id, "prod-1",
                        IdentifierField.state, "pending",
                        IdentifierField.publish_id, "pub-7",
                        IdentifierField.chunk_id, "3"));

        String line = out.toString();
        assertThat(line).endsWith("\n");
        assertThat(line.substring(0, line.length() - 1))
                .doesNotContain("\n")
                .isEqualTo("{\"ts\":\"2026-10-19T12:00:00Z\",\"level\":\"INFO\","
                        + "\"event\":\"fanout.chunk.completed\","
                        + "\"publish_id\":\"pub-7\",\"product_id\":\"prod-1\","
                        + "\"chunk_id\":\"3\",\"state\":\"pending\"}");
    }

    @Test
    void eventWithoutIdentifiersEmitsOnlyCoreFields() {
        sink.log(LogLevel.WARN, "backfill.halt.observed", AT, Map.of());

        assertThat(out.toString())
                .isEqualTo("{\"ts\":\"2026-10-19T12:00:00Z\",\"level\":\"WARN\","
                        + "\"event\":\"backfill.halt.observed\"}\n");
    }

    @Test
    void jsonSpecialCharactersEscaped() {
        sink.log(LogLevel.ERROR, "fanout.dlq", AT,
                Map.of(IdentifierField.product_id, "prod-1",
                        IdentifierField.state, "dead\\lettered"));

        assertThat(out.toString()).contains("\"dead\\\\lettered\"");
    }

    @Test
    void multipleEventsEmitOneLineEach() {
        sink.log(LogLevel.INFO, "fanout.run.started", AT,
                Map.of(IdentifierField.product_id, "prod-1"));
        sink.log(LogLevel.INFO, "fanout.run.completed", AT,
                Map.of(IdentifierField.product_id, "prod-1",
                        IdentifierField.state, "complete"));

        assertThat(out.toString().lines()).hasSize(2);
    }

    @Test
    void failedAppendSoftFailsWithoutThrowing() {
        AtomicBoolean failed = new AtomicBoolean();
        StructuredLogSink broken = new JsonLineStructuredLogSink(target -> {
            failed.set(true);
            throw new java.io.UncheckedIOException(new java.io.IOException("disk full"));
        });

        broken.log(LogLevel.INFO, "fanout.run.started", AT,
                Map.of(IdentifierField.product_id, "prod-1"));

        assertThat(failed).isTrue();
    }
}
