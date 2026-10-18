package com.example.fanout.ports;

import java.time.Duration;

/**
 * Counter/timer hooks for ingest, fan-out progress, downstream latency, retries,
 * dead-letter counts, and SLO measurement. No-op implementation now; a real
 * implementation is a later phase.
 *
 * <p><b>Confidentiality invariant (hard rule)</b>: engagement content MUST NEVER
 * appear in {@code name}, {@code delta}, {@code duration}, or {@code tags}. Only
 * identifiers (publish id, product id, firm id, engagement id, batch id) and
 * measurements are permitted. Metrics are observations, not a state store:
 * correctness MUST NOT depend on metric values.
 *
 * <p>Null/blank {@code name}, null {@code duration}, and negative {@code delta}
 * are rejected.
 */
public interface FanOutMetrics {

    void increment(String name, long delta, String... tags);

    void recordTime(String name, Duration duration, String... tags);
}
