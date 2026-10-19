package com.example.fanout.observability;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * One edge emitted by {@link AlertEvaluator} (spec data-model.md): a
 * transition of one condition into active ({@code active=true}, fire) or out
 * of it ({@code active=false}, clear). Identifiers keys are always a subset
 * of {@link IdentifierField}; values are identifiers or measurements only.
 */
public record AlertEvaluation(
        AlertCondition condition,
        boolean active,
        Instant at,
        Map<IdentifierField, String> identifiers) {

    public AlertEvaluation {
        Objects.requireNonNull(condition, "condition must not be null");
        Objects.requireNonNull(at, "at must not be null");
        Objects.requireNonNull(identifiers, "identifiers must not be null");
        identifiers.keySet().forEach(field -> {
            if (field == null) {
                throw new IllegalArgumentException("identifier field must not be null");
            }
        });
        identifiers = Map.copyOf(identifiers);
    }
}
