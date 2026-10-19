package com.example.fanout.ports;

import com.example.fanout.observability.IdentifierField;
import com.example.fanout.observability.LogLevel;
import java.time.Instant;
import java.util.Map;

/**
 * Port for structured, whitelist-validated log emission (spec FR-004,
 * contracts/structured-logging.md).
 *
 * <p><b>Confidentiality invariant (hard rule, FR-013)</b>: the payload is
 * identifiers and measurements only — engagement content, free-text messages,
 * and stack traces MUST NEVER reach a sink. {@code identifiers} is keyed by
 * {@link IdentifierField}, so a non-whitelisted field cannot be represented.
 *
 * <p>{@code event} is a dotted lower_snake name ({@code ingest.claimed},
 * {@code fanout.chunk.completed}, …). Emission stays at chunk/batch cadence —
 * never per-engagement row on the hot path.
 *
 * <p>Observability is a read-only side channel: correctness MUST NOT depend
 * on whether a sink received or dropped an event (constitution II).
 */
public interface StructuredLogSink {

    void log(LogLevel level, String event, Instant at, Map<IdentifierField, String> identifiers);
}
