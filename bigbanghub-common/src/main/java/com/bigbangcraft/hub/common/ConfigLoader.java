package com.bigbangcraft.hub.common;

import com.bigbangcraft.hub.api.GameDefinition;
import com.bigbangcraft.hub.api.GameId;
import com.bigbangcraft.hub.api.GameState;
import com.bigbangcraft.hub.api.RoutingStrategy;
import com.bigbangcraft.hub.api.ServerDefinition;
import com.bigbangcraft.hub.api.ServerId;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Loads and validates a complete immutable runtime snapshot before it is swapped in. */
public final class ConfigLoader {
    private ConfigLoader() { }

    public static HubConfigSnapshot load(Path directory) throws ConfigException {
        try {
            Map<String, Object> config = yaml(directory.resolve("config.yml"));
            Map<String, Object> menus = yaml(directory.resolve("menus.yml"));
            Map<String, Object> games = yaml(directory.resolve("games.yml"));
            Map<String, Object> servers = yaml(directory.resolve("servers.yml"));
            Map<String, Object> messages = yaml(directory.resolve("messages.yml"));
            List<GameDefinition> gameDefinitions = readGames(games);
            List<ServerDefinition> serverDefinitions = readServers(servers, gameDefinitions);
            CompassMenu compass = readCompass(menus, gameDefinitions, serverDefinitions);
            return new HubConfigSnapshot(
                    gameDefinitions,
                    serverDefinitions,
                    compass,
                    readProtection(config),
                    readInventory(config),
                    readProxy(config),
                    readAliases(config, gameDefinitions),
                    readMessages(messages),
                    bool(config, "allow-console-commands", false),
                    Set.copyOf(strings(config, "console-command-allowlist")));
        } catch (IOException exception) {
            throw new ConfigException("Unable to read BigBangHub configuration: " + exception.getMessage(), exception);
        }
    }

    private static List<GameDefinition> readGames(Map<String, Object> root) throws ConfigException {
        Map<String, Object> values = map(root.get("games"), "games");
        List<GameDefinition> result = new ArrayList<>(values.size());
        Set<GameId> ids = new HashSet<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String path = "games." + entry.getKey();
            GameId id = id(entry.getKey(), path);
            if (!ids.add(id)) throw new ConfigException("Duplicate game id at " + path);
            Map<String, Object> game = map(entry.getValue(), path);
            Map<String, Object> queue = optionalMap(game.get("queue"));
            RoutingStrategy strategy = enumValue(queue.getOrDefault("strategy", "FILL_WAITING"),
                    RoutingStrategy.class, path + ".queue.strategy");
            result.add(new GameDefinition(id,
                    string(game, "display-name", path),
                    bool(game, "enabled", true),
                    bool(queue, "enabled", true),
                    integer(queue, "min-players", 2, path + ".queue.min-players"),
                    integer(queue, "max-players", 10, path + ".queue.max-players"),
                    strategy));
        }
        if (result.isEmpty()) throw new ConfigException("games must define at least one game");
        return List.copyOf(result);
    }

    private static List<ServerDefinition> readServers(Map<String, Object> root,
                                                       List<GameDefinition> games) throws ConfigException {
        Map<String, Object> values = map(root.get("servers"), "servers");
        Set<GameId> gameIds = games.stream().map(GameDefinition::id).collect(java.util.stream.Collectors.toSet());
        List<ServerDefinition> result = new ArrayList<>(values.size());
        Set<ServerId> ids = new HashSet<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String path = "servers." + entry.getKey();
            ServerId id = serverId(entry.getKey(), path);
            if (!ids.add(id)) throw new ConfigException("Duplicate server id at " + path);
            Map<String, Object> server = map(entry.getValue(), path);
            GameId gameId = id(string(server, "game", path), path + ".game");
            if (!gameIds.contains(gameId)) throw new ConfigException("Unknown game " + gameId + " at " + path + ".game");
            result.add(new ServerDefinition(id, gameId,
                    string(server, "host", path),
                    integer(server, "port", 25565, path + ".port"),
                    enumValue(server.getOrDefault("state", "WAITING"), GameState.class, path + ".state"),
                    integer(server, "player-count", 0, path + ".player-count"),
                    integer(server, "max-players", 10, path + ".max-players")));
        }
        if (result.isEmpty()) throw new ConfigException("servers must define at least one server");
        return List.copyOf(result);
    }

    private static CompassMenu readCompass(Map<String, Object> root, List<GameDefinition> games,
                                           List<ServerDefinition> servers) throws ConfigException {
        Map<String, Object> compass = map(root.get("compass"), "compass");
        int rows = integer(compass, "rows", 3, "menus.compass.rows");
        if (rows < 1 || rows > 6) throw new ConfigException("menus.compass.rows must be between 1 and 6");
        Map<String, Object> items = map(compass.get("items"), "menus.compass.items");
        Set<Integer> slots = new HashSet<>();
        Set<GameId> gameIds = games.stream().map(GameDefinition::id).collect(java.util.stream.Collectors.toSet());
        Set<ServerId> serverIds = servers.stream().map(ServerDefinition::id).collect(java.util.stream.Collectors.toSet());
        List<CompassEntry> entries = new ArrayList<>(items.size());
        for (Map.Entry<String, Object> entry : items.entrySet()) {
            String path = "menus.compass.items." + entry.getKey();
            Map<String, Object> item = map(entry.getValue(), path);
            int slot = integer(item, "slot", -1, path + ".slot");
            if (slot < 0 || slot >= rows * 9) throw new ConfigException("Invalid slot at " + path + ".slot");
            if (!slots.add(slot)) throw new ConfigException("Duplicate compass slot at " + path + ".slot");
            Map<String, Object> action = map(item.get("action"), path + ".action");
            ActionType actionType = actionType(action.get("type"), path + ".action.type");
            String actionValue = actionType == ActionType.CLOSE ? stringOr(action, "value", "") : string(action, "value", path + ".action");
            if (actionType == ActionType.QUEUE && !gameIds.contains(id(actionValue, path + ".action.value"))) {
                throw new ConfigException("Unknown queue game at " + path + ".action.value");
            }
            if (actionType == ActionType.SERVER && !serverIds.contains(serverId(actionValue, path + ".action.value"))) {
                throw new ConfigException("Unknown server at " + path + ".action.value");
            }
            entries.add(new CompassEntry(slot,
                    string(item, "material", path),
                    string(item, "name", path),
                    strings(item, "lore"),
                    bool(item, "glow", false),
                    strings(item, "flags"),
                    new ActionDefinition(actionType, actionValue)));
        }
        Map<String, Object> item = compass.containsKey("item") ? optionalMap(compass.get("item")) : compass;
        LobbyItemSettings lobbyItem;
        try {
            lobbyItem = new LobbyItemSettings(bool(item, "enabled", true),
                    integer(item, "slot", 4, "menus.compass.item.slot"),
                    stringOr(item, "material", "COMPASS"),
                    stringOr(item, "name", "<aqua><bold>Selecionar Minigame</bold></aqua>"),
                    strings(item, "lore"), bool(item, "glow", false), strings(item, "flags"));
        } catch (IllegalArgumentException exception) {
            throw new ConfigException("Invalid compass item: " + exception.getMessage(), exception);
        }
        return new CompassMenu(string(compass, "title", "menus.compass"), rows, entries, lobbyItem);
    }

    private static ProtectionSettings readProtection(Map<String, Object> root) throws ConfigException {
        Map<String, Object> values = optionalMap(root.get("protection"));
        return new ProtectionSettings(
                bool(values, "block-break", true), bool(values, "block-place", true),
                bool(values, "item-drop", true), bool(values, "item-pickup", true),
                bool(values, "damage", true), bool(values, "pvp", true),
                bool(values, "hunger", true), bool(values, "mob-interactions", true),
                bool(values, "crafting", true), bool(values, "inventory-manipulation", true),
                bool(values, "weather", true), bool(values, "farmland-trampling", true),
                bool(values, "armor-stand-interaction", true), bool(values, "entity-interaction", true),
                bool(values, "bucket-use", true), bool(values, "fire", true),
                bool(values, "explosions", true), bool(values, "fluid-placement", true),
                bool(values, "void-safety", true));
    }

    private static InventorySettings readInventory(Map<String, Object> root) throws ConfigException {
        Map<String, Object> values = optionalMap(root.get("inventory"));
        return new InventorySettings(bool(values, "clear-on-join", true), bool(values, "lock-lobby-items", true),
                bool(values, "prevent-drop", true), bool(values, "prevent-move", true));
    }

    private static ProxySettings readProxy(Map<String, Object> root) throws ConfigException {
        Map<String, Object> values = optionalMap(root.get("proxy"));
        try {
            return new ProxySettings(stringOr(values, "channel", "bigbanghub:main"),
                    integer(values, "protocol-version", ProtocolCodec.PROTOCOL_VERSION, "proxy.protocol-version"),
                    stringOr(values, "hub-server-name", "hubminigame"),
                    stringOr(values, "shared-secret-environment", "BIGBANGHUB_MESSAGE_SECRET"),
                    bool(values, "require-hmac", false),
                    integer(values, "max-payload-bytes", ProtocolCodec.MAX_PAYLOAD_BYTES, "proxy.max-payload-bytes"));
        } catch (IllegalArgumentException exception) {
            throw new ConfigException("Invalid proxy configuration: " + exception.getMessage(), exception);
        }
    }

    private static Map<String, String> readAliases(Map<String, Object> root, List<GameDefinition> games) throws ConfigException {
        Map<String, Object> values = optionalMap(root.get("aliases"));
        Set<GameId> gameIds = games.stream().map(GameDefinition::id).collect(java.util.stream.Collectors.toSet());
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String alias = normalizeAlias(entry.getKey());
            GameId game = id(stringValue(entry.getValue(), "aliases." + entry.getKey()), "aliases." + entry.getKey());
            if (!gameIds.contains(game)) throw new ConfigException("Unknown game at aliases." + entry.getKey());
            if (result.put(alias, game.value()) != null) throw new ConfigException("Duplicate alias: " + alias);
        }
        return Map.copyOf(result);
    }

    private static Map<String, String> readMessages(Map<String, Object> root) throws ConfigException {
        Map<String, Object> values = optionalMap(root.get("messages"));
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            result.put(entry.getKey(), stringValue(entry.getValue(), "messages." + entry.getKey()));
        }
        return Map.copyOf(result);
    }

    private static Map<String, Object> yaml(Path path) throws IOException, ConfigException {
        if (!Files.isRegularFile(path)) throw new ConfigException("Missing configuration file: " + path.getFileName());
        LoaderOptions options = new LoaderOptions();
        options.setMaxAliasesForCollections(0);
        options.setAllowDuplicateKeys(false);
        options.setCodePointLimit(256 * 1024);
        try (InputStream input = Files.newInputStream(path)) {
            Object loaded = new Yaml(new SafeConstructor(options)).load(input);
            return map(loaded, path.getFileName().toString());
        } catch (RuntimeException exception) {
            throw new ConfigException("Invalid YAML in " + path.getFileName() + ": " + exception.getMessage(), exception);
        }
    }

    private static ActionType actionType(Object value, String path) throws ConfigException {
        String raw = stringValue(value, path).toUpperCase(Locale.ROOT);
        try {
            return ActionType.valueOf(raw);
        } catch (IllegalArgumentException exception) {
            throw new ConfigException("Unknown action type " + raw + " at " + path, exception);
        }
    }

    private static <T extends Enum<T>> T enumValue(Object value, Class<T> type, String path) throws ConfigException {
        String raw = stringValue(value, path).toUpperCase(Locale.ROOT);
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException exception) {
            throw new ConfigException("Unknown value " + raw + " at " + path, exception);
        }
    }

    private static GameId id(String value, String path) throws ConfigException {
        try { return GameId.of(value); }
        catch (IllegalArgumentException exception) { throw new ConfigException("Invalid game id at " + path, exception); }
    }

    private static ServerId serverId(String value, String path) throws ConfigException {
        try { return ServerId.of(value); }
        catch (IllegalArgumentException exception) { throw new ConfigException("Invalid server id at " + path, exception); }
    }

    private static String normalizeAlias(String value) throws ConfigException {
        String alias = value.trim().toLowerCase(Locale.ROOT);
        if (!alias.matches("[a-z0-9](?:[a-z0-9_-]{0,30}[a-z0-9])?")) {
            throw new ConfigException("Invalid command alias: " + value);
        }
        return alias;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value, String path) throws ConfigException {
        if (!(value instanceof Map<?, ?> raw)) throw new ConfigException("Expected a mapping at " + path);
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw new ConfigException("Non-string key at " + path);
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static Map<String, Object> optionalMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return Map.of();
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) if (entry.getKey() instanceof String key) result.put(key, entry.getValue());
        return result;
    }

    private static String string(Map<String, Object> map, String key, String path) throws ConfigException {
        return stringValue(map.get(key), path + "." + key);
    }

    private static String stringOr(Map<String, Object> map, String key, String fallback) throws ConfigException {
        return map.containsKey(key) ? stringValue(map.get(key), key) : fallback;
    }

    private static String stringValue(Object value, String path) throws ConfigException {
        if (!(value instanceof String string) || string.isBlank()) throw new ConfigException("Expected non-empty string at " + path);
        return string.trim();
    }

    private static int integer(Map<String, Object> map, String key, int fallback, String path) throws ConfigException {
        Object value = map.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Number number)) throw new ConfigException("Expected integer at " + path);
        return number.intValue();
    }

    private static boolean bool(Map<String, Object> map, String key, boolean fallback) throws ConfigException {
        Object value = map.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Boolean booleanValue)) throw new ConfigException("Expected boolean at " + key);
        return booleanValue;
    }

    private static List<String> strings(Map<String, Object> map, String key) throws ConfigException {
        Object value = map.get(key);
        if (value == null) return List.of();
        if (!(value instanceof List<?> list)) throw new ConfigException("Expected list at " + key);
        List<String> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof String string)) throw new ConfigException("Expected string at " + key);
            result.add(string);
        }
        return List.copyOf(result);
    }
}
