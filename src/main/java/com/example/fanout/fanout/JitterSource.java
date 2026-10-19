package com.example.fanout.fanout;

/**
 * Jitter seam (research.md D5): returns a value in [0, 1). Production uses
 * {@link java.util.Random}; tests inject a seeded/scripted source so every
 * backoff delay is reproducible and bounds assertions are exact (spec FR-072).
 */
public interface JitterSource {

    double next();

    /** Production jitter source. */
    static JitterSource random() {
        java.util.Random random = new java.util.Random();
        return random::nextDouble;
    }
}
