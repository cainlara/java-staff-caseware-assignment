package com.example.fanout.observability;

/**
 * Structured-log levels (spec FR-004, data-model volume discipline).
 *
 * <p>{@code INFO}, {@code WARN}, {@code ERROR} only — no {@code DEBUG} /
 * {@code TRACE}, because hot-path debug logging is exactly the volume and
 * confidentiality risk this component refuses.
 */
public enum LogLevel {

    /** Normal lifecycle event (claim, chunk started, run completed). */
    INFO,

    /** Degraded but handled event (deferred, retry, saturation). */
    WARN,

    /** Exhausted or failing unit (dead-letter, invariant breach). */
    ERROR
}
