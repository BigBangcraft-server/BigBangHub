package com.bigbangcraft.hub.api;

import java.util.Objects;

public record InstanceHealthChangedEvent(
        ServerId instanceId,
        InstanceHealth oldHealth,
        InstanceHealth newHealth) implements InstanceEvent {
    public InstanceHealthChangedEvent {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(oldHealth, "oldHealth");
        Objects.requireNonNull(newHealth, "newHealth");
    }
}
