package com.example.fanout.ports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.example.fanout.domain.TemplatePublished;
import com.example.fanout.domain.TemplateWithdrawn;
import com.example.fanout.fakes.FakeEventSource;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pins EventSource contract (contracts/event-source.md): at-least-once shape —
 * duplicate events carry equal natural keys (dedup possible); empty poll is not
 * an error; unkeyed events cannot be constructed.
 */
class EventSourceContractTest {

    private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");

    private static TemplatePublished publish(String id) {
        return new TemplatePublished(id, "prod-1", "v1", "v2", NOW);
    }

    @Test
    void emptyPollReturnsEmptyWithoutThrowing() {
        var source = new FakeEventSource();
        assertThat(source.pollPublish()).isEqualTo(Optional.empty());
        assertThat(source.pollWithdraw()).isEqualTo(Optional.empty());
    }

    @Test
    void duplicateEventsCarryEqualNaturalKeys() {
        var source = new FakeEventSource();
        var event = publish("pub-1");
        source.enqueue(event);
        source.enqueue(event);

        var first = source.pollPublish();
        var second = source.pollPublish();
        assertThat(first).isPresent();
        assertThat(second).isPresent();
        assertThat(first.get().publishId()).isEqualTo(second.get().publishId());
        assertThat(first.get().productId()).isEqualTo(second.get().productId());
    }

    @Test
    void withdrawEventsPollIndependently() {
        var source = new FakeEventSource();
        source.enqueue(publish("pub-1"));
        source.enqueue(new TemplateWithdrawn("wd-1", "prod-1", "v2", NOW));

        assertThat(source.pollPublish()).isPresent();
        assertThat(source.pollPublish()).isEmpty();
        assertThat(source.pollWithdraw()).isPresent();
    }

    @Test
    void unkeyedEventsCannotBeConstructed() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TemplatePublished(" ", "prod-1", "v1", "v2", NOW))
                .withMessageContaining("publishId");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TemplateWithdrawn("", "prod-1", "v2", NOW))
                .withMessageContaining("withdrawId");
    }
}
