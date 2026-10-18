package com.example.fanout.ingest;

import java.util.List;
import java.util.Objects;

/**
 * Result of one {@link IngestService} ingest call. Returned, never thrown, for
 * the duplicate and deferred paths; null arguments throw instead (defensive
 * rejection — no checkpoint, no trigger).
 *
 * <ul>
 *   <li>{@link IngestStatus#INGESTED} — newly claimed; exactly one trigger
 *       emitted (publish, or an immediately resolved withdraw).</li>
 *   <li>{@link IngestStatus#DUPLICATE} — event id already checkpointed; no
 *       trigger; duplicate counter incremented.</li>
 *   <li>{@link IngestStatus#DEFERRED} — withdraw recorded as deferred; no
 *       trigger.</li>
 *   <li>{@link IngestStatus#REJECTED} — invalid input; no checkpoint, no
 *       trigger.</li>
 * </ul>
 *
 * <p>{@code resolvedWithdrawIds} is non-empty only on INGESTED publish
 * outcomes that resolved deferred withdraws.
 */
public record IngestOutcome(IngestStatus status, String eventId, List<String> resolvedWithdrawIds) {

    public IngestOutcome {
        Objects.requireNonNull(status, "status must not be null");
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId must be non-blank");
        }
        Objects.requireNonNull(resolvedWithdrawIds, "resolvedWithdrawIds must not be null");
        resolvedWithdrawIds.forEach(id -> {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("resolvedWithdrawIds must not contain blank ids");
            }
        });
        resolvedWithdrawIds = List.copyOf(resolvedWithdrawIds);
    }

    public static IngestOutcome ingested(String eventId, List<String> resolvedWithdrawIds) {
        return new IngestOutcome(IngestStatus.INGESTED, eventId, resolvedWithdrawIds);
    }

    public static IngestOutcome duplicate(String eventId) {
        return new IngestOutcome(IngestStatus.DUPLICATE, eventId, List.of());
    }

    public static IngestOutcome deferred(String eventId) {
        return new IngestOutcome(IngestStatus.DEFERRED, eventId, List.of());
    }
}
