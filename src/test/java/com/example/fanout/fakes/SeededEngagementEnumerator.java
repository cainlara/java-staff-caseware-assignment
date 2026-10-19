package com.example.fanout.fakes;

import com.example.fanout.domain.EnumeratedEngagement;
import com.example.fanout.domain.EnumerationBatch;
import com.example.fanout.fanout.TransientFanOutException;
import com.example.fanout.ports.EngagementEnumerator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic {@link EngagementEnumerator} fake (spec B1/FR-001,
 * contracts/engagement-enumerator.md, research.md D1):
 *
 * <ul>
 *   <li><b>Seed dataset</b> — the same {@code seed} always yields the same
 *       firm and engagement ids; an explicit dataset may be injected instead
 *       (the fairness distribution is built that way).</li>
 *   <li><b>Failure injection</b> — {@link #failNextCalls(int)},
 *       {@link #failAlways()}, {@link #failAfterBatches(int)}; every failure is
 *       an unchecked exception thrown <i>after</i> the call has been counted
 *       and logged.</li>
 *   <li><b>Restartable</b> — any cursor (including one from a partially
 *       consumed run) is honored: strictly ascending ids above the exclusive
 *       lower bound.</li>
 *   <li><b>Call log</b> — {@code (databaseId, afterCursor, limit)} per call,
 *       in call order, for admission/resume assertions.</li>
 * </ul>
 *
 * <p>An unknown database id enumerates as empty (immediately done); a
 * database with no engagements above the cursor does the same. Validation
 * per the port contract: null/blank database id or {@code limit < 1} throws
 * {@link IllegalArgumentException}.
 */
public final class SeededEngagementEnumerator implements EngagementEnumerator {

    /** One recorded {@code nextBatch} call. */
    public record Call(String databaseId, Optional<String> afterCursor, int limit) {
    }

    private final Map<String, List<EnumeratedEngagement>> byFirm;
    private final List<Call> callLog = new ArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger failNext = new AtomicInteger();
    private final AtomicInteger failAfterBatches = new AtomicInteger(-1);
    private volatile boolean failForever;

    /** Seed-generated dataset: {@code firmCount} firms x {@code perFirm} engagements. */
    public SeededEngagementEnumerator(long seed, int firmCount, int perFirm) {
        if (firmCount < 0) {
            throw new IllegalArgumentException("firmCount must be >= 0");
        }
        if (perFirm < 0) {
            throw new IllegalArgumentException("perFirm must be >= 0");
        }
        Map<String, List<EnumeratedEngagement>> dataset = new TreeMap<>();
        for (int f = 0; f < firmCount; f++) {
            String firmId = String.format("f%d-%04d", seed, f);
            List<EnumeratedEngagement> engagements = new ArrayList<>(perFirm);
            for (int e = 0; e < perFirm; e++) {
                String engagementId = String.format("e%d-%04d-%08d", seed, f, e);
                engagements.add(new EnumeratedEngagement(engagementId, firmId, productId(seed, f)));
            }
            dataset.put(firmId, engagements);
        }
        this.byFirm = immutableCopy(dataset);
    }

    /** Explicit dataset: firms are enumerated in the given insertion order. */
    public SeededEngagementEnumerator(Map<String, List<EnumeratedEngagement>> dataset) {
        Objects.requireNonNull(dataset, "dataset must not be null");
        Map<String, List<EnumeratedEngagement>> sorted = new TreeMap<>();
        for (Map.Entry<String, List<EnumeratedEngagement>> entry : dataset.entrySet()) {
            String firmId = entry.getKey();
            if (firmId == null || firmId.isBlank()) {
                throw new IllegalArgumentException("firmId must be non-blank");
            }
            List<EnumeratedEngagement> engagements = new ArrayList<>();
            for (EnumeratedEngagement item : entry.getValue()) {
                Objects.requireNonNull(item, "dataset must not contain null");
                if (!item.firmId().equals(firmId)) {
                    throw new IllegalArgumentException(
                            "engagement " + item.engagementId() + " does not belong to " + firmId);
                }
                engagements.add(item);
            }
            engagements.sort((a, b) -> a.engagementId().compareTo(b.engagementId()));
            sorted.put(firmId, engagements);
        }
        this.byFirm = immutableCopy(sorted);
    }

    @Override
    public EnumerationBatch nextBatch(String customerDatabaseId,
                                      Optional<String> afterEngagementId, int limit) {
        if (customerDatabaseId == null || customerDatabaseId.isBlank()) {
            throw new IllegalArgumentException("customerDatabaseId must be non-blank");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }
        Objects.requireNonNull(afterEngagementId, "afterEngagementId must not be null");
        afterEngagementId.ifPresent(cursor -> {
            if (cursor.isBlank()) {
                throw new IllegalArgumentException("afterEngagementId must be non-blank when present");
            }
        });

        callLog.add(new Call(customerDatabaseId, afterEngagementId, limit));
        int call = calls.incrementAndGet();
        if (failForever || failNext.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
            throw new TransientFanOutException("injected enumeration failure on call " + call);
        }
        int failAfter = failAfterBatches.get();
        if (failAfter >= 0 && call > failAfter) {
            throw new TransientFanOutException(
                    "injected enumeration failure after " + failAfter + " batches");
        }

        List<EnumeratedEngagement> firmData = byFirm.get(customerDatabaseId);
        if (firmData == null || firmData.isEmpty()) {
            return EnumerationBatch.endOfEnumeration();
        }
        int startIndex = afterEngagementId
                .map(cursor -> upperBound(firmData, cursor))
                .orElse(0);
        if (startIndex >= firmData.size()) {
            return EnumerationBatch.endOfEnumeration();
        }
        int endIndex = Math.min(startIndex + limit, firmData.size());
        List<EnumeratedEngagement> items = List.copyOf(firmData.subList(startIndex, endIndex));
        return new EnumerationBatch(items,
                Optional.of(items.get(items.size() - 1).engagementId()), false);
    }

    /** Next n calls throw a transient enumeration failure. */
    public void failNextCalls(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("n must be >= 0");
        }
        failNext.set(n);
    }

    /** Every subsequent call throws (until {@link #clearFailures()}). */
    public void failAlways() {
        failForever = true;
    }

    /** The first k calls succeed; every later call throws. */
    public void failAfterBatches(int k) {
        if (k < 0) {
            throw new IllegalArgumentException("k must be >= 0");
        }
        failAfterBatches.set(k);
    }

    /** Drop every failure injection. */
    public void clearFailures() {
        failForever = false;
        failNext.set(0);
        failAfterBatches.set(-1);
    }

    /** Every recorded call, in order. */
    public List<Call> callLog() {
        synchronized (callLog) {
            return List.copyOf(callLog);
        }
    }

    /** Number of calls observed so far (including failing ones). */
    public int callCount() {
        return calls.get();
    }

    /** Firm ids of the seeded dataset, ascending. */
    public Set<String> firmIds() {
        return byFirm.keySet();
    }

    /** Engagement ids of one firm, ascending (empty when unknown). */
    public List<EnumeratedEngagement> dataset(String firmId) {
        return byFirm.getOrDefault(firmId, List.of());
    }

    private static int upperBound(List<EnumeratedEngagement> data, String cursor) {
        int low = 0;
        int high = data.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (data.get(mid).engagementId().compareTo(cursor) <= 0) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    private static String productId(long seed, int firmIndex) {
        return String.format("p%d-%04d", seed, firmIndex % 10);
    }

    private static Map<String, List<EnumeratedEngagement>> immutableCopy(
            Map<String, List<EnumeratedEngagement>> source) {
        Map<String, List<EnumeratedEngagement>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<EnumeratedEngagement>> entry : source.entrySet()) {
            copy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }
}
