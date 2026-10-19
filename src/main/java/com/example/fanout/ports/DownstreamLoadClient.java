package com.example.fanout.ports;

import com.example.fanout.domain.EngagementRef;

/**
 * Calls the shared downstream engagement-load service (owned by another team,
 * ~1 minute per call). <b>Off the hot path only.</b>
 *
 * <p><b>Idempotency</b>: read-only; the same {@link EngagementRef} MAY be loaded
 * repeatedly. The result is a point-in-time observation of the engagement's
 * current template version, not a monotonic guarantee.
 *
 * <p><b>Latency and capacity</b>: real latency is expected to be ~60 s per call.
 * Concurrency is governed by {@link CapacityBudget} (C = 20 in flight,
 * configurable); this port does not self-limit. Test fakes simulate latency
 * (default ~60 s simulated) and support failure injection.
 *
 * <p><b>Hot path prohibition</b>: MUST NOT be called on the publish hot path
 * (the path that writes PENDING rows from index metadata). Legitimate callers
 * (later phases): revalidation, summary generation, backfill — always behind
 * {@link CapacityBudget#tryAcquire()}.
 *
 * <p>Returns a non-blank template version identifier. Downstream failures
 * surface as unchecked exceptions; retry/backoff is a later phase.
 */
public interface DownstreamLoadClient {

    String loadEngagement(EngagementRef ref);

    /**
     * Capture by bare identity — the backfill path, where a pre-feature
     * engagement's current version is unknown until this call returns
     * (research.md D1: {@link EngagementRef} would force a sentinel version).
     * Delivered implementors keep compiling: the default fails loudly until a
     * client opts into identifier capture; every backfill-relevant client
     * overrides it.
     *
     * @throws UnsupportedOperationException when the client has not overridden it
     */
    default String loadEngagement(String engagementId, String firmId, String productId) {
        throw new UnsupportedOperationException(
                "identifier capture not implemented by " + getClass().getName());
    }
}
