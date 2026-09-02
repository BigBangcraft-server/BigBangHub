package com.bigbangcraft.hub.common;

import com.bigbangcraft.hub.api.MatchEvent;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class MatchEventBus {
    private final List<Consumer<MatchEvent>> listeners = new CopyOnWriteArrayList<>();

    public void add(Consumer<MatchEvent> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void remove(Consumer<MatchEvent> listener) {
        listeners.remove(Objects.requireNonNull(listener, "listener"));
    }

    public void publish(MatchEvent event) {
        Objects.requireNonNull(event, "event");
        for (Consumer<MatchEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (Throwable ignored) {
                // Keep bus isolated from individual listener failures
            }
        }
    }
}
