package com.bigbangcraft.hub.common;

import com.bigbangcraft.hub.api.PartyEvent;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class PartyEventBus {
    private final List<Consumer<PartyEvent>> listeners = new CopyOnWriteArrayList<>();

    public void add(Consumer<PartyEvent> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void remove(Consumer<PartyEvent> listener) {
        listeners.remove(Objects.requireNonNull(listener, "listener"));
    }

    public void publish(PartyEvent event) {
        Objects.requireNonNull(event, "event");
        for (Consumer<PartyEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (Throwable ignored) {
                // Keep bus isolated from individual listener failures
            }
        }
    }
}
