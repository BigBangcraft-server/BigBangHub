package com.bigbangcraft.hub.api;

import java.util.Collection;
import java.util.Collections;
import java.util.Optional;

public interface InstanceRegistry {
    Collection<InstanceSnapshot> instances();

    Collection<InstanceSnapshot> instancesForGame(GameId gameId);

    Optional<InstanceSnapshot> find(ServerId instanceId);

    static InstanceRegistry empty() {
        return EmptyInstanceRegistry.INSTANCE;
    }

    enum EmptyInstanceRegistry implements InstanceRegistry {
        INSTANCE;

        @Override
        public Collection<InstanceSnapshot> instances() {
            return Collections.emptyList();
        }

        @Override
        public Collection<InstanceSnapshot> instancesForGame(GameId gameId) {
            return Collections.emptyList();
        }

        @Override
        public Optional<InstanceSnapshot> find(ServerId instanceId) {
            return Optional.empty();
        }
    }
}
