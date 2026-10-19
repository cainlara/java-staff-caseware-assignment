package com.example.fanout.ports;

import com.example.fanout.domain.EnumerationBatch;
import java.util.Optional;

/**
 * Enumerate every engagement of one customer database in stable, resumable,
 * engagement-id-ordered batches — the backfill path's only source of work
 * (spec B1, contracts/engagement-enumerator.md).
 *
 * <p>Identifier-only: this port never returns engagement content or template
 * versions. Batches are strictly ascending by engagement id, stable for the
 * same {@code (customerDatabaseId, afterEngagementId, limit)} triple absent
 * concurrent engagement creation, and resumable from any cursor the port itself
 * produced.
 *
 * <p>Rejected with {@link IllegalArgumentException}: null/blank database id or
 * {@code limit < 1}. Downstream enumeration failures surface as unchecked
 * exceptions (the worker retries them per the retry policy).
 */
public interface EngagementEnumerator {

    /**
     * Next batch of engagements for one customer database, strictly above the
     * cursor, ordered by engagementId ascending.
     *
     * @param customerDatabaseId non-blank database identity (= firm id, 1:1)
     * @param afterEngagementId  exclusive lower bound; empty = start from the beginning
     * @param limit              max items, >= 1
     * @return a batch; never null; never an empty item list
     */
    EnumerationBatch nextBatch(String customerDatabaseId, Optional<String> afterEngagementId, int limit);
}
