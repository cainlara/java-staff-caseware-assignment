package com.example.fanout.ports;

import com.example.fanout.domain.EngagementRef;
import java.util.stream.Stream;

/**
 * Streams engagement eligibility rows from Pending Update Index metadata for
 * one publish (hot path) or one offered version (withdraw supersede).
 *
 * <p><b>Content rule (spec FR-001, FR-070)</b>: rows are
 * {@link EngagementRef} handles — identifiers only. This port MUST NOT load
 * engagement content and MUST NOT call {@link DownstreamLoadClient}; index
 * metadata only.
 *
 * <p><b>Ordering (normative, spec R1 / FR-003)</b>: both streams yield rows
 * ordered by firm id ascending, then engagement id ascending within firm.
 * Ordering is the basis for positional chunking and crash-resume alignment;
 * same inputs MUST yield the same order across calls (determinism). A real
 * adapter MUST sort (or serve pre-sorted metadata).
 *
 * <p>Empty results are empty streams, not errors. Backing-store failures
 * surface as unchecked exceptions; the engine retries them per the retry
 * contract ({@code ELIGIBILITY_STREAM} unit kind).
 *
 * <p>Throws only on null/blank arguments (defensive path).
 */
public interface EngagementEligibilitySource {

    Stream<EngagementRef> streamEligible(String productId, String publishId);

    Stream<EngagementRef> streamOffering(String productId, String version);
}
