package com.example.fanout.domain;

/**
 * One element of an enumeration batch — identifiers only (spec B1,
 * data-model.md EnumeratedEngagement). Deliberately not {@link EngagementRef}:
 * a pre-feature engagement's template version is unknown until the downstream
 * load, and this type must never carry a sentinel version or engagement
 * content (research.md D1).
 */
public record EnumeratedEngagement(String engagementId, String firmId, String productId) {

    public EnumeratedEngagement {
        Checks.requireNonBlank(engagementId, "engagementId");
        Checks.requireNonBlank(firmId, "firmId");
        Checks.requireNonBlank(productId, "productId");
    }
}
