package com.example.fanout.adapters.inmemory;

import com.example.fanout.domain.FanOutTrigger;
import com.example.fanout.ports.FanOutTriggerSink;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Recording {@link FanOutTriggerSink} stub: captures emissions so ingest tests
 * can assert trigger identity and counts; also the seam the fan-out phase
 * will replace with a real consumer.
 */
public final class RecordingFanOutTriggerSink implements FanOutTriggerSink {

    private final List<FanOutTrigger> emitted = new ArrayList<>();

    @Override
    public synchronized void emit(FanOutTrigger trigger) {
        emitted.add(Objects.requireNonNull(trigger, "trigger must not be null"));
    }

    /** Unmodifiable snapshot of emissions in arrival order. */
    public synchronized List<FanOutTrigger> emitted() {
        return List.copyOf(emitted);
    }

    public synchronized void clear() {
        emitted.clear();
    }
}
