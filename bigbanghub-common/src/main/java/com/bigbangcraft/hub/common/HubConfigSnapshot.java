package com.bigbangcraft.hub.common;

import com.bigbangcraft.hub.api.GameDefinition;
import com.bigbangcraft.hub.api.ServerDefinition;
import com.bigbangcraft.hub.api.ServerRole;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record HubConfigSnapshot(
        ServerRole role,
        Optional<InstanceAgentSettings> instance,
        RegistrySettings registry,
        MatchSettings match,
        SpectatorSettings spectator,
        List<GameDefinition> games,
        List<ServerDefinition> servers,
        CompassMenu compass,
        ProtectionSettings protection,
        InventorySettings inventory,
        ProxySettings proxy,
        Map<String, String> aliases,
        Map<String, String> messages,
        boolean allowConsoleCommands,
        Set<String> consoleCommandAllowlist) {

    public HubConfigSnapshot {
        role = Objects.requireNonNull(role, "role");
        instance = Objects.requireNonNull(instance, "instance");
        registry = Objects.requireNonNull(registry, "registry");
        match = Objects.requireNonNull(match, "match");
        spectator = Objects.requireNonNull(spectator, "spectator");
        games = List.copyOf(games);
        servers = List.copyOf(servers);
        aliases = Map.copyOf(aliases);
        messages = Map.copyOf(messages);
        consoleCommandAllowlist = Set.copyOf(consoleCommandAllowlist);
    }
}
