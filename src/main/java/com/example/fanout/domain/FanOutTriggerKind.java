package com.example.fanout.domain;

/** Kind of internal fan-out trigger emitted after a successful ingest claim. */
public enum FanOutTriggerKind {
    PUBLISH,
    WITHDRAW
}
