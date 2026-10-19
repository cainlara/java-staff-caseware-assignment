package com.example.fanout.domain;

/** Lifecycle status of a fan-out run cursor held in the checkpoint store. */
public enum FanOutRunStatus {
    RUNNING,
    COMPLETE
}
