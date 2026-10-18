package com.example.fanout.domain;

/** Lifecycle state of a pending-index metadata row. */
public enum PendingIndexState {
    PENDING,
    APPLIED,
    DECLINED,
    CREATED
}
