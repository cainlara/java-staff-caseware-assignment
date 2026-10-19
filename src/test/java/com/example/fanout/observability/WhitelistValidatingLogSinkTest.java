package com.example.fanout.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.example.fanout.ports.StructuredLogSink;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins contracts/structured-logging.md test vector 1: every whitelist/content
 * guard throws {@link IllegalArgumentException} naming the offending event and
 * field, and a valid event passes through to the delegate unchanged.
 */
class WhitelistValidatingLogSinkTest {

    private static final Instant AT = Instant.parse("2026-10-19T12:00:00Z");

    private final RecordingSink delegate = new RecordingSink();
    private final StructuredLogSink sink = new WhitelistValidatingLogSink(delegate);

    @Test
    void nullArgumentsRejectedNamingTheField() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> sink.log(null, "ingest.claimed", AT, Map.of()))
                .withMessageContaining("level");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> sink.log(LogLevel.INFO, null, AT, Map.of()))
                .withMessageContaining("event");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> sink.log(LogLevel.INFO, "ingest.claimed", null, Map.of()))
                .withMessageContaining("at");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> sink.log(LogLevel.INFO, "ingest.claimed", AT, null))
                .withMessageContaining("identifiers");
        assertThat(delegate.events).isEmpty();
    }

    @Test
    void blankOrMalformedEventNameRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> sink.log(LogLevel.INFO, " ", AT, Map.of()))
                .withMessageContaining("event");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> sink.log(LogLevel.INFO, "not dotted lower_snake", AT, Map.of()))
                .withMessageContaining("event");
        assertThat(delegate.events).isEmpty();
    }

    @Test
    void blankIdentifierValueRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> sink.log(LogLevel.INFO, "ingest.claimed", AT,
                        Map.of(IdentifierField.product_id, " ")))
                .withMessageContaining("product_id");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> sink.log(LogLevel.INFO, "ingest.claimed", AT,
                        Collections.singletonMap(IdentifierField.publish_id, null)))
                .withMessageContaining("publish_id");
        assertThat(delegate.events).isEmpty();
    }

    @Test
    void overlongIdentifierValueRejected() {
        String overlong = "x".repeat(129);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> sink.log(LogLevel.INFO, "ingest.claimed", AT,
                        Map.of(IdentifierField.publish_id, overlong)))
                .withMessageContaining("publish_id");
        assertThat(delegate.events).isEmpty();
    }

    @Test
    void controlCharactersInValueRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> sink.log(LogLevel.INFO, "fanout.chunk.completed", AT,
                        Map.of(IdentifierField.state, "pending\ninjected")))
                .withMessageContaining("state");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> sink.log(LogLevel.INFO, "fanout.chunk.completed", AT,
                        Map.of(IdentifierField.state, "pending\rflush")))
                .withMessageContaining("state");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> sink.log(LogLevel.INFO, "fanout.chunk.completed", AT,
                        Map.of(IdentifierField.state, "pend" + (char) 0 + "ing")))
                .withMessageContaining("state");
        assertThat(delegate.events).isEmpty();
    }

    @Test
    void freeTextShapedValueRejected() {
        String spaced = "three   consecutive   whitespace   runs";
        assertThatIllegalArgumentException()
                .isThrownBy(() -> sink.log(LogLevel.INFO, "fanout.chunk.completed", AT,
                        Map.of(IdentifierField.state, spaced)))
                .withMessageContaining("state");
        assertThat(delegate.events).isEmpty();
    }

    @Test
    void engagementIdAllowedOnlyOnRetryAndDlq() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> sink.log(LogLevel.INFO, "ingest.claimed", AT,
                        Map.of(IdentifierField.engagement_id, "eng-1")))
                .withMessageContaining("engagement_id");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> sink.log(LogLevel.INFO, "fanout.run.started", AT,
                        Map.of(IdentifierField.engagement_id, "eng-1")))
                .withMessageContaining("engagement_id");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> sink.log(LogLevel.WARN, "fanout.chunk.completed", AT,
                        Map.of(IdentifierField.product_id, "p1",
                                IdentifierField.engagement_id, "eng-1")))
                .withMessageContaining("fanout.chunk.completed");

        sink.log(LogLevel.WARN, "fanout.retry", AT,
                Map.of(IdentifierField.product_id, "p1", IdentifierField.engagement_id, "eng-1"));
        sink.log(LogLevel.ERROR, "fanout.dlq", AT,
                Map.of(IdentifierField.product_id, "p1", IdentifierField.engagement_id, "eng-1"));
        assertThat(delegate.events).hasSize(2);
    }

    @Test
    void validEventPassesThroughUnchanged() {
        Map<IdentifierField, String> identifiers = new LinkedHashMap<>();
        identifiers.put(IdentifierField.publish_id, "pub-9");
        identifiers.put(IdentifierField.product_id, "prod-1");
        identifiers.put(IdentifierField.chunk_id, "12");
        identifiers.put(IdentifierField.state, "pending");

        sink.log(LogLevel.INFO, "fanout.chunk.completed", AT, identifiers);

        assertThat(delegate.events).hasSize(1);
        RecordingSink.Recorded first = delegate.events.getFirst();
        assertThat(first.level).isEqualTo(LogLevel.INFO);
        assertThat(first.event).isEqualTo("fanout.chunk.completed");
        assertThat(first.at).isEqualTo(AT);
        assertThat(first.identifiers).containsExactlyInAnyOrderEntriesOf(identifiers);
    }

    /** Minimal pass-through recorder behind the decorator. */
    private static final class RecordingSink implements StructuredLogSink {

        private final java.util.List<Recorded> events = new java.util.ArrayList<>();

        @Override
        public void log(LogLevel level, String event, Instant at,
                        Map<IdentifierField, String> identifiers) {
            events.add(new Recorded(level, event, at, Map.copyOf(identifiers)));
        }

        private record Recorded(LogLevel level, String event, Instant at,
                                Map<IdentifierField, String> identifiers) {
        }
    }
}
