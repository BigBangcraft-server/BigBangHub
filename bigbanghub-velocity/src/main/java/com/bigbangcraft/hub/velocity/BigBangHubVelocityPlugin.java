package com.bigbangcraft.hub.velocity;

import com.bigbangcraft.hub.api.BigBangHubApi;
import com.bigbangcraft.hub.api.GameDefinition;
import com.bigbangcraft.hub.api.GameRegistry;
import com.bigbangcraft.hub.api.GameState;
import com.bigbangcraft.hub.api.PlayerTransferService;
import com.bigbangcraft.hub.api.QueueEvent;
import com.bigbangcraft.hub.api.QueueService;
import com.bigbangcraft.hub.api.RoutingService;
import com.bigbangcraft.hub.api.ServerDefinition;
import com.bigbangcraft.hub.api.ServerRegistry;
import com.bigbangcraft.hub.api.ServerId;
import com.bigbangcraft.hub.common.ConfigException;
import com.bigbangcraft.hub.common.ConfigLoader;
import com.bigbangcraft.hub.common.FillWaitingRoutingService;
import com.bigbangcraft.hub.common.HubConfigSnapshot;
import com.bigbangcraft.hub.common.InMemoryGameRegistry;
import com.bigbangcraft.hub.common.InMemoryQueueService;
import com.bigbangcraft.hub.common.InMemoryServerRegistry;
import com.bigbangcraft.hub.common.MessagePayloads;
import com.bigbangcraft.hub.common.MessageType;
import com.bigbangcraft.hub.common.ProtocolCodec;
import com.bigbangcraft.hub.common.ProtocolEnvelope;
import com.bigbangcraft.hub.common.ProtocolValidationException;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.slf4j.Logger;

import com.google.inject.Inject;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Plugin(id = "bigbanghub", name = "BigBangHub", version = "0.1.0", authors = {"BigBangCraft"})
public final class BigBangHubVelocityPlugin implements BigBangHubApi {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final AtomicReference<HubConfigSnapshot> config = new AtomicReference<>();
    private final AtomicReference<GameRegistry> games = new AtomicReference<>();
    private final AtomicReference<ServerRegistry> servers = new AtomicReference<>();
    private final AtomicReference<RoutingService> routing = new AtomicReference<>();
    private final Set<ServerInfo> ownedServers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> inFlightTransfers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastRequestNanos = new ConcurrentHashMap<>();
    private MinecraftChannelIdentifier channel;
    private ProtocolCodec codec;
    private InMemoryQueueService queues;
    private VelocityTransferService transfers;

    @Inject
    public BigBangHubVelocityPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        try {
            ensureDefaults();
            HubConfigSnapshot snapshot = ConfigLoader.load(dataDirectory);
            codec = createCodec(snapshot);
            channel = MinecraftChannelIdentifier.from(snapshot.proxy().channel());
            proxy.getChannelRegistrar().register(channel);
            install(snapshot);
            proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("bbhub").plugin(this).build(), new VelocityCommands(this, false));
            proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("queue").plugin(this).build(), new VelocityCommands(this, true));
            logger.info("BigBangHub Velocity enabled with {} games", games().games().size());
        } catch (ConfigException | IOException | IllegalArgumentException exception) {
            logger.error("BigBangHub failed to enable", exception);
            proxy.shutdown();
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (channel != null) proxy.getChannelRegistrar().unregister(channel);
        for (ServerInfo server : ownedServers) proxy.unregisterServer(server);
        if (queues != null) queues.clear();
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        lastRequestNanos.remove(playerId);
        inFlightTransfers.remove(playerId);
        if (queues != null) queues.removePlayer(playerId);
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (channel == null || !channel.equals(event.getIdentifier())) return;
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection connection)) return;
        HubConfigSnapshot snapshot = configSnapshot();
        Player player = connection.getPlayer();
        if (!rateAllowed(player.getUniqueId())) return;
        try {
            ProtocolEnvelope envelope = codec.decode(event.getData());
            if (envelope.messageType() == MessageType.SERVER_STATUS) {
                handleServerStatus(connection, envelope);
                return;
            }
            if (!connection.getServerInfo().getName().equals(snapshot.proxy().hubServerName())) {
                logger.warn("Rejected BigBangHub message from untrusted backend {}", connection.getServerInfo().getName());
                return;
            }
            handle(connection, player, envelope);
        } catch (ProtocolValidationException | IllegalArgumentException exception) {
            logger.warn("Rejected invalid BigBangHub message from {}: {}", connection.getServerInfo().getName(), exception.getMessage());
        }
    }

    void reload(CommandSource source) {
        try {
            HubConfigSnapshot next = ConfigLoader.load(dataDirectory);
            ProtocolCodec nextCodec = createCodec(next);
            if (!configSnapshot().proxy().channel().equals(next.proxy().channel())
                    || configSnapshot().proxy().protocolVersion() != next.proxy().protocolVersion()
                    || configSnapshot().proxy().maxPayloadBytes() != next.proxy().maxPayloadBytes()) {
                throw new ConfigException("proxy channel, protocol version and payload limit require a restart");
            }
            if (!codec.hasSameAuthentication(nextCodec)) {
                throw new ConfigException("protocol authentication changes require a restart");
            }
            ensureConfiguredServers(next, true);
            GameRegistry nextGames = new InMemoryGameRegistry(next.games());
            ServerRegistry nextServers = new InMemoryServerRegistry(next.servers());
            games.set(nextGames);
            servers.set(nextServers);
            routing.set(new FillWaitingRoutingService(nextGames, nextServers));
            transfers = new VelocityTransferService(proxy, nextServers);
            config.set(next);
            source.sendPlainMessage("BigBangHub configuration reloaded.");
        } catch (ConfigException | IllegalArgumentException exception) {
            source.sendPlainMessage("Reload rejected; previous configuration kept: " + exception.getMessage());
            logger.warn("Keeping previous configuration: {}", exception.getMessage());
        }
    }

    void join(Player player, com.bigbangcraft.hub.api.GameId game) {
        if (!inFlightTransfers.add(player.getUniqueId())) {
            player.sendPlainMessage("Uma transferência já está em andamento.");
            return;
        }
        queues.join(player.getUniqueId(), game).thenAccept(result -> {
            if (result.code() == com.bigbangcraft.hub.api.QueueResult.Code.JOINED
                    || result.code() == com.bigbangcraft.hub.api.QueueResult.Code.ALREADY_QUEUED) {
                routeQueued(player, game, result);
            } else {
                inFlightTransfers.remove(player.getUniqueId());
                player.sendPlainMessage(result.message());
            }
        });
    }

    private void handle(ServerConnection connection, Player player, ProtocolEnvelope envelope) {
        switch (envelope.messageType()) {
            case QUEUE_JOIN -> handleQueueJoin(connection, player, envelope);
            case QUEUE_LEAVE -> handleQueueLeave(connection, player, envelope);
            case QUEUE_STATUS -> handleQueueStatus(connection, player, envelope);
            case SERVER_CONNECT -> handleServerConnect(connection, player, envelope);
            case SERVER_STATUS -> handleServerStatus(connection, envelope);
            default -> logger.warn("Rejected unexpected request type {}", envelope.messageType());
        }
    }

    private void handleQueueJoin(ServerConnection connection, Player player, ProtocolEnvelope envelope) {
        try {
            MessagePayloads.QueueJoin request = MessagePayloads.queueJoin(envelope.payload());
            if (!player.getUniqueId().equals(request.playerId())) { rejectIdentity(connection, envelope, player); return; }
            if (!games().find(request.gameId()).map(game -> game.enabled() && game.queueEnabled()).orElse(false)) {
                sendQueueResponse(connection, envelope, com.bigbangcraft.hub.api.QueueResult.of(
                        com.bigbangcraft.hub.api.QueueResult.Code.UNAVAILABLE, request.gameId(), 0, 0,
                        "Este minigame está temporariamente indisponível."), player.getUniqueId());
                return;
            }
            if (!inFlightTransfers.add(player.getUniqueId())) {
                sendQueueResponse(connection, envelope, com.bigbangcraft.hub.api.QueueResult.of(
                        com.bigbangcraft.hub.api.QueueResult.Code.ERROR, request.gameId(), 0, 0,
                        "Uma transferência já está em andamento."), player.getUniqueId());
                return;
            }
            queues.join(player.getUniqueId(), request.gameId()).thenAccept(result -> routeQueued(connection, player, envelope, request.gameId(), result));
        } catch (ProtocolValidationException exception) { logger.warn("Invalid queue join payload: {}", exception.getMessage()); }
    }

    private void routeQueued(ServerConnection connection, Player player, ProtocolEnvelope envelope,
                             com.bigbangcraft.hub.api.GameId game, com.bigbangcraft.hub.api.QueueResult result) {
        if (result.code() != com.bigbangcraft.hub.api.QueueResult.Code.JOINED
                && result.code() != com.bigbangcraft.hub.api.QueueResult.Code.ALREADY_QUEUED) {
            inFlightTransfers.remove(player.getUniqueId());
            sendQueueResponse(connection, envelope, result, player.getUniqueId());
            return;
        }
        ServerDefinition target = routing().select(game).orElse(null);
        if (target == null) {
            inFlightTransfers.remove(player.getUniqueId());
            sendQueueResponse(connection, envelope, result, player.getUniqueId());
            return;
        }
        InMemoryServerRegistry registry = serversMutable();
        if (!registry.reserve(target.id())) {
            target = routing().select(game).orElse(null);
            if (target == null || !registry.reserve(target.id())) {
                inFlightTransfers.remove(player.getUniqueId());
                sendQueueResponse(connection, envelope, result, player.getUniqueId());
                return;
            }
        }
        ServerDefinition selected = target;
        com.bigbangcraft.hub.api.QueueResult assigned = queues.assign(player.getUniqueId(), game, selected.id());
        if (assigned.code() != com.bigbangcraft.hub.api.QueueResult.Code.ASSIGNED) {
            registry.release(selected.id());
            inFlightTransfers.remove(player.getUniqueId());
            sendQueueResponse(connection, envelope, assigned, player.getUniqueId());
            return;
        }
        sendQueueResponse(connection, envelope, assigned, player.getUniqueId());
        transfers.transfer(player.getUniqueId(), selected.id()).thenAccept(transfer -> {
            if (!transfer.success()) {
                registry.release(selected.id());
                queues.join(player.getUniqueId(), game);
                player.sendPlainMessage("Não foi possível localizar um servidor agora.");
            }
            inFlightTransfers.remove(player.getUniqueId());
        });
    }

    private void routeQueued(Player player, com.bigbangcraft.hub.api.GameId game, com.bigbangcraft.hub.api.QueueResult result) {
        ServerDefinition target = routing().select(game).orElse(null);
        if (target != null) {
            InMemoryServerRegistry registry = serversMutable();
            boolean reserved = registry.reserve(target.id());
            if (!reserved) {
                target = routing().select(game).orElse(null);
                reserved = target != null && registry.reserve(target.id());
            }
            if (target != null && reserved) {
                com.bigbangcraft.hub.api.QueueResult assigned = queues.assign(player.getUniqueId(), game, target.id());
                if (assigned.code() != com.bigbangcraft.hub.api.QueueResult.Code.ASSIGNED) {
                    registry.release(target.id());
                    inFlightTransfers.remove(player.getUniqueId());
                    player.sendPlainMessage(assigned.message());
                    return;
                }
                ServerDefinition selected = target;
                transfers.transfer(player.getUniqueId(), selected.id()).thenAccept(transfer -> {
                    if (!transfer.success()) {
                        registry.release(selected.id());
                        queues.join(player.getUniqueId(), game);
                    }
                    inFlightTransfers.remove(player.getUniqueId());
                });
            } else inFlightTransfers.remove(player.getUniqueId());
        } else {
            inFlightTransfers.remove(player.getUniqueId());
        }
        player.sendPlainMessage(result.message());
    }

    private void handleQueueLeave(ServerConnection connection, Player player, ProtocolEnvelope envelope) {
        try {
            MessagePayloads.PlayerRequest request = MessagePayloads.playerRequest(envelope.payload());
            if (!player.getUniqueId().equals(request.playerId())) { rejectIdentity(connection, envelope, player); return; }
            queues.leave(player.getUniqueId()).thenAccept(result -> sendQueueResponse(connection, envelope, result, player.getUniqueId()));
        } catch (ProtocolValidationException exception) { logger.warn("Invalid queue leave payload: {}", exception.getMessage()); }
    }

    private void handleQueueStatus(ServerConnection connection, Player player, ProtocolEnvelope envelope) {
        try {
            MessagePayloads.PlayerRequest request = MessagePayloads.playerRequest(envelope.payload());
            if (!player.getUniqueId().equals(request.playerId())) { rejectIdentity(connection, envelope, player); return; }
            queues.status(player.getUniqueId()).thenAccept(status -> sendQueueResponse(connection, envelope,
                    com.bigbangcraft.hub.api.QueueResult.of(com.bigbangcraft.hub.api.QueueResult.Code.JOINED,
                            status.game().orElse(null), status.position(), status.size(),
                            status.game().isPresent() ? "Fila consultada." : "Você não está em uma fila."), player.getUniqueId()));
        } catch (ProtocolValidationException exception) { logger.warn("Invalid queue status payload: {}", exception.getMessage()); }
    }

    private void handleServerConnect(ServerConnection connection, Player player, ProtocolEnvelope envelope) {
        try {
            MessagePayloads.ServerConnect request = MessagePayloads.serverConnect(envelope.payload());
            if (!player.getUniqueId().equals(request.playerId())) { rejectIdentity(connection, envelope, player); return; }
            if (servers().find(request.serverId()).isEmpty()) {
                sendServerResponse(connection, envelope, player.getUniqueId(), false, "Servidor não permitido.");
                return;
            }
            if (!inFlightTransfers.add(player.getUniqueId())) {
                sendServerResponse(connection, envelope, player.getUniqueId(), false, "Uma transferência já está em andamento.");
                return;
            }
            sendServerResponse(connection, envelope, player.getUniqueId(), true, "Conectando ao servidor...");
            transfers.transfer(player.getUniqueId(), request.serverId()).thenAccept(result -> {
                if (!result.success()) player.sendPlainMessage("Não foi possível localizar um servidor agora.");
                inFlightTransfers.remove(player.getUniqueId());
            });
        } catch (ProtocolValidationException exception) { logger.warn("Invalid server connect payload: {}", exception.getMessage()); }
    }

    private void handleServerStatus(ServerConnection connection, ProtocolEnvelope envelope) {
        try {
            MessagePayloads.ServerStatus status = MessagePayloads.serverStatus(envelope.payload());
            if (!connection.getServerInfo().getName().equals(status.serverId().value())) return;
            ServerDefinition current = servers().find(status.serverId()).orElse(null);
            if (current == null) return;
            serversMutable().update(new ServerDefinition(current.id(), current.gameId(), current.host(), current.port(),
                    GameState.valueOf(status.state().name()), status.playerCount(), status.maxPlayers()));
        } catch (ProtocolValidationException | IllegalArgumentException exception) {
            logger.warn("Rejected invalid server status: {}", exception.getMessage());
        }
    }

    private void sendQueueResponse(ServerConnection connection, ProtocolEnvelope request,
                                   com.bigbangcraft.hub.api.QueueResult result, UUID playerId) {
        send(connection, new ProtocolEnvelope(1, MessageType.QUEUE_RESPONSE, request.correlationId(),
                MessagePayloads.queueResponse(new MessagePayloads.QueueResponse(playerId, result.code(), result.game(),
                        result.position(), result.size(), result.message()))));
    }

    private void sendServerResponse(ServerConnection connection, ProtocolEnvelope request, UUID playerId,
                                    boolean success, String message) {
        send(connection, new ProtocolEnvelope(1, MessageType.SERVER_RESPONSE, request.correlationId(),
                MessagePayloads.serverResponse(new MessagePayloads.ServerResponse(playerId, success, message))));
    }

    private void send(ServerConnection connection, ProtocolEnvelope response) {
        connection.sendPluginMessage(channel, codec.encode(response));
    }

    private void rejectIdentity(ServerConnection connection, ProtocolEnvelope request, Player player) {
        sendServerResponse(connection, request, player.getUniqueId(), false, "Identidade de jogador inválida.");
    }

    private boolean rateAllowed(UUID playerId) {
        long now = System.nanoTime();
        Long previous = lastRequestNanos.put(playerId, now);
        return previous == null || now - previous >= 100_000_000L;
    }

    private void install(HubConfigSnapshot snapshot) throws ConfigException, IOException {
        GameRegistry nextGames = new InMemoryGameRegistry(snapshot.games());
        ServerRegistry nextServers = new InMemoryServerRegistry(snapshot.servers());
        ensureConfiguredServers(snapshot, true);
        queues = new InMemoryQueueService(new com.bigbangcraft.hub.common.QueueEventBus());
        transfers = new VelocityTransferService(proxy, nextServers);
        games.set(nextGames);
        servers.set(nextServers);
        routing.set(new FillWaitingRoutingService(nextGames, nextServers));
        config.set(snapshot);
    }

    private void ensureConfiguredServers(HubConfigSnapshot snapshot, boolean failOnMismatch) throws ConfigException {
        for (ServerDefinition server : snapshot.servers()) {
            ServerInfo info = new ServerInfo(server.id().value(), InetSocketAddress.createUnresolved(server.host(), server.port()));
            RegisteredServer existing = proxy.getServer(server.id().value()).orElse(null);
            if (existing == null) {
                try {
                    proxy.registerServer(info);
                    ownedServers.add(info);
                } catch (RuntimeException exception) {
                    if (failOnMismatch) throw new ConfigException("Unable to register server " + server.id(), exception);
                }
            } else if (!existing.getServerInfo().getAddress().equals(info.getAddress()) && failOnMismatch) {
                throw new ConfigException("Configured address differs from existing Velocity server " + server.id());
            }
        }
    }

    private void ensureDefaults() throws IOException {
        Files.createDirectories(dataDirectory);
        copyDefault("config.yml"); copyDefault("menus.yml"); copyDefault("games.yml");
        copyDefault("servers.yml"); copyDefault("messages.yml");
    }

    private void copyDefault(String file) throws IOException {
        Path target = dataDirectory.resolve(file);
        if (Files.exists(target)) return;
        try (var input = getClass().getResourceAsStream("/" + file)) {
            if (input == null) throw new IOException("Missing bundled resource " + file);
            Files.copy(input, target);
        }
    }

    private ProtocolCodec createCodec(HubConfigSnapshot snapshot) throws ConfigException {
        byte[] secret = ProtocolCodec.secretFromEnvironment(snapshot.proxy().sharedSecretEnvironment(), snapshot.proxy().requireHmac());
        return new ProtocolCodec(secret, snapshot.proxy().maxPayloadBytes(), snapshot.proxy().requireHmac());
    }

    InMemoryServerRegistry serversMutable() { return (InMemoryServerRegistry) servers.get(); }
    HubConfigSnapshot configSnapshot() { return Objects.requireNonNull(config.get(), "plugin is not enabled"); }
    ProxyServer proxy() { return proxy; }
    @Override public GameRegistry games() { return games.get(); }
    @Override public ServerRegistry servers() { return servers.get(); }
    @Override public QueueService queues() { return queues; }
    @Override public RoutingService routing() { return routing.get(); }
    @Override public PlayerTransferService transfers() { return transfers; }
    @Override public void addQueueListener(java.util.function.Consumer<QueueEvent> listener) { queues.addListener(listener); }
    @Override public void removeQueueListener(java.util.function.Consumer<QueueEvent> listener) { queues.removeListener(listener); }
}
