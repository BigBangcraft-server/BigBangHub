package com.bigbangcraft.hub.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record InstanceSnapshot(
        ServerId instanceId,
        GameId gameId,
        String serverName,
        UUID sessionId,
        GameState state,
        InstanceHealth health,
        int playerCount,
        int minPlayers,
        int maxPlayers,
        int activeReservations,
        boolean acceptingPlayers,
        Instant lastHeartbeat) {

    public InstanceSnapshot {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(gameId, "gameId");
        Objects.requireNonNull(serverName, "serverName");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(health, "health");
        Objects.requireNonNull(lastHeartbeat, "lastHeartbeat");
        if (playerCount < 0) throw new IllegalArgumentException("playerCount cannot be negative");
        if (minPlayers < 0) throw new IllegalArgumentException("minPlayers cannot be negative");
        if (maxPlayers < 1) throw new IllegalArgumentException("maxPlayers must be at least 1");
        if (activeReservations < 0) throw new IllegalArgumentException("activeReservations cannot be negative");
    }

    public int effectiveCapacity() {
        return Math.max(0, maxPlayers - (playerCount + activeReservations));
    }

    public boolean canAcceptPlayers() {
        return health == InstanceHealth.HEALTHY && acceptingPlayers && effectiveCapacity() > 0;
    }
}
