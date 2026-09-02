package com.bigbangcraft.hub.api;

import java.util.Objects;

public record InstanceRegisteredEvent(InstanceSnapshot instance) implements InstanceEvent {
    public InstanceRegisteredEvent {
        Objects.requireNonNull(instance, "instance");
    }

    @Override
    public ServerId instanceId() {
        return instance.instanceId();
    }
}
