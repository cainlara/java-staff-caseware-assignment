package com.example.fanout.ingest;

import com.example.fanout.domain.FanOutTrigger;
import com.example.fanout.domain.FanOutTriggerKind;
import com.example.fanout.domain.TemplatePublished;
import com.example.fanout.domain.TemplateWithdrawn;
import com.example.fanout.ports.FanOutMetrics;
import com.example.fanout.ports.FanOutTriggerSink;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Idempotent event ingestion (contracts/ingest-service.md). Processing order:
 * validate -> checkpoint claim -> per-product bookkeeping -> trigger emission.
 * The checkpoint store is the sole progress truth; triggers are at-most-once
 * in-process signals emitted after a successful claim (R5). Never loads
 * engagement content and never touches the engagement-load dependency (R6).
 */
public final class IngestService {

    private final IngestProgressRepository progress;
    private final FanOutTriggerSink triggers;
    private final FanOutMetrics metrics;

    public IngestService(
            IngestProgressRepository progress, FanOutTriggerSink triggers, FanOutMetrics metrics) {
        this.progress = Objects.requireNonNull(progress, "progress must not be null");
        this.triggers = Objects.requireNonNull(triggers, "triggers must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
    }

    /** Ingest one published-template event. */
    public IngestOutcome ingest(TemplatePublished event) {
        Objects.requireNonNull(event, "event must not be null");
        if (!progress.claimPublish(
                event.publishId(), event.productId(), event.toVersion(), event.publishedAt())) {
            duplicate(event.publishId(), event.productId());
            return IngestOutcome.duplicate(event.publishId());
        }
        long sequence = progress.nextProductSequence(event.productId());
        progress.addProductPublish(event.productId(), event.publishId());

        List<String> resolved = resolvePendingWithdraws(event);

        triggers.emit(new FanOutTrigger(
                FanOutTriggerKind.PUBLISH, event.publishId(), event.productId(), sequence, null));
        for (String withdrawId : resolved) {
            triggers.emit(new FanOutTrigger(
                    FanOutTriggerKind.WITHDRAW,
                    withdrawId,
                    event.productId(),
                    sequence,
                    event.publishId()));
        }
        metrics.increment("ingest.published", 1, "product_id=" + event.productId());
        return IngestOutcome.ingested(event.publishId(), resolved);
    }

    /** Ingest one withdrawn-template event. */
    public IngestOutcome ingest(TemplateWithdrawn event) {
        Objects.requireNonNull(event, "event must not be null");
        if (!progress.claimWithdraw(
                event.withdrawId(), event.productId(), event.version(), event.withdrawnAt())) {
            duplicate(event.withdrawId(), event.productId());
            return IngestOutcome.duplicate(event.withdrawId());
        }
        metrics.increment("ingest.withdrawn", 1, "product_id=" + event.productId());

        List<IngestedPublish> publishes = loadProductPublishes(event.productId());
        var matched = EffectiveVersion.matchPublish(publishes, event.version());
        if (matched.isPresent()) {
            progress.resolveWithdraw(event.withdrawId(), matched.get().publishId());
            triggers.emit(new FanOutTrigger(
                    FanOutTriggerKind.WITHDRAW,
                    event.withdrawId(),
                    event.productId(),
                    sequenceOf(event.productId()),
                    matched.get().publishId()));
            metrics.increment("ingest.resolved", 1, "product_id=" + event.productId());
            return IngestOutcome.ingested(event.withdrawId(), List.of());
        }
        progress.deferWithdraw(event.productId(), event.version(), event.withdrawId());
        metrics.increment("ingest.deferred", 1, "product_id=" + event.productId());
        return IngestOutcome.deferred(event.withdrawId());
    }

    // ---- helpers ----

    private List<String> resolvePendingWithdraws(TemplatePublished event) {
        List<String> pending =
                progress.takePendingWithdraws(event.productId(), event.toVersion());
        List<String> resolved = new ArrayList<>();
        for (String withdrawId : pending) {
            var record = progress.withdraw(withdrawId);
            if (record.isEmpty() || record.get().status() == WithdrawStatus.RESOLVED) {
                continue;
            }
            progress.resolveWithdraw(withdrawId, event.publishId());
            resolved.add(withdrawId);
        }
        if (!resolved.isEmpty()) {
            metrics.increment("ingest.resolved", 1, "product_id=" + event.productId());
        }
        return List.copyOf(resolved);
    }

    private List<IngestedPublish> loadProductPublishes(String productId) {
        List<IngestedPublish> publishes = new ArrayList<>();
        for (String publishId : progress.productPublishes(productId)) {
            progress.publish(publishId).ifPresent(publishes::add);
        }
        return publishes;
    }

    private long sequenceOf(String productId) {
        return progress.productPublishes(productId).size();
    }

    private void duplicate(String eventId, String productId) {
        metrics.increment("ingest.duplicate", 1, "product_id=" + productId);
    }
}
