package com.example.fanout.ingest;

import java.time.Instant;
import java.util.Objects;

/**
 * Decoded publish-ingest checkpoint record (PUBLISH schema: productId,
 * toVersion, publishedAt). Lighter than the bus event — fromVersion is not
 * needed for ingest correctness or rule-R4 selection.
 */
public record IngestedPublish(String publishId, String productId, String toVersion, Instant publishedAt) {

    public IngestedPublish {
        if (publishId == null || publishId.isBlank()) {
            throw new IllegalArgumentException("publishId must be non-blank");
        }
        if (productId == null || productId.isBlank()) {
            throw new IllegalArgumentException("productId must be non-blank");
        }
        if (toVersion == null || toVersion.isBlank()) {
            throw new IllegalArgumentException("toVersion must be non-blank");
        }
        Objects.requireNonNull(publishedAt, "publishedAt must not be null");
    }
}
