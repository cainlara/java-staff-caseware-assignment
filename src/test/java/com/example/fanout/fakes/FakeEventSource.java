package com.example.fanout.fakes;

import com.example.fanout.domain.TemplatePublished;
import com.example.fanout.domain.TemplateWithdrawn;
import com.example.fanout.ports.EventSource;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/**
 * Scriptable {@link EventSource} fake: enqueue publishes/withdraws (including
 * duplicates) and poll them in order; empty poll returns {@link Optional#empty()}.
 */
public final class FakeEventSource implements EventSource {

    private final Deque<TemplatePublished> publishes = new ArrayDeque<>();
    private final Deque<TemplateWithdrawn> withdraws = new ArrayDeque<>();

    public synchronized void enqueue(TemplatePublished event) {
        publishes.addLast(event);
    }

    public synchronized void enqueue(TemplateWithdrawn event) {
        withdraws.addLast(event);
    }

    @Override
    public synchronized Optional<TemplatePublished> pollPublish() {
        return Optional.ofNullable(publishes.pollFirst());
    }

    @Override
    public synchronized Optional<TemplateWithdrawn> pollWithdraw() {
        return Optional.ofNullable(withdraws.pollFirst());
    }
}
