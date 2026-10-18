package com.example.fanout.ingest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Rule-R4 effective-version selection as a pure function over already-ingested
 * publishes. Never stores "current version" state — always derived, so any
 * arrival order yields the same result (permutation convergence).
 *
 * <p>Ordering: greatest {@code publishedAt} wins; ties break on greater
 * {@code publishId} lexicographically. Version strings are opaque — no
 * version-number arithmetic.
 */
public final class EffectiveVersion {

    private static final Comparator<IngestedPublish> R4_ORDER =
            Comparator.comparingLong((IngestedPublish p) -> p.publishedAt().toEpochMilli())
                    .thenComparing(IngestedPublish::publishId);

    private EffectiveVersion() {
    }

    /** Publish with the greatest (publishedAt, publishId) pair, if any. */
    public static Optional<IngestedPublish> selectEffective(List<IngestedPublish> publishes) {
        Objects.requireNonNull(publishes, "publishes must not be null");
        return publishes.stream().max(R4_ORDER);
    }

    /**
     * Among publishes with {@code toVersion == version}, the one with the
     * greatest (publishedAt, publishId) pair — the publish a deferred withdraw
     * for that version binds to (rule R3). Empty if none matches.
     */
    public static Optional<IngestedPublish> matchPublish(List<IngestedPublish> publishes, String version) {
        Objects.requireNonNull(publishes, "publishes must not be null");
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must be non-blank");
        }
        List<IngestedPublish> matching = new ArrayList<>();
        for (IngestedPublish p : publishes) {
            if (p.toVersion().equals(version)) {
                matching.add(p);
            }
        }
        return selectEffective(matching);
    }
}
