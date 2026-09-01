package com.bigbangcraft.hub.api;

import java.util.Objects;

public record GameDefinition(
        GameId id,
        String displayName,
        boolean enabled,
        boolean queueEnabled,
        int minPlayers,
        int maxPlayers,
        RoutingStrategy routingStrategy) {
    public GameDefinition {
        Objects.requireNonNull(id, "id");
        displayName = Objects.requireNonNull(displayName, "displayName").trim();
        if (displayName.isEmpty()) throw new IllegalArgumentException("displayName is empty");
        if (minPlayers < 1 || maxPlayers < minPlayers) {
            throw new IllegalArgumentException("Invalid player limits for " + id);
        }
        routingStrategy = Objects.requireNonNull(routingStrategy, "routingStrategy");
    }
}
