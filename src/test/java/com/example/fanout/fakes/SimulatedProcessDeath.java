package com.example.fanout.fakes;

/**
 * Simulated process death for crash tests (research.md D12): an unchecked,
 * NON-transient failure that {@code RetryExecutor} does not catch (it retries
 * only {@code TransientFanOutException}) and that no backfill worker code
 * path wraps — thrown from a seam, it propagates out of {@code run()} exactly
 * like a JVM exit, so the restart test observes a real crash boundary.
 */
public class SimulatedProcessDeath extends RuntimeException {

    public SimulatedProcessDeath(String message) {
        super(message);
    }
}
