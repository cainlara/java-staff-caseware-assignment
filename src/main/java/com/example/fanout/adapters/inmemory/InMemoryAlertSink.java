package com.example.fanout.adapters.inmemory;

import com.example.fanout.observability.AlertCondition;
import com.example.fanout.observability.AlertEvaluation;
import com.example.fanout.observability.IdentifierField;
import com.example.fanout.ports.AlertSink;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Recording {@link AlertSink} for tests and the e2e demo: captures every
 * fire/clear edge as an {@link AlertEvaluation} for assertion.
 */
public final class InMemoryAlertSink implements AlertSink {

    private final List<AlertEvaluation> fires = new CopyOnWriteArrayList<>();
    private final List<AlertEvaluation> clears = new CopyOnWriteArrayList<>();

    @Override
    public void fire(AlertCondition condition, Instant at,
                     Map<IdentifierField, String> identifiers) {
        fires.add(new AlertEvaluation(condition, true, at, Objects.requireNonNullElseGet(
                identifiers, Map::of)));
    }

    @Override
    public void clear(AlertCondition condition, Instant at,
                      Map<IdentifierField, String> identifiers) {
        clears.add(new AlertEvaluation(condition, false, at, Objects.requireNonNullElseGet(
                identifiers, Map::of)));
    }

    public List<AlertEvaluation> fires() {
        return List.copyOf(fires);
    }

    public List<AlertEvaluation> clears() {
        return List.copyOf(clears);
    }
}
