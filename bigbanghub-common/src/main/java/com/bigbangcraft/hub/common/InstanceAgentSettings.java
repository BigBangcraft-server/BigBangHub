package com.bigbangcraft.hub.common;

import com.bigbangcraft.hub.api.GameId;
import com.bigbangcraft.hub.api.ServerId;

import java.time.Duration;
import java.util.Objects;

public record InstanceAgentSettings(
        ServerId instanceId,
        GameId gameId,
        String serverName,
        Duration heartbeatInterval,
        int minPlayers,
        int maxPlayers,
        boolean acceptingPlayers) {

    public InstanceAgentSettings {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(gameId, "gameId");
        Objects.requireNonNull(serverName, "serverName");
        Objects.requireNonNull(heartbeatInterval, "heartbeatInterval");
        if (heartbeatInterval.isNegative() || heartbeatInterval.isZero()) {
            throw new IllegalArgumentException("heartbeatInterval must be positive");
        }
        if (minPlayers < 0 || maxPlayers < 1 || minPlayers > maxPlayers) {
            throw new IllegalArgumentException("Invalid instance capacity");
        }
    }

    public static InstanceAgentSettings of(String instanceId, String gameId, String serverName,
                                          Duration interval, int min, int max) {
        return new InstanceAgentSettings(
                ServerId.of(instanceId), GameId.of(gameId), serverName, interval, min, max, true);
    }
}
