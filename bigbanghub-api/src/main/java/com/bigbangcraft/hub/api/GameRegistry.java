package com.bigbangcraft.hub.api;

import java.util.Collection;
import java.util.Optional;

public interface GameRegistry {
    Collection<GameDefinition> games();

    Optional<GameDefinition> find(GameId id);
}
