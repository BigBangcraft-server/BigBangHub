package com.bigbangcraft.hub.api;

import java.util.UUID;

public interface InstanceService {
    ServerId instanceId();

    GameId gameId();

    UUID sessionId();

    GameState state();

    InstanceHealth health();

    int playerCount();

    int maxPlayers();

    int minPlayers();

    boolean isAcceptingPlayers();

    void setState(GameState state);

    void setAcceptingPlayers(boolean accepting);

    void setPlayerCount(int playerCount);

    void setMaxPlayers(int maxPlayers);

    void updateState(GameState state, boolean acceptingPlayers);

    void updateCapacity(int playerCount, int maxPlayers);
}
