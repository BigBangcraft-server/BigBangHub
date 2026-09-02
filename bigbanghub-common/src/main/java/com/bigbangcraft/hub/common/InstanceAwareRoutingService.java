package com.bigbangcraft.hub.common;

import com.bigbangcraft.hub.api.GameDefinition;
import com.bigbangcraft.hub.api.GameId;
import com.bigbangcraft.hub.api.GameRegistry;
import com.bigbangcraft.hub.api.GameState;
import com.bigbangcraft.hub.api.InstanceRegistry;
import com.bigbangcraft.hub.api.InstanceSnapshot;
import com.bigbangcraft.hub.api.RoutingService;
import com.bigbangcraft.hub.api.RoutingStrategy;
import com.bigbangcraft.hub.api.ServerDefinition;
import com.bigbangcraft.hub.api.ServerRegistry;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InstanceAwareRoutingService implements RoutingService {
    private final GameRegistry games;
    private final InstanceRegistry instances;
    private final ServerRegistry fallbackServers;
    private final Map<RoutingStrategy, InstanceRoutingPolicy> policies = new ConcurrentHashMap<>();

    public InstanceAwareRoutingService(GameRegistry games, InstanceRegistry instances, ServerRegistry fallbackServers) {
        this.games = Objects.requireNonNull(games, "games");
        this.instances = Objects.requireNonNull(instances, "instances");
        this.fallbackServers = Objects.requireNonNull(fallbackServers, "fallbackServers");
        policies.put(RoutingStrategy.FILL_WAITING, InstanceRoutingPolicy.FILL_WAITING);
        policies.put(RoutingStrategy.LEAST_PLAYERS, InstanceRoutingPolicy.LEAST_PLAYERS);
        policies.put(RoutingStrategy.ROUND_ROBIN, InstanceRoutingPolicy.roundRobin());
    }

    @Override
    public Optional<ServerDefinition> select(GameId gameId) {
        Optional<InstanceSnapshot> selectedInstance = selectInstance(gameId);
        if (selectedInstance.isPresent()) {
            InstanceSnapshot inst = selectedInstance.get();
            ServerDefinition server = fallbackServers.find(inst.instanceId()).orElse(null);
            String host = server != null ? server.host() : "127.0.0.1";
            int port = server != null ? server.port() : 25565;
            return Optional.of(new ServerDefinition(
                    inst.instanceId(), inst.gameId(), host, port, inst.state(),
                    inst.playerCount() + inst.activeReservations(), inst.maxPlayers()));
        }

        GameDefinition game = games.find(Objects.requireNonNull(gameId, "gameId")).orElse(null);
        if (game == null || !game.enabled() || !game.queueEnabled()) return Optional.empty();

        return fallbackServers.servers().stream()
                .filter(server -> server.gameId().equals(gameId))
                .filter(ServerDefinition::canAcceptPlayers)
                .filter(server -> server.state() == GameState.WAITING)
                .min(Comparator.comparingInt(ServerDefinition::playerCount).reversed()
                        .thenComparing(server -> server.id().value()));
    }

    @Override
    public Optional<InstanceSnapshot> selectInstance(GameId gameId) {
        return selectInstance(gameId, 1);
    }

    public Optional<InstanceSnapshot> selectInstance(GameId gameId, int requiredCapacity) {
        GameDefinition game = games.find(Objects.requireNonNull(gameId, "gameId")).orElse(null);
        if (game == null || !game.enabled() || !game.queueEnabled()) return Optional.empty();

        List<InstanceSnapshot> eligible = instances.instancesForGame(gameId).stream()
                .filter(InstanceSnapshot::canAcceptPlayers)
                .filter(inst -> inst.effectiveCapacity() >= requiredCapacity)
                .filter(inst -> inst.state() == GameState.WAITING)
                .toList();

        if (eligible.isEmpty()) return Optional.empty();

        RoutingStrategy strategy = game.routingStrategy();
        InstanceRoutingPolicy policy = policies.computeIfAbsent(strategy, InstanceRoutingPolicy::forStrategy);
        return policy.select(eligible);
    }
}
