package com.bigbangcraft.hub.api;

import java.util.Optional;

public interface RoutingService {
    Optional<ServerDefinition> select(GameId gameId);
}
