package com.example.fanout.fakes;

import com.example.fanout.domain.FanOutTrigger;
import com.example.fanout.ports.FanOutTriggerSink;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fault-injection wrapper: once armed, {@code emit} throws — simulating a
 * crash after the checkpoint claim is durable but before the in-process
 * trigger reaches a consumer. Disarmed, delegates to the recording sink.
 */
public final class FailingTriggerSink implements FanOutTriggerSink {

    private final FanOutTriggerSink delegate;
    private final AtomicBoolean armed = new AtomicBoolean();

    public FailingTriggerSink(FanOutTriggerSink delegate) {
        this.delegate = delegate;
    }

    public void armFailures() {
        armed.set(true);
    }

    public void disarm() {
        armed.set(false);
    }

    @Override
    public void emit(FanOutTrigger trigger) {
        if (armed.get()) {
            throw new IllegalStateException("simulated crash after checkpoint, before trigger");
        }
        delegate.emit(trigger);
    }
}
