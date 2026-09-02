package com.bigbangcraft.hub.common;

import com.bigbangcraft.hub.api.GameDefinition;
import com.bigbangcraft.hub.api.GameId;
import com.bigbangcraft.hub.api.GameRegistry;
import com.bigbangcraft.hub.api.GameState;
import com.bigbangcraft.hub.api.RoutingService;
import com.bigbangcraft.hub.api.ServerDefinition;
import com.bigbangcraft.hub.api.ServerRegistry;

import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

/** Selects the fullest eligible WAITING instance, with a stable id tie-break. */
public final class FillWaitingRoutingService implements RoutingService {
    private static final Comparator<ServerDefinition> FILL_ORDER = Comparator
            .comparingInt(ServerDefinition::playerCount).reversed()
            .thenComparing(server -> server.id().value());

    private final GameRegistry games;
    private final ServerRegistry servers;

    public FillWaitingRoutingService(GameRegistry games, ServerRegistry servers) {
        this.games = Objects.requireNonNull(games, "games");
        this.servers = Objects.requireNonNull(servers, "servers");
    }

    @Override
    public Optional<ServerDefinition> select(GameId gameId) {
        GameDefinition game = games.find(Objects.requireNonNull(gameId, "gameId")).orElse(null);
        if (game == null || !game.enabled() || !game.queueEnabled()) return Optional.empty();
        return servers.servers().stream()
                .filter(server -> server.gameId().equals(gameId))
                .filter(ServerDefinition::canAcceptPlayers)
                .filter(server -> server.state() == GameState.WAITING)
                .min(FILL_ORDER);
    }
}
