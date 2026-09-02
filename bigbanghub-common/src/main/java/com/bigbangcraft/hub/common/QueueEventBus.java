package com.bigbangcraft.hub.common;

import com.bigbangcraft.hub.api.QueueEvent;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class QueueEventBus {
    private final CopyOnWriteArrayList<Consumer<QueueEvent>> listeners = new CopyOnWriteArrayList<>();

    public void add(Consumer<QueueEvent> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void remove(Consumer<QueueEvent> listener) {
        listeners.remove(listener);
    }

    public void publish(QueueEvent event) {
        for (Consumer<QueueEvent> listener : listeners) {
            listener.accept(event);
        }
    }
}
