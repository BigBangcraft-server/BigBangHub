package com.bigbangcraft.hub.velocity;

import com.bigbangcraft.hub.api.BigBangHubApi;
import com.bigbangcraft.hub.api.GameId;
import com.bigbangcraft.hub.api.GameRegistry;
import com.bigbangcraft.hub.api.GameState;
import com.bigbangcraft.hub.api.InstanceEvent;
import com.bigbangcraft.hub.api.InstanceHealth;
import com.bigbangcraft.hub.api.InstanceHealthChangedEvent;
import com.bigbangcraft.hub.api.InstanceRegisteredEvent;
import com.bigbangcraft.hub.api.InstanceRegistry;
import com.bigbangcraft.hub.api.InstanceService;
import com.bigbangcraft.hub.api.InstanceSnapshot;
import com.bigbangcraft.hub.api.InstanceStateChangedEvent;
import com.bigbangcraft.hub.api.PlayerTransferService;
import com.bigbangcraft.hub.api.QueueEvent;
import com.bigbangcraft.hub.api.QueueResult;
import com.bigbangcraft.hub.api.QueueService;
import com.bigbangcraft.hub.api.Reservation;
import com.bigbangcraft.hub.api.RoutingService;
import com.bigbangcraft.hub.api.ServerDefinition;
import com.bigbangcraft.hub.api.ServerId;
import com.bigbangcraft.hub.api.ServerRegistry;
import com.bigbangcraft.hub.api.ServerRole;
import com.bigbangcraft.hub.common.ConfigException;
import com.bigbangcraft.hub.common.ConfigLoader;
import com.bigbangcraft.hub.common.HubConfigSnapshot;
import com.bigbangcraft.hub.common.InMemoryGameRegistry;
import com.bigbangcraft.hub.common.InMemoryInstanceRegistry;
import com.bigbangcraft.hub.common.InMemoryQueueService;
import com.bigbangcraft.hub.common.InMemoryReservationService;
import com.bigbangcraft.hub.common.InMemoryServerRegistry;
import com.bigbangcraft.hub.common.InstanceAwareRoutingService;
import com.bigbangcraft.hub.common.InstanceEventBus;
import com.bigbangcraft.hub.common.MessagePayloads;
import com.bigbangcraft.hub.common.MessageType;
import com.bigbangcraft.hub.common.ProtocolCodec;
import com.bigbangcraft.hub.common.ProtocolEnvelope;
import com.bigbangcraft.hub.common.ProtocolValidationException;
import com.bigbangcraft.hub.common.QueueEventBus;
import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
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
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Plugin(id = "bigbanghub", name = "BigBangHub", version = "0.2.0", authors = {"BigBangCraft"})
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
    private final Map<String, Long> backendLastRequestNanos = new ConcurrentHashMap<>();

    // Internal metrics counters
    private final AtomicLong registrationsCount = new AtomicLong();
    private final AtomicLong heartbeatsReceivedCount = new AtomicLong();
    private final AtomicLong heartbeatsRejectedCount = new AtomicLong();
    private final AtomicLong routingAttemptsCount = new AtomicLong();
    private final AtomicLong routingFailuresCount = new AtomicLong();
    private final AtomicLong transfersInitiatedCount = new AtomicLong();
    private final AtomicLong transfersSucceededCount = new AtomicLong();
    private final AtomicLong transfersFailedCount = new AtomicLong();
    private final AtomicLong reservationExpirationsCount = new AtomicLong();

    private MinecraftChannelIdentifier channel;
    private ProtocolCodec codec;
    private InMemoryQueueService queues;
    private InMemoryInstanceRegistry instanceRegistry;
    private InMemoryReservationService reservationService;
    private InstanceEventBus instanceEventBus;
    private InstanceAwareRoutingService instanceRouting;
    private VelocityTransferService transfers;
    private ScheduledTask livenessTask;

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
            proxy.getCommandManager().register(
                    proxy.getCommandManager().metaBuilder("bbhub").plugin(this).build(),
                    new VelocityCommands(this, false));
            proxy.getCommandManager().register(
                    proxy.getCommandManager().metaBuilder("queue").plugin(this).build(),
                    new VelocityCommands(this, true));
            logger.info("BigBangHub Velocity 0.2.0 enabled with {} games", games().games().size());
        } catch (ConfigException | IOException | IllegalArgumentException exception) {
            logger.error("BigBangHub failed to enable", exception);
            proxy.shutdown();
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (livenessTask != null) livenessTask.cancel();
        if (channel != null) proxy.getChannelRegistrar().unregister(channel);
        for (ServerInfo server : ownedServers) proxy.unregisterServer(server);
        if (reservationService != null) reservationService.clear();
        if (instanceRegistry != null) instanceRegistry.clear();
        if (queues != null) queues.clear();
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        lastRequestNanos.remove(playerId);
        inFlightTransfers.remove(playerId);
        if (queues != null) queues.removePlayer(playerId);
        if (reservationService != null) reservationService.cancel(playerId, "player disconnected");
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        ServerConnection current = player.getCurrentServer().orElse(null);
        if (current == null) return;
        ServerId serverId = ServerId.of(current.getServerInfo().getName());
        if (reservationService != null) {
            boolean confirmed = reservationService.confirm(player.getUniqueId(), serverId);
            if (confirmed) {
                transfersSucceededCount.incrementAndGet();
                logger.info("Confirmed reservation and arrival of player {} on {}", player.getUsername(), serverId);
            }
        }
        inFlightTransfers.remove(player.getUniqueId());
    }

    @Subscribe
    public void onKickedFromServer(KickedFromServerEvent event) {
        Player player = event.getPlayer();
        HubConfigSnapshot snapshot = configSnapshot();
        if (snapshot == null || !snapshot.registry().fallbackToHub()) return;

        String kickedFrom = event.getServer().getServerInfo().getName();
        String hubName = snapshot.proxy().hubServerName();
        if (kickedFrom.equals(hubName)) return;

        if (reservationService != null) {
            reservationService.cancel(player.getUniqueId(), "kicked from server");
        }
        inFlightTransfers.remove(player.getUniqueId());

        RegisteredServer hubServer = proxy.getServer(hubName).orElse(null);
        if (hubServer != null) {
            event.setResult(KickedFromServerEvent.RedirectPlayer.create(
                    hubServer,
                    Component.text("§cO servidor de minigame ficou indisponível. Você retornou ao Hub.")));
        }
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (channel == null || !channel.equals(event.getIdentifier())) return;
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection connection)) return;
        String backendName = connection.getServerInfo().getName();
        if (!backendRateAllowed(backendName)) return;

        HubConfigSnapshot snapshot = configSnapshot();
        try {
            ProtocolEnvelope envelope = codec.decode(event.getData());
            switch (envelope.messageType()) {
                case INSTANCE_REGISTER -> handleInstanceRegister(connection, envelope);
                case INSTANCE_HEARTBEAT -> handleInstanceHeartbeat(connection, envelope);
                case INSTANCE_STATE_CHANGE -> handleInstanceStateChange(connection, envelope);
                case INSTANCE_UNREGISTER -> handleInstanceUnregister(connection, envelope);
                case SERVER_STATUS -> handleServerStatus(connection, envelope);
                default -> {
                    if (backendName.equals(snapshot.proxy().hubServerName())) {
                        Player player = connection.getPlayer();
                        if (player != null && rateAllowed(player.getUniqueId())) {
                            handle(connection, player, envelope);
                        }
                    } else {
                        logger.warn("Rejected message {} from non-hub backend {}", envelope.messageType(), backendName);
                    }
                }
            }
        } catch (ProtocolValidationException | IllegalArgumentException exception) {
            logger.warn("Rejected invalid BigBangHub message from {}: {}", backendName, exception.getMessage());
        }
    }

    private void handleInstanceRegister(ServerConnection connection, ProtocolEnvelope envelope) {
        try {
            MessagePayloads.InstanceRegister reg = MessagePayloads.instanceRegister(envelope.payload());
            if (!validateBackendIdentity(connection, reg.instanceId(), reg.gameId())) {
                logger.warn("Rejected unauthorized instance register from backend {} as {}",
                        connection.getServerInfo().getName(), reg.instanceId());
                send(connection, new ProtocolEnvelope(1, MessageType.INSTANCE_REGISTER_ACK, envelope.correlationId(),
                        MessagePayloads.instanceRegisterAck(new MessagePayloads.InstanceRegisterAck(
                                reg.instanceId(), reg.sessionId(), false, "Unauthorized backend identity"))));
                return;
            }

            if (proxy.getServer(reg.serverName()).isEmpty()) {
                logger.warn("Rejected instance register from {}: server {} not registered in Velocity",
                        connection.getServerInfo().getName(), reg.serverName());
                send(connection, new ProtocolEnvelope(1, MessageType.INSTANCE_REGISTER_ACK, envelope.correlationId(),
                        MessagePayloads.instanceRegisterAck(new MessagePayloads.InstanceRegisterAck(
                                reg.instanceId(), reg.sessionId(), false, "Server not found in proxy configuration"))));
                return;
            }

            InMemoryInstanceRegistry.RegisterOutcome outcome = instanceRegistry.register(
                    reg, System.nanoTime(), Instant.now());
            logger.info("Instance {} registered ({}) for game {}", reg.instanceId(), outcome, reg.gameId());

            send(connection, new ProtocolEnvelope(1, MessageType.INSTANCE_REGISTER_ACK, envelope.correlationId(),
                    MessagePayloads.instanceRegisterAck(new MessagePayloads.InstanceRegisterAck(
                            reg.instanceId(), reg.sessionId(), true, "Registered successfully"))));

            dispatchQueue(reg.gameId());
        } catch (ProtocolValidationException e) {
            logger.warn("Invalid instance register payload: {}", e.getMessage());
        }
    }

    private void handleInstanceHeartbeat(ServerConnection connection, ProtocolEnvelope envelope) {
        try {
            MessagePayloads.InstanceHeartbeat hb = MessagePayloads.instanceHeartbeat(envelope.payload());
            if (!validateBackendIdentity(connection, hb.instanceId(), null)) {
                heartbeatsRejectedCount.incrementAndGet();
                return;
            }

            InMemoryInstanceRegistry.HeartbeatOutcome outcome = instanceRegistry.heartbeat(
                    hb, System.nanoTime(), Instant.now());
            if (outcome == InMemoryInstanceRegistry.HeartbeatOutcome.REJECTED_STALE_SESSION
                    || outcome == InMemoryInstanceRegistry.HeartbeatOutcome.REJECTED_UNKNOWN_INSTANCE) {
                heartbeatsRejectedCount.incrementAndGet();
            } else {
                heartbeatsReceivedCount.incrementAndGet();
                if (outcome == InMemoryInstanceRegistry.HeartbeatOutcome.ACCEPTED_RECOVERED) {
                    instanceRegistry.find(hb.instanceId()).ifPresent(inst -> dispatchQueue(inst.gameId()));
                }
            }
        } catch (ProtocolValidationException e) {
            heartbeatsRejectedCount.incrementAndGet();
            logger.warn("Invalid instance heartbeat payload: {}", e.getMessage());
        }
    }

    private void handleInstanceStateChange(ServerConnection connection, ProtocolEnvelope envelope) {
        try {
            MessagePayloads.InstanceStateChange change = MessagePayloads.instanceStateChange(envelope.payload());
            if (!validateBackendIdentity(connection, change.instanceId(), null)) return;

            InMemoryInstanceRegistry.StateChangeOutcome outcome = instanceRegistry.updateState(
                    change, System.nanoTime(), Instant.now());
            if (outcome == InMemoryInstanceRegistry.StateChangeOutcome.SUCCESS) {
                instanceRegistry.find(change.instanceId()).ifPresent(inst -> {
                    if (inst.canAcceptPlayers() && inst.state() == GameState.WAITING) {
                        dispatchQueue(inst.gameId());
                    }
                });
            }
        } catch (ProtocolValidationException e) {
            logger.warn("Invalid state change payload: {}", e.getMessage());
        }
    }

    private void handleInstanceUnregister(ServerConnection connection, ProtocolEnvelope envelope) {
        try {
            MessagePayloads.InstanceUnregister unreg = MessagePayloads.instanceUnregister(envelope.payload());
            if (!validateBackendIdentity(connection, unreg.instanceId(), null)) return;

            InMemoryInstanceRegistry.UnregisterOutcome outcome = instanceRegistry.unregister(
                    unreg.instanceId(), unreg.sessionId(), unreg.reason());
            logger.info("Instance {} unregistered: {} ({})", unreg.instanceId(), outcome, unreg.reason());
            instanceRegistry.find(unreg.instanceId()).ifPresent(inst -> dispatchQueue(inst.gameId()));
        } catch (ProtocolValidationException e) {
            logger.warn("Invalid unregister payload: {}", e.getMessage());
        }
    }

    public void dispatchQueue(GameId gameId) {
        if (gameId == null || queues == null) return;
        while (true) {
            Optional<UUID> nextPlayerId = queues.peekNext(gameId);
            if (nextPlayerId.isEmpty()) break;

            UUID playerId = nextPlayerId.get();
            Player player = proxy.getPlayer(playerId).orElse(null);
            if (player == null || !player.isActive()) {
                queues.removePlayer(playerId);
                reservationService.cancel(playerId, "player inactive");
                continue;
            }

            if (inFlightTransfers.contains(playerId)) {
                break;
            }

            Optional<InstanceSnapshot> selectedInstance = instanceRouting.selectInstance(gameId);
            if (selectedInstance.isEmpty()) {
                break;
            }

            InstanceSnapshot target = selectedInstance.get();
            Optional<Reservation> reservation = reservationService.reserve(
                    target.instanceId(), playerId, gameId, Instant.now());
            if (reservation.isEmpty()) {
                break;
            }

            inFlightTransfers.add(playerId);
            routingAttemptsCount.incrementAndGet();
            QueueResult assigned = queues.assign(playerId, gameId, target.instanceId());
            if (assigned.code() != QueueResult.Code.ASSIGNED) {
                reservationService.cancel(playerId, "queue assign failed");
                inFlightTransfers.remove(playerId);
                break;
            }

            player.sendPlainMessage("Partida encontrada no servidor " + target.instanceId() + "! Conectando...");
            transfersInitiatedCount.incrementAndGet();
            transfers.transfer(playerId, target.instanceId()).thenAccept(result -> {
                if (!result.success()) {
                    transfersFailedCount.incrementAndGet();
                    routingFailuresCount.incrementAndGet();
                    reservationService.cancel(playerId, "transfer connection failed");
                    inFlightTransfers.remove(playerId);
                    Player p = proxy.getPlayer(playerId).orElse(null);
                    if (p != null && p.isActive()) {
                        queues.join(playerId, gameId);
                        p.sendPlainMessage("Falha ao conectar à partida. Você retornou à fila.");
                        dispatchQueue(gameId);
                    }
                }
            });
        }
    }

    private void sweepLivenessAndReservations() {
        try {
            long nowNanos = System.nanoTime();
            Instant now = Instant.now();
            HubConfigSnapshot snapshot = configSnapshot();
            if (snapshot == null || instanceRegistry == null) return;

            long suspectNanos = snapshot.registry().suspectThreshold().toNanos();
            long timeoutNanos = snapshot.registry().heartbeatTimeout().toNanos();
            List<InMemoryInstanceRegistry.LivenessTransition> transitions =
                    instanceRegistry.sweepLiveness(nowNanos, suspectNanos, timeoutNanos);

            for (InMemoryInstanceRegistry.LivenessTransition t : transitions) {
                if (t.newHealth() == InstanceHealth.UNAVAILABLE) {
                    logger.warn("Instance {} is now UNAVAILABLE (heartbeat timeout). Orphaned reservations: {}",
                            t.instanceId(), t.orphanedReservations().size());
                    for (UUID resId : t.orphanedReservations()) {
                        reservationService.find(resId).ifPresent(res ->
                                reservationService.cancel(res.playerId(), "instance became unavailable"));
                    }
                    instanceRegistry.find(t.instanceId()).ifPresent(inst -> dispatchQueue(inst.gameId()));
                }
            }

            List<Reservation> expired = reservationService.sweepExpired(now);
            if (!expired.isEmpty()) {
                for (Reservation res : expired) {
                    reservationExpirationsCount.incrementAndGet();
                    logger.warn("Reservation {} for player {} on {} expired",
                            res.reservationId(), res.playerId(), res.instanceId());
                    inFlightTransfers.remove(res.playerId());
                    dispatchQueue(res.gameId());
                }
            }
        } catch (Exception e) {
            logger.error("Error in liveness and reservation sweep", e);
        }
    }

    private boolean validateBackendIdentity(ServerConnection connection, ServerId instanceId, GameId gameId) {
        String backendName = connection.getServerInfo().getName();
        HubConfigSnapshot snapshot = configSnapshot();
        if (backendName.equalsIgnoreCase(instanceId.value())) return true;

        for (Map.Entry<String, GameId> entry : snapshot.registry().allowedInstances().entrySet()) {
            String pattern = entry.getKey();
            GameId allowedGame = entry.getValue();
            if (matchesPattern(backendName, pattern) && (gameId == null || allowedGame.equals(gameId))) {
                return true;
            }
        }

        ServerDefinition def = servers().find(instanceId).orElse(null);
        return def != null && (gameId == null || def.gameId().equals(gameId));
    }

    private boolean matchesPattern(String text, String pattern) {
        if (pattern.equals("*") || pattern.equals(text)) return true;
        if (pattern.endsWith("*")) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            return text.startsWith(prefix);
        }
        return false;
    }

    private boolean backendRateAllowed(String serverName) {
        long now = System.nanoTime();
        Long prev = backendLastRequestNanos.put(serverName, now);
        return prev == null || (now - prev) >= 20_000_000L;
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
            instanceRouting = new InstanceAwareRoutingService(nextGames, instanceRegistry, nextServers);
            routing.set(instanceRouting);
            transfers = new VelocityTransferService(proxy, instanceRegistry, nextServers);
            config.set(next);
            source.sendPlainMessage("BigBangHub configuration reloaded.");
        } catch (ConfigException | IllegalArgumentException exception) {
            source.sendPlainMessage("Reload rejected; previous configuration kept: " + exception.getMessage());
            logger.warn("Keeping previous configuration: {}", exception.getMessage());
        }
    }

    void join(Player player, GameId game) {
        queues.join(player.getUniqueId(), game).thenAccept(result -> {
            player.sendPlainMessage(result.message());
            if (result.code() == QueueResult.Code.JOINED || result.code() == QueueResult.Code.ALREADY_QUEUED) {
                dispatchQueue(game);
            }
        });
    }

    private void handle(ServerConnection connection, Player player, ProtocolEnvelope envelope) {
        switch (envelope.messageType()) {
            case QUEUE_JOIN -> handleQueueJoin(connection, player, envelope);
            case QUEUE_LEAVE -> handleQueueLeave(connection, player, envelope);
            case QUEUE_STATUS -> handleQueueStatus(connection, player, envelope);
            case SERVER_CONNECT -> handleServerConnect(connection, player, envelope);
            default -> logger.warn("Rejected unexpected request type {}", envelope.messageType());
        }
    }

    private void handleQueueJoin(ServerConnection connection, Player player, ProtocolEnvelope envelope) {
        try {
            MessagePayloads.QueueJoin request = MessagePayloads.queueJoin(envelope.payload());
            if (!player.getUniqueId().equals(request.playerId())) { rejectIdentity(connection, envelope, player); return; }
            if (!games().find(request.gameId()).map(game -> game.enabled() && game.queueEnabled()).orElse(false)) {
                sendQueueResponse(connection, envelope, QueueResult.of(
                        QueueResult.Code.UNAVAILABLE, request.gameId(), 0, 0,
                        "Este minigame está temporariamente indisponível."), player.getUniqueId());
                return;
            }
            queues.join(player.getUniqueId(), request.gameId()).thenAccept(result -> {
                sendQueueResponse(connection, envelope, result, player.getUniqueId());
                if (result.code() == QueueResult.Code.JOINED || result.code() == QueueResult.Code.ALREADY_QUEUED) {
                    dispatchQueue(request.gameId());
                }
            });
        } catch (ProtocolValidationException exception) { logger.warn("Invalid queue join payload: {}", exception.getMessage()); }
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
                    QueueResult.of(QueueResult.Code.JOINED,
                            status.game().orElse(null), status.position(), status.size(),
                            status.game().isPresent() ? "Fila consultada." : "Você não está em uma fila."), player.getUniqueId()));
        } catch (ProtocolValidationException exception) { logger.warn("Invalid queue status payload: {}", exception.getMessage()); }
    }

    private void handleServerConnect(ServerConnection connection, Player player, ProtocolEnvelope envelope) {
        try {
            MessagePayloads.ServerConnect request = MessagePayloads.serverConnect(envelope.payload());
            if (!player.getUniqueId().equals(request.playerId())) { rejectIdentity(connection, envelope, player); return; }
            if (servers().find(request.serverId()).isEmpty() && instanceRegistry.find(request.serverId()).isEmpty()) {
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
                                   QueueResult result, UUID playerId) {
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
        queues = new InMemoryQueueService(new QueueEventBus());
        instanceEventBus = new InstanceEventBus();
        instanceRegistry = new InMemoryInstanceRegistry(instanceEventBus);
        reservationService = new InMemoryReservationService(instanceRegistry, snapshot.registry().reservationTtl());
        instanceRouting = new InstanceAwareRoutingService(nextGames, instanceRegistry, nextServers);
        transfers = new VelocityTransferService(proxy, instanceRegistry, nextServers);

        games.set(nextGames);
        servers.set(nextServers);
        routing.set(instanceRouting);
        config.set(snapshot);

        reservationService.addExpiredListener(event -> {
            reservationExpirationsCount.incrementAndGet();
            logger.warn("Reservation expired for player {} on instance {}",
                    event.reservation().playerId(), event.reservation().instanceId());
            dispatchQueue(event.reservation().gameId());
        });

        instanceEventBus.add(event -> {
            if (event instanceof InstanceRegisteredEvent regEvent) {
                registrationsCount.incrementAndGet();
                dispatchQueue(regEvent.instance().gameId());
            } else if (event instanceof InstanceHealthChangedEvent healthEvent) {
                if (healthEvent.newHealth() == InstanceHealth.HEALTHY) {
                    instanceRegistry.find(healthEvent.instanceId()).ifPresent(inst -> dispatchQueue(inst.gameId()));
                }
            } else if (event instanceof InstanceStateChangedEvent stateEvent) {
                if (stateEvent.newState() == GameState.WAITING) {
                    instanceRegistry.find(stateEvent.instanceId()).ifPresent(inst -> dispatchQueue(inst.gameId()));
                }
            }
        });

        livenessTask = proxy.getScheduler().buildTask(this, this::sweepLivenessAndReservations)
                .repeat(Duration.ofSeconds(1))
                .schedule();
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
    public InMemoryInstanceRegistry instanceRegistry() { return instanceRegistry; }
    public InMemoryReservationService reservationService() { return reservationService; }
    public InMemoryQueueService queueService() { return queues; }

    // Metric accessors
    public long registrationsCount() { return registrationsCount.get(); }
    public long heartbeatsReceivedCount() { return heartbeatsReceivedCount.get(); }
    public long heartbeatsRejectedCount() { return heartbeatsRejectedCount.get(); }
    public long routingAttemptsCount() { return routingAttemptsCount.get(); }
    public long routingFailuresCount() { return routingFailuresCount.get(); }
    public long transfersInitiatedCount() { return transfersInitiatedCount.get(); }
    public long transfersSucceededCount() { return transfersSucceededCount.get(); }
    public long transfersFailedCount() { return transfersFailedCount.get(); }
    public long reservationExpirationsCount() { return reservationExpirationsCount.get(); }

    @Override public ServerRole role() { return ServerRole.GENERIC; }
    @Override public GameRegistry games() { return games.get(); }
    @Override public ServerRegistry servers() { return servers.get(); }
    @Override public InstanceRegistry instances() { return instanceRegistry; }
    @Override public Optional<InstanceService> instance() { return Optional.empty(); }
    @Override public QueueService queues() { return queues; }
    @Override public RoutingService routing() { return routing.get(); }
    @Override public PlayerTransferService transfers() { return transfers; }
    @Override public void addQueueListener(Consumer<QueueEvent> listener) { queues.addListener(listener); }
    @Override public void removeQueueListener(Consumer<QueueEvent> listener) { queues.removeListener(listener); }
    @Override public void addInstanceListener(Consumer<InstanceEvent> listener) { instanceEventBus.add(listener); }
    @Override public void removeInstanceListener(Consumer<InstanceEvent> listener) { instanceEventBus.remove(listener); }
}
