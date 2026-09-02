package com.bigbangcraft.hub.api;

import java.util.Collection;
import java.util.Optional;

public interface InstanceRegistry {
    Collection<InstanceSnapshot> instances();

    Collection<InstanceSnapshot> instancesForGame(GameId gameId);

    Optional<InstanceSnapshot> find(ServerId instanceId);
}
