package com.example.fanout.fakes;

import com.example.fanout.domain.PendingIndexRow;
import com.example.fanout.domain.PendingIndexState;
import com.example.fanout.ports.IndexWriter;
import com.example.fanout.ports.WriteResult;
import java.util.Optional;

/**
 * Write seam for crash tests: the event fires AFTER the delegate upsert
 * committed, so a kill there is a death between a successful row write and
 * whatever comes next (the cursor advance of the batch's last engagement, or
 * the next engagement mid-batch) — the exact redundant-load window spec B4
 * promises idempotent writes absorb.
 */
public final class CrashingIndexWriter implements IndexWriter {

    private final IndexWriter delegate;
    private final KillController controller;

    public CrashingIndexWriter(IndexWriter delegate, KillController controller) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        if (controller == null) {
            throw new IllegalArgumentException("controller must not be null");
        }
        this.delegate = delegate;
        this.controller = controller;
    }

    @Override
    public WriteResult upsert(PendingIndexRow row) {
        WriteResult result = delegate.upsert(row);
        controller.event("write.row");
        return result;
    }

    @Override
    public Optional<PendingIndexRow> find(String engagementId, String offeredVersion,
                                          PendingIndexState state) {
        return delegate.find(engagementId, offeredVersion, state);
    }
}
