package com.bigbangcraft.hub.common;

import com.bigbangcraft.hub.api.ServerDefinition;
import com.bigbangcraft.hub.api.ServerId;
import com.bigbangcraft.hub.api.GameState;
import com.bigbangcraft.hub.api.ServerRegistry;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class InMemoryServerRegistry implements ServerRegistry {
    private final AtomicReference<Map<ServerId, ServerDefinition>> servers;

    public InMemoryServerRegistry(Collection<ServerDefinition> definitions) {
        this.servers = new AtomicReference<>(toMap(definitions));
    }

    public void replace(Collection<ServerDefinition> definitions) {
        servers.set(toMap(definitions));
    }

    public boolean update(ServerDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        while (true) {
            Map<ServerId, ServerDefinition> current = servers.get();
            if (!current.containsKey(definition.id())) return false;
            Map<ServerId, ServerDefinition> next = new java.util.HashMap<>(current);
            next.put(definition.id(), definition);
            if (servers.compareAndSet(current, Map.copyOf(next))) return true;
        }
    }

    public boolean reserve(ServerId id) {
        while (true) {
            Map<ServerId, ServerDefinition> current = servers.get();
            ServerDefinition server = current.get(id);
            if (server == null || !server.canAcceptPlayers()) return false;
            ServerDefinition nextServer = new ServerDefinition(server.id(), server.gameId(), server.host(), server.port(),
                    server.state(), server.playerCount() + 1, server.maxPlayers());
            Map<ServerId, ServerDefinition> next = new java.util.HashMap<>(current);
            next.put(id, nextServer);
            if (servers.compareAndSet(current, Map.copyOf(next))) return true;
        }
    }

    public void release(ServerId id) {
        while (true) {
            Map<ServerId, ServerDefinition> current = servers.get();
            ServerDefinition server = current.get(id);
            if (server == null || server.playerCount() == 0) return;
            ServerDefinition nextServer = new ServerDefinition(server.id(), server.gameId(), server.host(), server.port(),
                    server.state(), server.playerCount() - 1, server.maxPlayers());
            Map<ServerId, ServerDefinition> next = new java.util.HashMap<>(current);
            next.put(id, nextServer);
            if (servers.compareAndSet(current, Map.copyOf(next))) return;
        }
    }

    @Override
    public Collection<ServerDefinition> servers() {
        return List.copyOf(servers.get().values());
    }

    @Override
    public Optional<ServerDefinition> find(ServerId id) {
        return Optional.ofNullable(servers.get().get(Objects.requireNonNull(id, "id")));
    }

    private static Map<ServerId, ServerDefinition> toMap(Collection<ServerDefinition> definitions) {
        Objects.requireNonNull(definitions, "definitions");
        return definitions.stream().collect(Collectors.toUnmodifiableMap(
                ServerDefinition::id, Function.identity(), (left, right) -> {
                    throw new IllegalArgumentException("Duplicate server: " + left.id());
                }));
    }
}
