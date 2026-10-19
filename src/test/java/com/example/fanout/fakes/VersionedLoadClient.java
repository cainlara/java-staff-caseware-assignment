package com.example.fanout.fakes;

import com.example.fanout.domain.EngagementRef;
import com.example.fanout.fanout.TransientFanOutException;
import com.example.fanout.ports.DownstreamLoadClient;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Controllable {@link DownstreamLoadClient} fake for backfill tests: returns a
 * configured captured version per engagement (identifier-only payload), keeps
 * an exact in-flight counter plus a high-water tracker for permit-ceiling
 * assertions, records the engagement-id call log, and supports transient and
 * permanent failure injection plus an uncaught-failure hook.
 *
 * <p>No sleeps anywhere: latency is zero and every test waits on counters,
 * latches, or the injected clock (spec FR-055).
 */
public final class VersionedLoadClient implements DownstreamLoadClient {

    public static final String DEFAULT_VERSION = "v-captured";

    private final Map<String, String> versions = new ConcurrentHashMap<>();
    private final Set<String> alwaysFailing = ConcurrentHashMap.newKeySet();
    private final List<String> callLog = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger failNext = new AtomicInteger();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger max = new AtomicInteger();
    private volatile String defaultVersion = DEFAULT_VERSION;
    private volatile Consumer<EngagementRef> beforeLoad;

    @Override
    public String loadEngagement(EngagementRef ref) {
        Objects.requireNonNull(ref, "ref must not be null");
        return loadEngagement(ref.engagementId(), ref.firmId(), ref.productId());
    }

    @Override
    public String loadEngagement(String engagementId, String firmId, String productId) {
        Objects.requireNonNull(engagementId, "engagementId must not be null");
        Objects.requireNonNull(firmId, "firmId must not be null");
        Objects.requireNonNull(productId, "productId must not be null");
        callLog.add(engagementId);
        int current = inFlight.incrementAndGet();
        max.accumulateAndGet(current, Math::max);
        try {
            Consumer<EngagementRef> hook = beforeLoad;
            if (hook != null) {
                hook.accept(new EngagementRef(engagementId, firmId, productId,
                        DEFAULT_VERSION, "EU"));
            }
            if (alwaysFailing.contains(engagementId)) {
                throw new TransientFanOutException(
                        "injected downstream failure for " + engagementId);
            }
            if (failNext.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                throw new TransientFanOutException(
                        "injected downstream failure for " + engagementId);
            }
            return versions.getOrDefault(engagementId, defaultVersion);
        } finally {
            inFlight.decrementAndGet();
        }
    }

    /** Captured version returned for one engagement. */
    public void setVersion(String engagementId, String version) {
        requireNonBlank(engagementId, "engagementId");
        requireNonBlank(version, "version");
        versions.put(engagementId, version);
    }

    /** Captured version returned for every engagement without an override. */
    public void setDefaultVersion(String version) {
        requireNonBlank(version, "version");
        defaultVersion = version;
    }

    /** Next n calls fail transiently. */
    public void failNextCalls(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("n must be >= 0");
        }
        failNext.set(n);
    }

    /** Every call for one engagement fails transiently (poisoned engagement). */
    public void failAlwaysFor(String engagementId) {
        requireNonBlank(engagementId, "engagementId");
        alwaysFailing.add(engagementId);
    }

    /** Drop every failure injection. */
    public void clearFailures() {
        failNext.set(0);
        alwaysFailing.clear();
    }

    /** Hook invoked at the start of every call, before the version is read. */
    public void beforeLoad(Consumer<EngagementRef> hook) {
        this.beforeLoad = hook;
    }

    /** Engagement ids in call order. */
    public List<String> callLog() {
        synchronized (callLog) {
            return List.copyOf(callLog);
        }
    }

    /** Total calls observed (including failing ones). */
    public int calls() {
        return callLog.size();
    }

    /** Loads executing right now. */
    public int inFlight() {
        return inFlight.get();
    }

    /** Highest concurrent-load count ever observed. */
    public int max() {
        return max.get();
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must be non-blank");
        }
    }
}
