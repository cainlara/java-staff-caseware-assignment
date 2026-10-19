package com.example.fanout.observability;

import com.example.fanout.ports.StructuredLogSink;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Whitelist/content decorator (spec FR-004, contracts/structured-logging.md
 * test vector 1). Wraps a delegate sink; every event is validated by
 * {@link LogEvent} construction before it reaches the delegate. Violations
 * throw {@link IllegalArgumentException} naming the offending event and field.
 *
 * <p>Wire order at composition roots: emission site
 * -&gt; {@code WhitelistValidatingLogSink} -&gt; concrete sink
 * (JSON line / in-memory). The decorator is the single enforcement point, so
 * concrete sinks stay dumb serializers.
 */
public final class WhitelistValidatingLogSink implements StructuredLogSink {

    private final StructuredLogSink delegate;

    public WhitelistValidatingLogSink(StructuredLogSink delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    }

    @Override
    public void log(LogLevel level, String event, Instant at,
                    Map<IdentifierField, String> identifiers) {
        LogEvent validated = new LogEvent(level, event, at, identifiers);
        delegate.log(validated.level(), validated.event(), validated.at(),
                validated.identifiers());
    }
}
