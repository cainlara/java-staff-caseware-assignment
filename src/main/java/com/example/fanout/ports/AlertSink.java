package com.example.fanout.ports;

import com.example.fanout.observability.AlertCondition;
import com.example.fanout.observability.IdentifierField;
import java.time.Instant;
import java.util.Map;

/**
 * Edge-triggered alert notifications (spec FR-006, contracts/alert-hooks.md).
 * Only the {@code AlertEvaluator} calls this port — engines never do.
 *
 * <p>Identifiers only, never engagement content (hard rule). Delivery
 * (paging/chat/email) is out of scope: this is the in-process seam; a
 * runtime shell bridges to the ops alerting system (TODO register).
 */
public interface AlertSink {

    void fire(AlertCondition condition, Instant at, Map<IdentifierField, String> identifiers);

    void clear(AlertCondition condition, Instant at, Map<IdentifierField, String> identifiers);
}
