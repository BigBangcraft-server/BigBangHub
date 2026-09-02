package com.bigbangcraft.hub.common;

import com.bigbangcraft.hub.api.GameDefinition;
import com.bigbangcraft.hub.api.ServerDefinition;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record HubConfigSnapshot(List<GameDefinition> games, List<ServerDefinition> servers, CompassMenu compass,
                                ProtectionSettings protection, InventorySettings inventory, ProxySettings proxy,
                                Map<String, String> aliases, Map<String, String> messages,
                                boolean allowConsoleCommands, Set<String> consoleCommandAllowlist) {
    public HubConfigSnapshot {
        games = List.copyOf(games);
        servers = List.copyOf(servers);
        aliases = Map.copyOf(aliases);
        messages = Map.copyOf(messages);
        consoleCommandAllowlist = Set.copyOf(consoleCommandAllowlist);
    }
}
