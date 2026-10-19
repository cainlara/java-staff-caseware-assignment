package com.example.fanout.domain;

/**
 * Kind of fan-out run — the durable half of a run's identity together with
 * {@code (eventId, productId)}.
 */
public enum FanOutRunKind {
    PUBLISH,
    WITHDRAW_SUPERSEDE
}
