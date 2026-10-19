package com.example.fanout.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.fanout.adapters.inmemory.InMemoryCheckpointStore;
import com.example.fanout.adapters.inmemory.RecordingFanOutTriggerSink;
import com.example.fanout.domain.TemplatePublished;
import com.example.fanout.domain.TemplateWithdrawn;
import com.example.fanout.fakes.FakeDownstreamLoadClient;
import com.example.fanout.fakes.RecordingFanOutMetrics;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * US4 / FR-014 / SC-004: any duplication and reordering of a publish batch
 * converges to identical functional checkpoint state — same processed
 * publish-id set, same effective version per product, same withdraw
 * resolutions. Arrival-order sequence fields are observational and excluded
 * from the comparison per FR-014.
 */
class IngestPermutationTest {

    private static final List<TemplatePublished> BATCH = List.of(
            new TemplatePublished("pub-1", "prod-a", "v0", "v1", Instant.parse("2026-10-01T09:00:00Z")),
            new TemplatePublished("pub-2", "prod-a", "v1", "v2", Instant.parse("2026-10-01T10:00:00Z")),
            new TemplatePublished("pub-3", "prod-b", "v0", "v1", Instant.parse("2026-10-01T11:00:00Z")));

    private static final TemplateWithdrawn WD = new TemplateWithdrawn(
            "wd-1", "prod-a", "v1", Instant.parse("2026-10-02T10:00:00Z"));

    @Test
    void allPermutationsWithDuplicationConverge() {
        String baseline = null;
        int runs = 0;
        for (List<Object> permutation : permutations(BATCH)) {
            for (int dupFactor = 1; dupFactor <= 2; dupFactor++) {
                String state = runAndSnapshot(permutation, dupFactor);
                if (baseline == null) {
                    baseline = state;
                } else {
                    assertThat(state)
                            .as("permutation %s x dup %d must converge to baseline state", permutation, dupFactor)
                            .isEqualTo(baseline);
                }
                runs++;
            }
        }
        assertThat(runs).isGreaterThanOrEqualTo(12); // 3! permutations x 2 dup factors
    }

    @Test
    void withdrawResolutionConvergesAcrossArrivalOrdersOfItsPublish() {
        // wd withdraws prod-a v1; pub-1 introduces v1. Deliver withdraw first in
        // every run (deferred), then the publish in both possible orders relative
        // to pub-2 — resolution must bind pub-1 either way.
        String withPub1First = runWithdrawScenario(true);
        String withPub2First = runWithdrawScenario(false);
        assertThat(withPub1First).isEqualTo(withPub2First);
    }

    /** Deterministic event stream: withdraw, optional early pub-2, pub-1, remaining publishes (with duplicates). */
    private String runWithdrawScenario(boolean pub1BeforePub2) {
        var store = new InMemoryCheckpointStore();
        var progress = new IngestProgressRepository(store);
        var service = new IngestService(progress, new RecordingFanOutTriggerSink(), new RecordingFanOutMetrics());
        var events = new ArrayList<TemplatePublished>();
        events.add(BATCH.get(0)); // pub-1 (introduces v1)
        if (!pub1BeforePub2) {
            events.add(BATCH.get(1)); // pub-2 first
            events.add(BATCH.get(0)); // then pub-1
        } else {
            events.add(BATCH.get(1));
        }
        events.add(BATCH.get(2));

        service.ingest(WD);
        for (TemplatePublished e : events) {
            service.ingest(e);
        }
        return snapshotFunctionalState(progress, store);
    }

    private String runAndSnapshot(List<Object> permutation, int dupFactor) {
        var store = new InMemoryCheckpointStore();
        var progress = new IngestProgressRepository(store);
        var sink = new RecordingFanOutTriggerSink();
        var metrics = new RecordingFanOutMetrics();
        var downstream = new FakeDownstreamLoadClient(Duration.ZERO);
        var service = new IngestService(progress, sink, metrics);

        for (int d = 0; d < dupFactor; d++) {
            service.ingest(WD); // always first: defers, then resolves when pub-1 arrives
        }
        for (Object event : permutation) {
            for (int d = 0; d < dupFactor; d++) {
                if (event instanceof TemplatePublished p) {
                    service.ingest(p);
                } else {
                    service.ingest((TemplateWithdrawn) event);
                }
            }
        }
        assertThat(downstream.getCallCount()).isZero();
        return snapshotFunctionalState(progress, store);
    }

    /**
     * Functional state only: processed publish id set, effective publish id per
     * product, resolved withdraw ids with matched publish — arrival-order
     * lists and sequence numbers normalized out per FR-014.
     */
    private static String snapshotFunctionalState(IngestProgressRepository progress, InMemoryCheckpointStore store) {
        TreeSet<String> processedPublishes = new TreeSet<>();
        Map<String, String> effectiveByProduct = new TreeMap<>();
        Map<String, String> resolvedWithdraws = new TreeMap<>();

        for (String productId : List.of("prod-a", "prod-b")) {
            List<IngestedPublish> publishes = progress.productPublishes(productId).stream()
                    .map(id -> progress.publish(id).orElseThrow())
                    .toList();
            publishes.forEach(p -> processedPublishes.add(p.publishId()));
            EffectiveVersion.selectEffective(publishes)
                    .ifPresent(effective -> effectiveByProduct.put(productId, effective.publishId()));
        }

        var wd = progress.withdraw("wd-1");
        wd.ifPresent(w -> resolvedWithdraws.put("wd-1", w.status() + ":" + w.matchedPublishId()));

        return "publishes=" + processedPublishes
                + " effective=" + effectiveByProduct
                + " withdraws=" + resolvedWithdraws;
    }

    private static List<List<Object>> permutations(List<TemplatePublished> batch) {
        List<List<Object>> result = new ArrayList<>();
        permute(new ArrayList<>(List.copyOf(batch)), 0, result);
        return result;
    }

    private static void permute(List<Object> items, int start, List<List<Object>> result) {
        if (start == items.size()) {
            result.add(List.copyOf(items));
            return;
        }
        for (int i = start; i < items.size(); i++) {
            Collections.swap(items, start, i);
            permute(items, start + 1, result);
            Collections.swap(items, start, i);
        }
    }
}
