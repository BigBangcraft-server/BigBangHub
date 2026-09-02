package com.bigbangcraft.hub.common;

import com.bigbangcraft.hub.api.InstanceEvent;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class InstanceEventBus {
    private final CopyOnWriteArrayList<Consumer<InstanceEvent>> listeners = new CopyOnWriteArrayList<>();

    public void add(Consumer<InstanceEvent> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void remove(Consumer<InstanceEvent> listener) {
        listeners.remove(listener);
    }

    public void publish(InstanceEvent event) {
        for (Consumer<InstanceEvent> listener : listeners) {
            listener.accept(event);
        }
    }
}
