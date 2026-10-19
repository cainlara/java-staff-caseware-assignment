package com.example.fanout.fakes;

import com.example.fanout.domain.EngagementRef;
import com.example.fanout.fanout.TransientFanOutException;
import com.example.fanout.ports.EngagementEligibilitySource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * In-memory {@link EngagementEligibilitySource} fake (contracts/
 * engagement-eligibility-source.md): lists keyed by (productId, publishId)
 * and (productId, version), streamed in contract order (firm id asc,
 * engagement id asc) regardless of insertion order, with failNextStream(n)
 * failure injection. Never touches DownstreamLoadClient (spec FR-032).
 */
public final class FakeEligibilitySource implements EngagementEligibilitySource {

    private static final Comparator<EngagementRef> CONTRACT_ORDER =
            Comparator.comparing(EngagementRef::firmId).thenComparing(EngagementRef::engagementId);

    private final Map<String, Map<String, List<EngagementRef>>> eligible = new ConcurrentHashMap<>();
    private final Map<String, Map<String, List<EngagementRef>>> offering = new ConcurrentHashMap<>();
    private final AtomicInteger failNext = new AtomicInteger();

    @Override
    public Stream<EngagementRef> streamEligible(String productId, String publishId) {
        requireNonBlank(productId, "productId");
        requireNonBlank(publishId, "publishId");
        return stream(rows(eligible, productId, publishId));
    }

    @Override
    public Stream<EngagementRef> streamOffering(String productId, String version) {
        requireNonBlank(productId, "productId");
        requireNonBlank(version, "version");
        return stream(rows(offering, productId, version));
    }

    /** Append eligible rows for one publish (order-insensitive; sorted on read). */
    public void seedEligible(String productId, String publishId, List<EngagementRef> refs) {
        requireNonBlank(productId, "productId");
        requireNonBlank(publishId, "publishId");
        Objects.requireNonNull(refs, "refs must not be null");
        eligible.computeIfAbsent(productId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(publishId, k -> synchronizedList())
                .addAll(refs);
    }

    /** Append offering rows for one version (order-insensitive; sorted on read). */
    public void seedOffering(String productId, String version, List<EngagementRef> refs) {
        requireNonBlank(productId, "productId");
        requireNonBlank(version, "version");
        Objects.requireNonNull(refs, "refs must not be null");
        offering.computeIfAbsent(productId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(version, k -> synchronizedList())
                .addAll(refs);
    }

    /**
     * Scale generator (spec FR-072): appends count deterministic engagement
     * refs for one firm - zero-padded engagement ids so stream order equals
     * seed order within the firm.
     */
    public List<EngagementRef> seedEligible(String productId, String publishId,
                                            String firmId, int count) {
        requireNonBlank(firmId, "firmId");
        if (count < 0) {
            throw new IllegalArgumentException("count must be >= 0");
        }
        List<EngagementRef> refs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            refs.add(new EngagementRef(
                    firmId + "-eng-" + String.format("%06d", i),
                    firmId,
                    productId,
                    "v1",
                    "EU"));
        }
        seedEligible(productId, publishId, refs);
        return refs;
    }

    /** Next n stream calls (either method) throw a transient failure. */
    public void failNextStream(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("n must be >= 0");
        }
        failNext.set(n);
    }

    private Stream<EngagementRef> stream(List<EngagementRef> rows) {
        if (failNext.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
            throw new TransientFanOutException("injected eligibility stream failure");
        }
        if (rows == null) {
            return Stream.empty();
        }
        List<EngagementRef> snapshot;
        synchronized (rows) {
            snapshot = rows.stream().sorted(CONTRACT_ORDER).toList();
        }
        return snapshot.stream();
    }

    private static List<EngagementRef> rows(Map<String, Map<String, List<EngagementRef>>> byKey,
                                            String productId, String secondKey) {
        Map<String, List<EngagementRef>> inner = byKey.get(productId);
        if (inner == null) {
            return null;
        }
        return inner.get(secondKey);
    }

    private static List<EngagementRef> synchronizedList() {
        return Collections.synchronizedList(new ArrayList<>());
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must be non-blank");
        }
    }
}
