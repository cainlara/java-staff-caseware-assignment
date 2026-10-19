package com.example.fanout.backfill;

import com.example.fanout.adapters.inmemory.InMemoryCheckpointStore;
import com.example.fanout.domain.EnumeratedEngagement;
import com.example.fanout.domain.FanOutConfig;
import com.example.fanout.fanout.AdmissionGate;
import com.example.fanout.fanout.BackoffWaiter;
import com.example.fanout.fanout.FanOutCursorRepository;
import com.example.fanout.fanout.PermitGuardedDownstream;
import com.example.fanout.fanout.RetryExecutor;
import com.example.fanout.fanout.TaskRunner;
import com.example.fanout.fakes.FakeClock;
import com.example.fanout.fakes.FakeDeadLetterStore;
import com.example.fanout.fakes.FakeFeatureFlags;
import com.example.fanout.fakes.InMemoryIndexWriter;
import com.example.fanout.fakes.RecordingFanOutMetrics;
import com.example.fanout.fakes.SeededEngagementEnumerator;
import com.example.fanout.fakes.SeededJitter;
import com.example.fanout.fakes.TestTaskRunner;
import com.example.fanout.fakes.VersionedLoadClient;
import com.example.fanout.ingest.IngestProgressRepository;
import com.example.fanout.ports.CapacityBudget;
import com.example.fanout.ports.FeatureFlags;
import com.example.fanout.ports.CheckpointKey;
import com.example.fanout.ports.CheckpointKind;
import com.example.fanout.ports.CheckpointStore;
import com.example.fanout.ports.DownstreamLoadClient;
import com.example.fanout.ports.IndexWriter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared deterministic backfill fixture: delivered fakes plus the seeded
 * enumerator fake, a fake clock, seeded jitter, and a test-controlled runner.
 * Every wait in backfill tests is signal-based or clock-driven — no sleeps
 * anywhere (spec FR-055).
 *
 * <p>Seams a test may swap before calling {@link #newWorker()}: {@link #store}
 * (checkpoint store, for crash wrappers), {@link #indexWriter},
 * {@link #loadClient}, {@link #runner}, {@link #waiter}.
 */
public final class BackfillFixture {

    static final String STALE_VERSION = "v1";
    public static final String EFFECTIVE_VERSION = "v2";
    static final Instant PUBLISHED_AT = Instant.parse("2026-10-18T10:00:00Z");

    final FakeFeatureFlags flags = new FakeFeatureFlags();
    final InMemoryCheckpointStore backing = new InMemoryCheckpointStore();
    final InMemoryIndexWriter index = new InMemoryIndexWriter();
    public final RecordingFanOutMetrics metrics = new RecordingFanOutMetrics();
    final FakeDeadLetterStore deadLetters = new FakeDeadLetterStore();
    final FakeClock clock = FakeClock.startingAt("2026-10-19T12:00:00Z");
    final VersionedLoadClient downstream = new VersionedLoadClient();
    final FanOutCursorRepository fanOutCursors = new FanOutCursorRepository(backing);
    public final BackfillConfig config;
    public final CapacityShares shares;
    public CapacityBudget budget;
    AdmissionGate gate;
    /** Seam: swapped for KillingFeatureFlags by the crash tests. */
    FeatureFlags gateFlags = flags;
    final SeededJitter jitter = new SeededJitter(42L);

    public SeededEngagementEnumerator enumerator = new SeededEngagementEnumerator(0L, 0, 0);
    public List<String> firmIds = List.of();
    public TaskRunner runner;
    public CheckpointStore store;
    public IndexWriter indexWriter;
    public DownstreamLoadClient loadClient;
    public BackoffWaiter waiter;
    public BackfillCursorRepository cursors;

    public BackfillFixture() {
        this(BackfillConfig.defaults());
    }

    public BackfillFixture(BackfillConfig config) {
        this.config = config;
        this.shares = config.shares();
        this.budget = shares.backfillBudget();
        this.runner = new TestTaskRunner(config.bBackfill());
        this.store = backing;
        this.indexWriter = index;
        this.loadClient = downstream;
        this.waiter = delay -> clock.advance(delay);
    }

    /** Seeded dataset of {@code firmCount} firms x {@code perFirm} engagements. */
    public void seed(long seed, int firmCount, int perFirm) {
        enumerator = new SeededEngagementEnumerator(seed, firmCount, perFirm);
        firmIds = List.copyOf(enumerator.firmIds());
        downstream.setDefaultVersion(STALE_VERSION);
        enableAll();
    }

    /** Explicit dataset (fairness and mixed-product distributions). */
    void seed(Map<String, List<EnumeratedEngagement>> dataset) {
        enumerator = new SeededEngagementEnumerator(dataset);
        firmIds = List.copyOf(enumerator.firmIds());
        downstream.setDefaultVersion(STALE_VERSION);
        enableAll();
    }

    /** Opt-in enablement for every product and firm of the current dataset. */
    void enableAll() {
        products().forEach(product -> flags.setEnabled(product, true));
        firmIds.forEach(firm -> flags.setEnabledFirm(firm, true));
    }

    /** Distinct product ids of the current dataset, ascending. */
    Set<String> products() {
        Set<String> products = new LinkedHashSet<>();
        for (String firm : firmIds) {
            for (EnumeratedEngagement item : enumerator.dataset(firm)) {
                products.add(item.productId());
            }
        }
        return products;
    }

    /** Ingest one publish so the product has an effective version. */
    void seedEffective(String productId, String publishId, String version) {
        var ingest = new IngestProgressRepository(backing);
        ingest.claimPublish(publishId, productId, version, PUBLISHED_AT);
        ingest.addProductPublish(productId, publishId);
    }

    /** Ingest an effective publish for every product of the current dataset. */
    public void seedEffectiveForAllProducts(String version) {
        products().forEach(product -> seedEffective(product, "pub-" + product, version));
    }

    /** Fresh worker over the fixture's current seams (simulates a restart too). */
    public BackfillWorker newWorker() {
        cursors = new BackfillCursorRepository(store);
        gate = AdmissionGate.forBackfill(gateFlags, metrics);
        PermitGuardedDownstream guard = new PermitGuardedDownstream(
                loadClient,
                budget,
                waiter,
                clock,
                metrics,
                config.permitGuardConfig(),
                jitter,
                gateFlags::haltSwitch,
                PermitGuardedDownstream.MetricNames.backfill());
        RetryExecutor retry = new RetryExecutor(
                config.retryPolicy(), waiter, clock, jitter, metrics, deadLetters);
        return new BackfillWorker(
                enumerator,
                firmIds,
                cursors,
                fanOutCursors,
                indexWriter,
                guard,
                retry,
                gate,
                metrics,
                deadLetters,
                runner,
                config,
                clock);
    }

    /**
     * Rewind one firm's cursor to "RUNNING" with an empty resume bound — the
     * replay seam (FR-052). {@code BackfillCursorRepository} is deliberately
     * monotonic, so the test drives the raw store value.
     */
    void resetCursor(String firmId) {
        store.write(new CheckpointKey(CheckpointKind.BACKFILL_BATCH, firmId),
                "RUNNING" + (char) 0x1F);
    }

    /** Engagement ids of the whole dataset, ascending per firm. */
    List<String> allEngagementIds() {
        List<String> ids = new ArrayList<>();
        for (String firm : firmIds) {
            for (EnumeratedEngagement item : enumerator.dataset(firm)) {
                ids.add(item.engagementId());
            }
        }
        return ids;
    }

    /** FanOutConfig the delivered engine tests use — kept for parity checks. */
    FanOutConfig fanOutConfig() {
        return FanOutConfig.defaults();
    }
}
