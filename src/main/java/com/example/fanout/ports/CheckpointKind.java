package com.example.fanout.ports;

/** Domain of a checkpoint key: publish progress or backfill-batch progress. */
public enum CheckpointKind {
    PUBLISH,
    BACKFILL_BATCH
}
