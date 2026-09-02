package com.bigbangcraft.hub.api;

import java.util.Objects;

public record InstanceStateChangedEvent(
        ServerId instanceId,
        GameState oldState,
        GameState newState) implements InstanceEvent {
    public InstanceStateChangedEvent {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(oldState, "oldState");
        Objects.requireNonNull(newState, "newState");
    }
}
