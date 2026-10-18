package com.example.fanout.ingest;

/** Outcome status of one ingest call (see {@link IngestOutcome}). */
public enum IngestStatus {
    INGESTED,
    DUPLICATE,
    DEFERRED,
    REJECTED
}
