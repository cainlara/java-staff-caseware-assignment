package com.example.fanout.ingest;

import java.time.Instant;
import java.util.Objects;

/**
 * Decoded withdraw-ingest checkpoint record (WITHDRAW schema: productId,
 * version, status, matchedPublishId-or-empty, withdrawnAt). The existence of
 * the record is the duplicate claim; status transitions DEFERRED -> RESOLVED
 * on resolution.
 */
public record IngestedWithdraw(
        String withdrawId,
        String productId,
        String version,
        WithdrawStatus status,
        String matchedPublishId,
        Instant withdrawnAt) {

    public IngestedWithdraw {
        if (withdrawId == null || withdrawId.isBlank()) {
            throw new IllegalArgumentException("withdrawId must be non-blank");
        }
        if (productId == null || productId.isBlank()) {
            throw new IllegalArgumentException("productId must be non-blank");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must be non-blank");
        }
        Objects.requireNonNull(status, "status must not be null");
        if (status == WithdrawStatus.RESOLVED
                && (matchedPublishId == null || matchedPublishId.isBlank())) {
            throw new IllegalArgumentException("matchedPublishId must be non-blank when RESOLVED");
        }
        Objects.requireNonNull(withdrawnAt, "withdrawnAt must not be null");
    }
}
