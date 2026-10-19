package com.example.fanout.fanout;

/**
 * Thrown when a permit waiter observes the global halt switch and abandons the
 * admission attempt (spec FR-031, research.md D9,
 * contracts/capacity-shares.md).
 *
 * <p>Unchecked, <b>non-transient</b>, and distinct: {@link RetryExecutor} does
 * not catch it (only {@link TransientFanOutException} retries), it is never
 * dead-lettered, and it is never wrapped by {@link PermitGuardedDownstream}.
 * Halt is not a failure — callers treat it as "admission withdrawn": count
 * {@code backfill.halt.abandoned}, hold no permit, and leave the unit
 * unresolved behind its cursor so a later run resumes it.
 *
 * <p>Only ever thrown while holding no permit.
 */
public class AdmissionHaltedException extends RuntimeException {

    public AdmissionHaltedException(String message) {
        super(message);
    }

    public AdmissionHaltedException(String message, Throwable cause) {
        super(message, cause);
    }
}
