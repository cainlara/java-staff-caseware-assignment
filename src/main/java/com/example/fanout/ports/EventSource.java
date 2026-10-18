package com.example.fanout.ports;

import com.example.fanout.domain.TemplatePublished;
import com.example.fanout.domain.TemplateWithdrawn;
import java.util.Optional;

/**
 * Receives template lifecycle events from the at-least-once event bus.
 *
 * <p><b>Idempotency keys</b>: {@link TemplatePublished} on {@code publishId}
 * (scoped by {@code productId}); {@link TemplateWithdrawn} on {@code withdrawId}
 * (scoped by {@code productId}).
 *
 * <p><b>Consistency</b>: delivery is at-least-once — the same event may be
 * observed multiple times, in any order, with arbitrary delay. Consumers MUST
 * deduplicate by the key above before applying effects. Exactly-once is never
 * assumed.
 *
 * <p><b>Hot path</b>: polled events trigger the publish hot path, which may only
 * write pending-index metadata rows (never load engagement content).
 */
public interface EventSource {

    /** Next published-template event, or {@link Optional#empty()} if none available. */
    Optional<TemplatePublished> pollPublish();

    /** Next withdrawn-template event, or {@link Optional#empty()} if none available. */
    Optional<TemplateWithdrawn> pollWithdraw();
}
