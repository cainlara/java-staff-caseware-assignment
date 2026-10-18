package com.example.fanout.domain;

/**
 * Lightweight handle to one engagement file — the only engagement-related payload
 * permitted off the hot path. Never carries engagement content.
 *
 * <p>{@code region} is an opaque identifier; residency enforcement is a later phase.
 * Natural keys {@code engagementId} and {@code firmId} are mandatory, non-blank.
 */
public record EngagementRef(
        String engagementId,
        String firmId,
        String productId,
        String currentVersion,
        String region) {

    public EngagementRef {
        Checks.requireNonBlank(engagementId, "engagementId");
        Checks.requireNonBlank(firmId, "firmId");
        Checks.requireNonBlank(productId, "productId");
        Checks.requireNonBlank(currentVersion, "currentVersion");
        Checks.requireNonBlank(region, "region");
    }
}
