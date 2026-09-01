package com.bigbangcraft.hub.api;

import java.util.Collection;
import java.util.Optional;

public interface ServerRegistry {
    Collection<ServerDefinition> servers();

    Optional<ServerDefinition> find(ServerId id);
}
