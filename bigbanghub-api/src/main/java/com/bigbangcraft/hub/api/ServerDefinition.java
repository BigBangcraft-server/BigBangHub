package com.bigbangcraft.hub.api;

import java.util.Objects;

public record ServerDefinition(
        ServerId id,
        GameId gameId,
        String host,
        int port,
        GameState state,
        int playerCount,
        int maxPlayers) {
    public ServerDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(gameId, "gameId");
        host = Objects.requireNonNull(host, "host").trim();
        if (host.isEmpty()) throw new IllegalArgumentException("host is empty");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid port: " + port);
        state = Objects.requireNonNull(state, "state");
        if (playerCount < 0 || maxPlayers < 1 || playerCount > maxPlayers) {
            throw new IllegalArgumentException("Invalid capacity for " + id);
        }
    }

    public boolean canAcceptPlayers() {
        return state == GameState.WAITING && playerCount < maxPlayers;
    }

    public int freeSlots() {
        return maxPlayers - playerCount;
    }
}
