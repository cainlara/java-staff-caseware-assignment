package com.example.fanout.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Event: a product team published a template version.
 *
 * <p>Idempotency: consumers key on {@code publishId} (scoped by {@code productId}).
 * Natural keys {@code publishId} and {@code productId} are mandatory, non-blank.
 */
public record TemplatePublished(
        String publishId,
        String productId,
        String fromVersion,
        String toVersion,
        Instant publishedAt) {

    public TemplatePublished {
        Checks.requireNonBlank(publishId, "publishId");
        Checks.requireNonBlank(productId, "productId");
        Checks.requireNonBlank(fromVersion, "fromVersion");
        Checks.requireNonBlank(toVersion, "toVersion");
        Objects.requireNonNull(publishedAt, "publishedAt must not be null");
    }
}
