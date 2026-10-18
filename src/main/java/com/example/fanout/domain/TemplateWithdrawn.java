package com.example.fanout.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Event: a published template version was retracted.
 *
 * <p>Idempotency: consumers key on {@code withdrawId} (scoped by {@code productId}).
 * Natural keys {@code withdrawId} and {@code productId} are mandatory, non-blank.
 */
public record TemplateWithdrawn(
        String withdrawId,
        String productId,
        String version,
        Instant withdrawnAt) {

    public TemplateWithdrawn {
        Checks.requireNonBlank(withdrawId, "withdrawId");
        Checks.requireNonBlank(productId, "productId");
        Checks.requireNonBlank(version, "version");
        Objects.requireNonNull(withdrawnAt, "withdrawnAt must not be null");
    }
}
