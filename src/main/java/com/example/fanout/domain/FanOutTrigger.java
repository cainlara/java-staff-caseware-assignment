package com.example.fanout.domain;

import java.util.Objects;

/**
 * Internal signal that one resolved event awaits fan-out. Emitted by the ingest
 * service after a successful checkpoint claim (at-most-once per ingest
 * transition); the checkpoint store remains the durable truth.
 *
 * <p><b>Consumer idempotency key</b>: {@code (kind, eventId, productId)}.
 *
 * <p>Payload carries identifiers and measurements only — never engagement
 * content. {@code ingestSequence} is the per-product arrival-order sequence
 * assigned at ingest (observational).
 */
public record FanOutTrigger(
        FanOutTriggerKind kind,
        String eventId,
        String productId,
        long ingestSequence,
        String matchedPublishId) {

    public FanOutTrigger {
        Objects.requireNonNull(kind, "kind must not be null");
        Checks.requireNonBlank(eventId, "eventId");
        Checks.requireNonBlank(productId, "productId");
        Checks.requireAtLeast(ingestSequence, 1L, "ingestSequence");
        if (kind == FanOutTriggerKind.WITHDRAW) {
            Checks.requireNonBlank(matchedPublishId, "matchedPublishId");
        } else if (matchedPublishId != null) {
            throw new IllegalArgumentException("matchedPublishId must be null when kind is PUBLISH");
        }
    }
}
