package com.bigbangcraft.hub.velocity;

import com.bigbangcraft.hub.api.AdmissionTicket;
import com.bigbangcraft.hub.api.BigBangHubApi;
import com.bigbangcraft.hub.api.DisconnectPolicy;
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
import com.bigbangcraft.hub.api.MatchAbortedEvent;
import com.bigbangcraft.hub.api.MatchCreatedEvent;
import com.bigbangcraft.hub.api.MatchDefinition;
import com.bigbangcraft.hub.api.MatchEvent;
import com.bigbangcraft.hub.api.MatchException;
import com.bigbangcraft.hub.api.MatchFinishedEvent;
import com.bigbangcraft.hub.api.MatchId;
import com.bigbangcraft.hub.api.MatchManager;
import com.bigbangcraft.hub.api.MatchParticipant;
import com.bigbangcraft.hub.api.MatchParticipantLeftEvent;
import com.bigbangcraft.hub.api.MatchResult;
import com.bigbangcraft.hub.api.MatchSnapshot;
import com.bigbangcraft.hub.api.MatchState;
import com.bigbangcraft.hub.api.MatchStateChangedEvent;
import com.bigbangcraft.hub.api.ParticipantRole;
import com.bigbangcraft.hub.api.ParticipantState;
import com.bigbangcraft.hub.api.PartyEvent;
import com.bigbangcraft.hub.api.PartyException;
import com.bigbangcraft.hub.api.PartyId;
import com.bigbangcraft.hub.api.PartyInvite;
import com.bigbangcraft.hub.api.PartyService;
import com.bigbangcraft.hub.api.PartySnapshot;
import com.bigbangcraft.hub.api.PartyState;
import com.bigbangcraft.hub.api.PlayerAdmissionAcceptedEvent;
import com.bigbangcraft.hub.api.PlayerTransferService;
import com.bigbangcraft.hub.api.QueueEvent;
import com.bigbangcraft.hub.api.QueueResult;
import com.bigbangcraft.hub.api.QueueService;
import com.bigbangcraft.hub.api.QueueStatus;
import com.bigbangcraft.hub.api.Reservation;
import com.bigbangcraft.hub.api.ReturnReason;
import com.bigbangcraft.hub.api.RoutingService;
import com.bigbangcraft.hub.api.ServerDefinition;
import com.bigbangcraft.hub.api.ServerId;
import com.bigbangcraft.hub.api.ServerRegistry;
import com.bigbangcraft.hub.api.ServerRole;
import com.bigbangcraft.hub.common.AdmissionTicketService;
import com.bigbangcraft.hub.common.ConfigException;
import com.bigbangcraft.hub.common.ConfigLoader;
import com.bigbangcraft.hub.common.HubConfigSnapshot;
import com.bigbangcraft.hub.common.InMemoryGameRegistry;
import com.bigbangcraft.hub.common.InMemoryInstanceRegistry;
import com.bigbangcraft.hub.common.InMemoryMatchRegistry;
import com.bigbangcraft.hub.common.InMemoryPartyService;
import com.bigbangcraft.hub.common.InMemoryQueueService;
import com.bigbangcraft.hub.common.InMemoryReservationService;
import com.bigbangcraft.hub.common.InMemoryServerRegistry;
import com.bigbangcraft.hub.common.InstanceAwareRoutingService;
import com.bigbangcraft.hub.common.InstanceEventBus;
import com.bigbangcraft.hub.common.MatchEventBus;
import com.bigbangcraft.hub.common.PartyEventBus;
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
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Plugin(id = "bigbanghub", name = "BigBangHub", version = "0.3.0", authors = {"BigBangCraft"})
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

    // Telemetry and operational metrics
    private final AtomicLong registrationsCount = new AtomicLong();
    private final AtomicLong heartbeatsReceivedCount = new AtomicLong();
    private final AtomicLong heartbeatsRejectedCount = new AtomicLong();
    private final AtomicLong matchesCreatedCount = new AtomicLong();
    private final AtomicLong matchesStartedCount = new AtomicLong();
    private final AtomicLong matchesFinishedCount = new AtomicLong();
    private final AtomicLong matchesAbortedCount = new AtomicLong();
    private final AtomicLong admissionsAcceptedCount = new AtomicLong();
    private final AtomicLong admissionsRejectedCount = new AtomicLong();
    private final AtomicLong routingAttemptsCount = new AtomicLong();
    private final AtomicLong routingFailuresCount = new AtomicLong();
    private final AtomicLong transfersInitiatedCount = new AtomicLong();
    private final AtomicLong transfersSucceededCount = new AtomicLong();
    private final AtomicLong transfersFailedCount = new AtomicLong();
    private final AtomicLong returnFailuresCount = new AtomicLong();
    private final AtomicLong reservationExpirationsCount = new AtomicLong();

    private MinecraftChannelIdentifier channel;
    private ProtocolCodec codec;
    private InMemoryQueueService queues;
    private InMemoryInstanceRegistry instanceRegistry;
    private InMemoryReservationService reservationService;
    private InMemoryMatchRegistry matchRegistry;
    private AdmissionTicketService ticketService;
    private MatchEventBus matchEventBus;
    private VelocityMatchManager matchManager;
    private InstanceEventBus instanceEventBus;
    private InstanceAwareRoutingService instanceRouting;
    private VelocityTransferService transfers;
    private ScheduledTask livenessTask;
    private InMemoryPartyService partyService;
    private PartyEventBus partyEventBus;

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
            proxy.getCommandManager().register(
                    proxy.getCommandManager().metaBuilder("party").aliases("p").plugin(this).build(),
                    new VelocityPartyCommand(this));
            proxy.getCommandManager().register(
                    proxy.getCommandManager().metaBuilder("reconnect").plugin(this).build(),
                    new VelocityReconnectCommand(this));
            logger.info("BigBangHub Velocity 0.3.0 enabled with {} games", games().games().size());
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
        if (ticketService != null) ticketService.clear();
        if (matchRegistry != null) matchRegistry.clear();
        if (reservationService != null) reservationService.clear();
        if (instanceRegistry != null) instanceRegistry.clear();
        if (queues != null) queues.clear();
        if (partyService != null) partyService.clear();
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        lastRequestNanos.remove(playerId);
        inFlightTransfers.remove(playerId);
        if (partyService != null) {
            partyService.partyOf(playerId).ifPresent(party -> {
                if (party.state() == PartyState.QUEUED) {
                    queues.leave(party.leader());
                    partyService.transitionState(party.partyId(), PartyState.IDLE);
                    for (UUID memberId : party.memberIds()) {
                        if (!memberId.equals(playerId)) {
                            proxy.getPlayer(memberId).ifPresent(p ->
                                    p.sendPlainMessage("A party saiu da fila pois um membro desconectou."));
                        }
                    }
                }
            });
            partyService.handlePlayerDisconnect(playerId);
        }
        if (queues != null) queues.removePlayer(playerId);
        if (reservationService != null) reservationService.cancel(playerId, "player disconnected");
        if (ticketService != null) ticketService.invalidateForPlayer(playerId);
        if (matchRegistry != null) {
            matchRegistry.findActiveForPlayer(playerId).ifPresent(match -> {
                HubConfigSnapshot snapshot = configSnapshot();
                Duration reconnectTimeout = snapshot.match().reconnectTimeout();
                if (!match.state().isTerminal() && !reconnectTimeout.isZero() && !reconnectTimeout.isNegative()) {
                    Instant expiresAt = Instant.now().plus(reconnectTimeout);
                    matchRegistry.setPlayerDisconnected(match.matchId(), playerId, expiresAt, Instant.now());
                    logger.info("Player {} disconnected from active match {}, holding slot until {}",
                            playerId, match.matchId(), expiresAt);
                } else {
                    matchRegistry.removePlayer(match.matchId(), playerId, "player disconnected", Instant.now());
                }
            });
        }
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        if (partyService != null) partyService.handlePlayerReconnect(player.getUniqueId());
        ServerConnection current = player.getCurrentServer().orElse(null);
        if (current == null) return;
        ServerId serverId = ServerId.of(current.getServerInfo().getName());
        if (reservationService != null) {
            boolean confirmed = reservationService.confirm(player.getUniqueId(), serverId);
            if (confirmed) {
                transfersSucceededCount.incrementAndGet();
                logger.info("Confirmed reservation of player {} on {}", player.getUsername(), serverId);
            }
        }
        inFlightTransfers.remove(player.getUniqueId());

        if (current.getServerInfo().getName().equals(configSnapshot().proxy().hubServerName())) {
            checkAndHandleReconnect(player);
        }
    }

    public Optional<MatchSnapshot> findPendingReconnect(UUID playerId) {
        if (matchRegistry == null) return Optional.empty();
        Optional<MatchSnapshot> active = matchRegistry.findActiveForPlayer(playerId);
        if (active.isEmpty()) return Optional.empty();
        MatchSnapshot snapshot = active.get();
        if (snapshot.state().isTerminal()) return Optional.empty();
        Optional<MatchParticipant> participant = matchRegistry.participant(snapshot.matchId(), playerId);
        if (participant.isPresent() && participant.get().state() == ParticipantState.DISCONNECTED) {
            return Optional.of(snapshot);
        }
        return Optional.empty();
    }

    public void checkAndHandleReconnect(Player player) {
        Optional<MatchSnapshot> pending = findPendingReconnect(player.getUniqueId());
        if (pending.isEmpty()) return;

        HubConfigSnapshot snapshot = configSnapshot();
        if (snapshot.match().autoReconnect()) {
            player.sendPlainMessage("§a[BigBangHub] Partida em andamento encontrada! Reconectando...");
            reconnectPlayer(player);
        } else {
            player.sendMessage(Component.text("§e[BigBangHub] Você possui uma partida em andamento! ")
                    .append(Component.text("§a§l[CLIQUE AQUI PARA RECONECTAR]")
                            .clickEvent(ClickEvent.runCommand("/reconnect"))
                            .hoverEvent(HoverEvent.showText(Component.text("§7Reconectar à partida")))));
        }
    }

    public boolean reconnectPlayer(Player player) {
        Optional<MatchSnapshot> pending = findPendingReconnect(player.getUniqueId());
        if (pending.isEmpty()) {
            player.sendPlainMessage("§cVocê não possui nenhuma partida em andamento para reconectar.");
            return false;
        }

        MatchSnapshot match = pending.get();
        Optional<RegisteredServer> targetServer = proxy.getServer(match.instanceId().value());
        if (targetServer.isEmpty()) {
            player.sendPlainMessage("§cO servidor da sua partida não está mais disponível.");
            return false;
        }

        Instant now = Instant.now();
        Duration admissionTtl = configSnapshot().match().admissionTimeout();
        Optional<PartyId> partyId = (partyService != null) ? partyService.partyOf(player.getUniqueId()).map(PartySnapshot::partyId) : Optional.empty();
        MatchParticipant current = matchRegistry.participant(match.matchId(), player.getUniqueId()).orElseThrow();

        AdmissionTicket ticket = ticketService.issue(
                player.getUniqueId(), match.matchId(), match.instanceId(), current.role(), now, admissionTtl, partyId, true);

        inFlightTransfers.add(player.getUniqueId());
        player.createConnectionRequest(targetServer.get()).connect().thenAccept(result -> {
            inFlightTransfers.remove(player.getUniqueId());
            if (!result.isSuccessful()) {
                ticketService.invalidateForPlayer(player.getUniqueId());
                player.sendPlainMessage("§cFalha ao reconectar ao servidor da partida.");
            }
        });
        return true;
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
        if (ticketService != null) {
            ticketService.invalidateForPlayer(player.getUniqueId());
        }
        if (matchRegistry != null) {
            matchRegistry.findActiveForPlayer(player.getUniqueId()).ifPresent(match -> {
                matchRegistry.removePlayer(match.matchId(), player.getUniqueId(), "kicked from server", Instant.now());
            });
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
                case MATCH_CREATE -> handleMatchCreate(connection, envelope);
                case MATCH_STATE_CHANGE -> handleMatchStateChange(connection, envelope);
                case ADMISSION_REQUEST -> handleAdmissionRequest(connection, envelope);
                case PARTICIPANT_STATE_CHANGE -> handleParticipantStateChange(connection, envelope);
                case MATCH_FINISH -> handleMatchFinish(connection, envelope);
                case MATCH_ABORT -> handleMatchAbort(connection, envelope);
                case INSTANCE_READY -> handleInstanceReady(connection, envelope);
                case PLAYER_RETURN -> handlePlayerReturn(connection, envelope);
                default -> {
                    if (backendName.equals(snapshot.proxy().hubServerName()) || envelope.messageType().isPartyMessage()) {
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

            if (outcome == InMemoryInstanceRegistry.RegisterOutcome.REPLACED) {
                // If instance process rebooted, clean up any previous match
                matchRegistry.reconcileInstanceCrashOrShutdown(reg.instanceId(), null, Instant.now());
            }

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
            matchRegistry.reconcileInstanceCrashOrShutdown(unreg.instanceId(), unreg.sessionId(), Instant.now());
            instanceRegistry.find(unreg.instanceId()).ifPresent(inst -> dispatchQueue(inst.gameId()));
        } catch (ProtocolValidationException e) {
            logger.warn("Invalid unregister payload: {}", e.getMessage());
        }
    }

    private void handleMatchCreate(ServerConnection connection, ProtocolEnvelope envelope) {
        try {
            MessagePayloads.MatchCreate create = MessagePayloads.matchCreate(envelope.payload());
            if (!validateBackendIdentity(connection, create.instanceId(), create.gameId())) {
                send(connection, new ProtocolEnvelope(1, MessageType.MATCH_CREATE_ACK, envelope.correlationId(),
                        MessagePayloads.matchCreateAck(new MessagePayloads.MatchCreateAck(
                                create.matchId(), false, 0, "Unauthorized backend identity"))));
                return;
            }

            Optional<InstanceSnapshot> inst = instanceRegistry.find(create.instanceId());
            if (inst.isEmpty() || !inst.get().sessionId().equals(create.sessionId())) {
                send(connection, new ProtocolEnvelope(1, MessageType.MATCH_CREATE_ACK, envelope.correlationId(),
                        MessagePayloads.matchCreateAck(new MessagePayloads.MatchCreateAck(
                                create.matchId(), false, 0, "Invalid or stale instance session"))));
                return;
            }

            MatchDefinition definition = MatchDefinition.builder()
                    .gameId(create.gameId())
                    .minPlayers(create.minPlayers())
                    .maxPlayers(create.maxPlayers())
                    .allowLateJoin(create.allowLateJoin())
                    .arenaId(create.arenaId().isBlank() ? null : create.arenaId())
                    .build();

            InMemoryMatchRegistry.MatchSessionState state = matchRegistry.createMatch(
                    create.matchId(), definition, create.instanceId(), create.sessionId(), Instant.now());

            send(connection, new ProtocolEnvelope(1, MessageType.MATCH_CREATE_ACK, envelope.correlationId(),
                    MessagePayloads.matchCreateAck(new MessagePayloads.MatchCreateAck(
                            create.matchId(), true, state.stateMachine().revision(), "Match created successfully"))));

            logger.info("Match {} created on {} for game {}", create.matchId(), create.instanceId(), create.gameId());
            dispatchQueue(create.gameId());
        } catch (MatchException | ProtocolValidationException e) {
            logger.warn("Failed to create match from backend: {}", e.getMessage());
            try {
                MessagePayloads.MatchCreate create = MessagePayloads.matchCreate(envelope.payload());
                send(connection, new ProtocolEnvelope(1, MessageType.MATCH_CREATE_ACK, envelope.correlationId(),
                        MessagePayloads.matchCreateAck(new MessagePayloads.MatchCreateAck(
                                create.matchId(), false, 0, e.getMessage()))));
            } catch (Exception ignored) { }
        }
    }

    private void handleMatchStateChange(ServerConnection connection, ProtocolEnvelope envelope) {
        try {
            MessagePayloads.MatchStateChange change = MessagePayloads.matchStateChange(envelope.payload());
            if (!validateBackendIdentity(connection, change.instanceId(), null)) return;

            MatchState targetState = mapWireState(change.state());
            Optional<MatchSnapshot> existing = matchRegistry.find(change.matchId());
            if (existing.isEmpty()) {
                send(connection, new ProtocolEnvelope(1, MessageType.MATCH_STATE_ACK, envelope.correlationId(),
                        MessagePayloads.matchStateAck(new MessagePayloads.MatchStateAck(
                                change.matchId(), change.revision(), change.state(), false, "Match not found"))));
                return;
            }

            MatchState currentState = existing.get().state();
            boolean transitioned = matchRegistry.transitionState(
                    change.matchId(), change.sessionId(), change.revision(), currentState, targetState, Instant.now());

            long currentRevision = matchRegistry.find(change.matchId()).map(MatchSnapshot::revision).orElse(change.revision());
            send(connection, new ProtocolEnvelope(1, MessageType.MATCH_STATE_ACK, envelope.correlationId(),
                    MessagePayloads.matchStateAck(new MessagePayloads.MatchStateAck(
                            change.matchId(), currentRevision, change.state(), transitioned,
                            transitioned ? "State updated" : "Transition rejected"))));

            if (transitioned && targetState == MatchState.WAITING) {
                dispatchQueue(existing.get().gameId());
            }
        } catch (ProtocolValidationException e) {
            logger.warn("Invalid match state change payload: {}", e.getMessage());
        }
    }

    private void handleAdmissionRequest(ServerConnection connection, ProtocolEnvelope envelope) {
        try {
            MessagePayloads.AdmissionRequest req = MessagePayloads.admissionRequest(envelope.payload());
            if (!validateBackendIdentity(connection, req.instanceId(), null)) {
                admissionsRejectedCount.incrementAndGet();
                send(connection, new ProtocolEnvelope(1, MessageType.ADMISSION_RESPONSE, envelope.correlationId(),
                        MessagePayloads.admissionResponse(new MessagePayloads.AdmissionResponse(
                                req.ticketId(), req.playerId(), req.matchId(), false,
                                MessagePayloads.ParticipantRoleWire.PLAYER, "Unauthorized backend"))));
                return;
            }

            Instant now = Instant.now();
            AdmissionTicket ticket = ticketService.consume(
                    req.ticketId(), req.playerId(), req.matchId(), req.instanceId(), req.token(), now);
            MatchParticipant participant = matchRegistry.admitPlayer(ticket, now);

            if (partyService != null) {
                partyService.partyOf(req.playerId()).ifPresent(party -> {
                    if (party.state() == PartyState.ASSIGNED) {
                        partyService.transitionState(party.partyId(), PartyState.IN_MATCH);
                    }
                });
            }

            admissionsAcceptedCount.incrementAndGet();
            MessagePayloads.ParticipantRoleWire roleWire = (ticket.role() == ParticipantRole.SPECTATOR)
                    ? MessagePayloads.ParticipantRoleWire.SPECTATOR : MessagePayloads.ParticipantRoleWire.PLAYER;

            send(connection, new ProtocolEnvelope(1, MessageType.ADMISSION_RESPONSE, envelope.correlationId(),
                    MessagePayloads.admissionResponse(new MessagePayloads.AdmissionResponse(
                            req.ticketId(), req.playerId(), req.matchId(), true, roleWire,
                            ticket.isReconnect() ? "Reconnected successfully" : "Admitted successfully",
                            ticket.partyId(), ticket.isReconnect()))));

            logger.info("Player {} admitted into match {} on {} ({}, reconnect={})",
                    req.playerId(), req.matchId(), req.instanceId(), ticket.role(), ticket.isReconnect());
        } catch (MatchException e) {
            admissionsRejectedCount.incrementAndGet();
            logger.warn("Admission rejected for ticket: {}", e.getMessage());
            try {
                MessagePayloads.AdmissionRequest req = MessagePayloads.admissionRequest(envelope.payload());
                matchRegistry.releasePendingAdmission(req.matchId());
                reservationService.cancel(req.playerId(), "admission rejected: " + e.getMessage());
                send(connection, new ProtocolEnvelope(1, MessageType.ADMISSION_RESPONSE, envelope.correlationId(),
                        MessagePayloads.admissionResponse(new MessagePayloads.AdmissionResponse(
                                req.ticketId(), req.playerId(), req.matchId(), false,
                                MessagePayloads.ParticipantRoleWire.PLAYER, e.getMessage()))));
                safeReturnPlayerToHub(req.playerId(), ReturnReason.DIRECT_JOIN_REJECTED, e.getMessage());
            } catch (Exception ignored) { }
        } catch (ProtocolValidationException e) {
            admissionsRejectedCount.incrementAndGet();
            logger.warn("Malformed admission request: {}", e.getMessage());
        }
    }

    private void handleParticipantStateChange(ServerConnection connection, ProtocolEnvelope envelope) {
        try {
            MessagePayloads.ParticipantStateChange change = MessagePayloads.participantStateChange(envelope.payload());
            Instant now = Instant.now();
            switch (change.state()) {
                case ELIMINATED -> matchRegistry.eliminatePlayer(change.matchId(), change.playerId(), now);
                case SPECTATING -> matchRegistry.setPlayerSpectator(change.matchId(), change.playerId(), now);
                case DISCONNECTED -> {
                    Duration reconnectTimeout = configSnapshot().match().reconnectTimeout();
                    matchRegistry.setPlayerDisconnected(change.matchId(), change.playerId(), now.plus(reconnectTimeout), now);
                }
                case LEFT -> matchRegistry.removePlayer(change.matchId(), change.playerId(), "left", now);
                default -> { }
            }
        } catch (ProtocolValidationException e) {
            logger.warn("Invalid participant state change payload: {}", e.getMessage());
        }
    }

    private void handleMatchFinish(ServerConnection connection, ProtocolEnvelope envelope) {
        try {
            MessagePayloads.MatchFinish finish = MessagePayloads.matchFinish(envelope.payload());
            if (!validateBackendIdentity(connection, finish.instanceId(), null)) return;

            MatchResult.Outcome outcome = switch (finish.outcome()) {
                case WIN -> MatchResult.Outcome.WIN;
                case DRAW -> MatchResult.Outcome.DRAW;
                case ABORTED -> MatchResult.Outcome.ABORTED;
            };

            MatchResult result = new MatchResult(
                    outcome,
                    new HashSet<>(finish.winnerIds()),
                    Duration.ofMillis(finish.durationMillis()),
                    Map.of());

            boolean finished = matchRegistry.finishMatch(
                    finish.matchId(), finish.sessionId(), finish.revision(), result, Instant.now());
            if (finished) {
                logger.info("Match {} finished on {} (outcome: {}, duration: {}ms)",
                        finish.matchId(), finish.instanceId(), outcome, finish.durationMillis());
                // Safe return all players
                matchRegistry.findSession(finish.matchId()).ifPresent(session -> {
                    safeReturnPlayersToHub(
                            session.participants().stream().map(MatchParticipant::playerId).toList(),
                            ReturnReason.MATCH_FINISHED, "Match ended");
                });
            }
        } catch (ProtocolValidationException e) {
            logger.warn("Invalid match finish payload: {}", e.getMessage());
        }
    }

    private void handleMatchAbort(ServerConnection connection, ProtocolEnvelope envelope) {
        try {
            MessagePayloads.MatchAbort abort = MessagePayloads.matchAbort(envelope.payload());
            if (!validateBackendIdentity(connection, abort.instanceId(), null)) return;

            boolean aborted = matchRegistry.abortMatch(abort.matchId(), abort.reason(), Instant.now());
            if (aborted) {
                logger.info("Match {} aborted on {}: {}", abort.matchId(), abort.instanceId(), abort.reason());
                matchRegistry.findSession(abort.matchId()).ifPresent(session -> {
                    safeReturnPlayersToHub(
                            session.participants().stream().map(MatchParticipant::playerId).toList(),
                            ReturnReason.MATCH_ABORTED, abort.reason());
                });
            }
        } catch (ProtocolValidationException e) {
            logger.warn("Invalid match abort payload: {}", e.getMessage());
        }
    }

    private void handleInstanceReady(ServerConnection connection, ProtocolEnvelope envelope) {
        try {
            MessagePayloads.InstanceReady ready = MessagePayloads.instanceReady(envelope.payload());
            if (!validateBackendIdentity(connection, ready.instanceId(), null)) return;

            boolean marked = matchRegistry.markInstanceReady(ready.instanceId(), ready.matchId(), Instant.now());
            if (marked) {
                logger.info("Instance {} confirmed cleanup complete for match {}; now READY",
                        ready.instanceId(), ready.matchId());
                instanceRegistry.find(ready.instanceId()).ifPresent(inst -> dispatchQueue(inst.gameId()));
            }
        } catch (ProtocolValidationException e) {
            logger.warn("Invalid instance ready payload: {}", e.getMessage());
        }
    }

    private void handlePlayerReturn(ServerConnection connection, ProtocolEnvelope envelope) {
        try {
            MessagePayloads.PlayerReturn ret = MessagePayloads.playerReturn(envelope.payload());
            ReturnReason reason = switch (ret.reason()) {
                case MATCH_FINISHED -> ReturnReason.MATCH_FINISHED;
                case MATCH_ABORTED -> ReturnReason.MATCH_ABORTED;
                case PLAYER_ELIMINATED -> ReturnReason.PLAYER_ELIMINATED;
                case PLAYER_LEFT -> ReturnReason.PLAYER_LEFT;
                case SERVER_FAILURE -> ReturnReason.SERVER_FAILURE;
                case ADMIN_FORCE_RETURN -> ReturnReason.ADMIN_FORCE_RETURN;
                case DIRECT_JOIN_REJECTED -> ReturnReason.DIRECT_JOIN_REJECTED;
            };
            safeReturnPlayerToHub(ret.playerId(), reason, ret.message());
        } catch (ProtocolValidationException e) {
            logger.warn("Invalid player return payload: {}", e.getMessage());
        }
    }

    public void safeReturnPlayerToHub(UUID playerId, ReturnReason reason, String message) {
        HubConfigSnapshot snapshot = configSnapshot();
        if (snapshot == null) return;
        String hubName = snapshot.proxy().hubServerName();
        Player player = proxy.getPlayer(playerId).orElse(null);
        if (player == null || !player.isActive()) return;

        if (partyService != null) {
            partyService.partyOf(playerId).ifPresent(party -> {
                if (party.state() == PartyState.IN_MATCH || party.state() == PartyState.ASSIGNED) {
                    partyService.transitionState(party.partyId(), PartyState.IDLE);
                }
            });
        }

        RegisteredServer hubServer = proxy.getServer(hubName).orElse(null);
        if (hubServer == null) {
            returnFailuresCount.incrementAndGet();
            logger.error("Safe return failed: Hub server '{}' is not registered in Velocity!", hubName);
            return;
        }

        player.createConnectionRequest(hubServer).connect().thenAccept(result -> {
            if (result.isSuccessful()) {
                logger.info("Player {} safely returned to {} (reason: {})", player.getUsername(), hubName, reason);
            } else {
                returnFailuresCount.incrementAndGet();
                logger.warn("Failed to safely return player {} to {}: {}",
                        player.getUsername(), hubName, result.getReasonComponent().orElse(null));
            }
        });
    }

    public void safeReturnPlayersToHub(Collection<UUID> playerIds, ReturnReason reason, String message) {
        for (UUID playerId : playerIds) {
            safeReturnPlayerToHub(playerId, reason, message);
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
                ticketService.invalidateForPlayer(playerId);
                continue;
            }

            if (inFlightTransfers.contains(playerId)) {
                break;
            }

            Optional<PartySnapshot> partyOpt = (partyService != null) ? partyService.partyOf(playerId) : Optional.empty();
            List<UUID> groupMembers;
            PartySnapshot party = null;

            if (partyOpt.isPresent()) {
                party = partyOpt.get();
                boolean allReady = true;
                for (UUID memberId : party.memberIds()) {
                    Player m = proxy.getPlayer(memberId).orElse(null);
                    if (m == null || !m.isActive() || inFlightTransfers.contains(memberId)) {
                        allReady = false;
                        break;
                    }
                }
                if (!allReady) {
                    break;
                }
                groupMembers = new ArrayList<>(party.memberIds());
            } else {
                groupMembers = List.of(playerId);
            }

            int groupSize = groupMembers.size();

            // Route to active MatchSession first (preferring FILL_EXISTING_MATCH)
            Collection<MatchSnapshot> activeMatches = matchRegistry.activeMatchesForGame(gameId);
            MatchSnapshot targetMatch = activeMatches.stream()
                    .filter(MatchSnapshot::canAcceptParticipants)
                    .filter(m -> m.effectiveCapacity() >= groupSize)
                    .filter(m -> instanceRegistry.find(m.instanceId())
                            .map(inst -> inst.canAcceptPlayers() && inst.effectiveCapacity() >= groupSize)
                            .orElse(false))
                    .max(Comparator.comparingInt(m -> m.participantCount() + m.pendingAdmissions()))
                    .orElse(null);

            ServerId targetInstanceId;
            MatchId targetMatchId;

            if (targetMatch != null) {
                targetInstanceId = targetMatch.instanceId();
                targetMatchId = targetMatch.matchId();
            } else {
                Optional<InstanceSnapshot> candidateInst = instanceRouting.selectInstance(gameId, groupSize);
                if (candidateInst.isEmpty()) {
                    break;
                }
                InstanceSnapshot inst = candidateInst.get();
                if (matchRegistry.findActiveForInstance(inst.instanceId()).isPresent()) {
                    break;
                }
                targetInstanceId = inst.instanceId();
                targetMatchId = null;
            }

            Instant now = Instant.now();
            List<Reservation> reservationsMade = new ArrayList<>(groupSize);
            boolean allReservationsSucceeded = true;

            for (UUID memberId : groupMembers) {
                Optional<Reservation> res = reservationService.reserve(targetInstanceId, memberId, gameId, now);
                if (res.isPresent()) {
                    reservationsMade.add(res.get());
                } else {
                    allReservationsSucceeded = false;
                    break;
                }
            }

            if (!allReservationsSucceeded) {
                for (Reservation res : reservationsMade) {
                    reservationService.cancel(res.playerId(), "party atomic reservation rollback");
                }
                break;
            }

            // If routing into existing match, reserve match admission slots
            if (targetMatchId != null) {
                int admissionsReserved = 0;
                for (int i = 0; i < groupSize; i++) {
                    if (matchRegistry.reserveAdmission(targetMatchId)) {
                        admissionsReserved++;
                    } else {
                        break;
                    }
                }
                if (admissionsReserved < groupSize) {
                    for (int i = 0; i < admissionsReserved; i++) {
                        matchRegistry.releasePendingAdmission(targetMatchId);
                    }
                    for (Reservation res : reservationsMade) {
                        reservationService.cancel(res.playerId(), "party match admission rollback");
                    }
                    break;
                }
            }

            Duration admissionTtl = configSnapshot().match().admissionTimeout();
            List<AdmissionTicket> tickets = new ArrayList<>(groupSize);
            Optional<PartyId> partyIdOpt = (party != null) ? Optional.of(party.partyId()) : Optional.empty();
            if (targetMatchId != null) {
                for (UUID memberId : groupMembers) {
                    tickets.add(ticketService.issue(memberId, targetMatchId, targetInstanceId, ParticipantRole.PLAYER, now, admissionTtl, partyIdOpt));
                }
            }

            for (UUID memberId : groupMembers) {
                inFlightTransfers.add(memberId);
            }
            routingAttemptsCount.addAndGet(groupSize);

            QueueResult assigned = queues.assign(playerId, gameId, targetInstanceId);
            if (assigned.code() != QueueResult.Code.ASSIGNED) {
                for (Reservation res : reservationsMade) {
                    reservationService.cancel(res.playerId(), "queue assign failed");
                }
                if (targetMatchId != null) {
                    for (int i = 0; i < groupSize; i++) {
                        matchRegistry.releasePendingAdmission(targetMatchId);
                    }
                }
                for (AdmissionTicket ticket : tickets) {
                    ticketService.invalidate(ticket.ticketId());
                }
                for (UUID memberId : groupMembers) {
                    inFlightTransfers.remove(memberId);
                }
                break;
            }

            if (party != null) {
                partyService.transitionState(party.partyId(), PartyState.ASSIGNED);
            }

            final PartySnapshot finalParty = party;
            final MatchId finalTargetMatchId = targetMatchId;

            for (UUID memberId : groupMembers) {
                Player member = proxy.getPlayer(memberId).orElse(null);
                if (member != null) {
                    if (finalParty != null) {
                        member.sendPlainMessage("Partida encontrada no servidor " + targetInstanceId + "! Conectando com sua party...");
                    } else {
                        member.sendPlainMessage("Partida encontrada no servidor " + targetInstanceId + "! Conectando...");
                    }
                }
                transfersInitiatedCount.incrementAndGet();
                transfers.transfer(memberId, targetInstanceId).thenAccept(result -> {
                    if (!result.success()) {
                        transfersFailedCount.incrementAndGet();
                        routingFailuresCount.incrementAndGet();
                        reservationService.cancel(memberId, "transfer connection failed");
                        if (finalTargetMatchId != null) matchRegistry.releasePendingAdmission(finalTargetMatchId);
                        inFlightTransfers.remove(memberId);
                        Player p = proxy.getPlayer(memberId).orElse(null);
                        if (p != null && p.isActive()) {
                            p.sendPlainMessage("Falha ao conectar à partida.");
                        }
                    }
                });
            }
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
                    matchRegistry.reconcileInstanceCrashOrShutdown(t.instanceId(), null, now);
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

            ticketService.sweepExpired(now);
            matchRegistry.sweepTombstones(now);
            if (partyService != null) partyService.sweep();
        } catch (Exception e) {
            logger.error("Error in liveness, reservation, and match sweep", e);
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

    record QueueValidation(boolean allowed, String message, PartySnapshot party) { }

    private QueueValidation validateQueueJoin(UUID playerId, GameId gameId) {
        if (!games().find(gameId).map(g -> g.enabled() && g.queueEnabled()).orElse(false)) {
            return new QueueValidation(false, "Este minigame está temporariamente indisponível.", null);
        }
        if (partyService != null) {
            Optional<PartySnapshot> partyOpt = partyService.partyOf(playerId);
            if (partyOpt.isPresent()) {
                PartySnapshot party = partyOpt.get();
                if (!party.isLeader(playerId)) {
                    return new QueueValidation(false, "Apenas o líder pode colocar o grupo na fila.", null);
                }
                if (party.state() != PartyState.IDLE) {
                    return new QueueValidation(false, "A party já está em uma fila ou partida.", null);
                }
                for (UUID memberId : party.memberIds()) {
                    Player m = proxy.getPlayer(memberId).orElse(null);
                    if (m == null || !m.isActive()) {
                        return new QueueValidation(false, "Não é possível entrar na fila: todos os membros da party devem estar online.", null);
                    }
                    if (inFlightTransfers.contains(memberId) || matchRegistry.findActiveForPlayer(memberId).isPresent()) {
                        return new QueueValidation(false, "Não é possível entrar na fila: há membros da party em partida ou transição.", null);
                    }
                }
                return new QueueValidation(true, "OK", party);
            }
        }
        return new QueueValidation(true, "OK", null);
    }

    void join(Player player, GameId game) {
        QueueValidation validation = validateQueueJoin(player.getUniqueId(), game);
        if (!validation.allowed()) {
            player.sendPlainMessage(validation.message());
            return;
        }

        queues.join(player.getUniqueId(), game).thenAccept(result -> {
            player.sendPlainMessage(result.message());
            if (result.code() == QueueResult.Code.JOINED || result.code() == QueueResult.Code.ALREADY_QUEUED) {
                if (validation.party() != null) {
                    partyService.transitionState(validation.party().partyId(), PartyState.QUEUED);
                    for (UUID memberId : validation.party().memberIds()) {
                        if (!memberId.equals(player.getUniqueId())) {
                            proxy.getPlayer(memberId).ifPresent(p ->
                                    p.sendPlainMessage("Sua party entrou na fila para " + game.value() + "."));
                        }
                    }
                }
                dispatchQueue(game);
            }
        });
    }

    void leave(Player player) {
        Optional<PartySnapshot> partyOpt = (partyService != null) ? partyService.partyOf(player.getUniqueId()) : Optional.empty();
        if (partyOpt.isPresent()) {
            PartySnapshot party = partyOpt.get();
            if (party.state() == PartyState.QUEUED) {
                if (!party.isLeader(player.getUniqueId())) {
                    player.sendPlainMessage("Apenas o líder pode retirar a party da fila.");
                    return;
                }
                partyService.transitionState(party.partyId(), PartyState.IDLE);
                for (UUID memberId : party.memberIds()) {
                    if (!memberId.equals(player.getUniqueId())) {
                        proxy.getPlayer(memberId).ifPresent(p ->
                                p.sendPlainMessage("Sua party saiu da fila."));
                    }
                }
            }
        }
        queues.leave(player.getUniqueId()).thenAccept(result -> player.sendPlainMessage(result.message()));
    }

    CompletionStage<QueueStatus> queueStatus(UUID playerId) {
        Optional<PartySnapshot> partyOpt = (partyService != null) ? partyService.partyOf(playerId) : Optional.empty();
        if (partyOpt.isPresent() && partyOpt.get().state() == PartyState.QUEUED) {
            return queues.status(partyOpt.get().leader());
        }
        return queues.status(playerId);
    }

    private void handle(ServerConnection connection, Player player, ProtocolEnvelope envelope) {
        switch (envelope.messageType()) {
            case QUEUE_JOIN -> handleQueueJoin(connection, player, envelope);
            case QUEUE_LEAVE -> handleQueueLeave(connection, player, envelope);
            case QUEUE_STATUS -> handleQueueStatus(connection, player, envelope);
            case SERVER_CONNECT -> handleServerConnect(connection, player, envelope);
            case PARTY_CREATE -> handlePartyCreate(connection, player, envelope);
            case PARTY_INVITE -> handlePartyInvite(connection, player, envelope);
            case PARTY_ACCEPT -> handlePartyAccept(connection, player, envelope);
            case PARTY_DECLINE -> handlePartyDecline(connection, player, envelope);
            case PARTY_LEAVE -> handlePartyLeave(connection, player, envelope);
            case PARTY_KICK -> handlePartyKick(connection, player, envelope);
            case PARTY_LEADER_CHANGE -> handlePartyLeaderChange(connection, player, envelope);
            case PARTY_DISBAND -> handlePartyDisband(connection, player, envelope);
            case PARTY_WARP -> handlePartyWarp(connection, player, envelope);
            default -> logger.warn("Rejected unexpected request type {}", envelope.messageType());
        }
    }

    private void handlePartyCreate(ServerConnection connection, Player player, ProtocolEnvelope envelope) {
        try {
            MessagePayloads.PartyCreate req = MessagePayloads.partyCreate(envelope.payload());
            if (!player.getUniqueId().equals(req.leaderId())) {
                rejectIdentity(connection, envelope, player);
                return;
            }
            PartySnapshot party = partyService.createParty(player.getUniqueId());
            sendPartyResponse(connection, envelope, player.getUniqueId(), true, "Party criada.", Optional.of(party.partyId()));
        } catch (PartyException e) {
            sendPartyResponse(connection, envelope, player.getUniqueId(), false, e.getMessage(), Optional.empty());
        } catch (ProtocolValidationException e) {
            logger.warn("Invalid party create payload: {}", e.getMessage());
        }
    }

    private void handlePartyInvite(ServerConnection connection, Player player, ProtocolEnvelope envelope) {
        try {
            MessagePayloads.PartyInvitePayload req = MessagePayloads.partyInvite(envelope.payload());
            if (!player.getUniqueId().equals(req.actorId())) {
                rejectIdentity(connection, envelope, player);
                return;
            }
            PartyInvite invite = partyService.invitePlayer(player.getUniqueId(), req.targetId());
            sendPartyResponse(connection, envelope, player.getUniqueId(), true, "Convite enviado.", Optional.of(invite.partyId()));
            proxy.getPlayer(req.targetId()).ifPresent(target -> {
                Component msg = Component.text()
                        .append(Component.text("§b§m----------------------------------------\n"))
                        .append(Component.text(player.getUsername(), NamedTextColor.YELLOW, TextDecoration.BOLD))
                        .append(Component.text(" convidou você para uma Party!\n", NamedTextColor.GRAY))
                        .append(Component.text(" [ACEITAR] ", NamedTextColor.GREEN, TextDecoration.BOLD)
                                .clickEvent(ClickEvent.runCommand("/party accept " + player.getUsername()))
                                .hoverEvent(HoverEvent.showText(Component.text("Clique para aceitar o convite"))))
                        .append(Component.text(" [RECUSAR] ", NamedTextColor.RED, TextDecoration.BOLD)
                                .clickEvent(ClickEvent.runCommand("/party decline " + player.getUsername()))
                                .hoverEvent(HoverEvent.showText(Component.text("Clique para recusar o convite"))))
                        .append(Component.text("\n§b§m----------------------------------------"))
                        .build();
                target.sendMessage(msg);
            });
        } catch (PartyException e) {
            sendPartyResponse(connection, envelope, player.getUniqueId(), false, e.getMessage(), Optional.empty());
        } catch (ProtocolValidationException e) {
            logger.warn("Invalid party invite payload: {}", e.getMessage());
        }
    }

    private void handlePartyAccept(ServerConnection connection, Player player, ProtocolEnvelope envelope) {
        try {
            MessagePayloads.PartyAcceptPayload req = MessagePayloads.partyAccept(envelope.payload());
            if (!player.getUniqueId().equals(req.playerId())) {
                rejectIdentity(connection, envelope, player);
                return;
            }
            PartyId partyId = req.partyId().orElse(null);
            if (partyId == null) {
                for (PartySnapshot p : partyService.activeParties()) {
                    if (p.invitedPlayers().containsKey(player.getUniqueId())) {
                        partyId = p.partyId();
                        break;
                    }
                }
            }
            if (partyId == null) {
                sendPartyResponse(connection, envelope, player.getUniqueId(), false, "Nenhum convite pendente.", Optional.empty());
                return;
            }
            PartySnapshot party = partyService.acceptInvite(player.getUniqueId(), partyId);
            sendPartyResponse(connection, envelope, player.getUniqueId(), true, "Entrou na party.", Optional.of(party.partyId()));
        } catch (PartyException e) {
            sendPartyResponse(connection, envelope, player.getUniqueId(), false, e.getMessage(), Optional.empty());
        } catch (ProtocolValidationException e) {
            logger.warn("Invalid party accept payload: {}", e.getMessage());
        }
    }

    private void handlePartyDecline(ServerConnection connection, Player player, ProtocolEnvelope envelope) {
        try {
            MessagePayloads.PartyDeclinePayload req = MessagePayloads.partyDecline(envelope.payload());
            if (!player.getUniqueId().equals(req.playerId())) {
                rejectIdentity(connection, envelope, player);
                return;
            }
            req.partyId().ifPresent(id -> partyService.declineInvite(player.getUniqueId(), id));
            sendPartyResponse(connection, envelope, player.getUniqueId(), true, "Convite recusado.", req.partyId());
        } catch (ProtocolValidationException e) {
            logger.warn("Invalid party decline payload: {}", e.getMessage());
        }
    }

    private void handlePartyLeave(ServerConnection connection, Player player, ProtocolEnvelope envelope) {
        try {
            MessagePayloads.PartyLeavePayload req = MessagePayloads.partyLeave(envelope.payload());
            if (!player.getUniqueId().equals(req.playerId())) {
                rejectIdentity(connection, envelope, player);
                return;
            }
            PartySnapshot party = partyService.leaveParty(player.getUniqueId());
            sendPartyResponse(connection, envelope, player.getUniqueId(), true, "Saiu da party.", Optional.of(party.partyId()));
        } catch (PartyException e) {
            sendPartyResponse(connection, envelope, player.getUniqueId(), false, e.getMessage(), Optional.empty());
        } catch (ProtocolValidationException e) {
            logger.warn("Invalid party leave payload: {}", e.getMessage());
        }
    }

    private void handlePartyKick(ServerConnection connection, Player player, ProtocolEnvelope envelope) {
        try {
            MessagePayloads.PartyKickPayload req = MessagePayloads.partyKick(envelope.payload());
            if (!player.getUniqueId().equals(req.actorId())) {
                rejectIdentity(connection, envelope, player);
                return;
            }
            PartySnapshot party = partyService.kickPlayer(player.getUniqueId(), req.targetId());
            sendPartyResponse(connection, envelope, player.getUniqueId(), true, "Membro expulso.", Optional.of(party.partyId()));
        } catch (PartyException e) {
            sendPartyResponse(connection, envelope, player.getUniqueId(), false, e.getMessage(), Optional.empty());
        } catch (ProtocolValidationException e) {
            logger.warn("Invalid party kick payload: {}", e.getMessage());
        }
    }

    private void handlePartyLeaderChange(ServerConnection connection, Player player, ProtocolEnvelope envelope) {
        try {
            MessagePayloads.PartyLeaderChangePayload req = MessagePayloads.partyLeaderChange(envelope.payload());
            if (!player.getUniqueId().equals(req.actorId())) {
                rejectIdentity(connection, envelope, player);
                return;
            }
            PartySnapshot party = partyService.transferLeadership(player.getUniqueId(), req.newLeaderId());
            sendPartyResponse(connection, envelope, player.getUniqueId(), true, "Liderança transferida.", Optional.of(party.partyId()));
        } catch (PartyException e) {
            sendPartyResponse(connection, envelope, player.getUniqueId(), false, e.getMessage(), Optional.empty());
        } catch (ProtocolValidationException e) {
            logger.warn("Invalid party leader change payload: {}", e.getMessage());
        }
    }

    private void handlePartyDisband(ServerConnection connection, Player player, ProtocolEnvelope envelope) {
        try {
            MessagePayloads.PartyDisbandPayload req = MessagePayloads.partyDisband(envelope.payload());
            if (!player.getUniqueId().equals(req.actorId())) {
                rejectIdentity(connection, envelope, player);
                return;
            }
            PartySnapshot party = partyService.disbandParty(player.getUniqueId(), req.partyId());
            sendPartyResponse(connection, envelope, player.getUniqueId(), true, "Party desfeita.", Optional.of(party.partyId()));
        } catch (PartyException e) {
            sendPartyResponse(connection, envelope, player.getUniqueId(), false, e.getMessage(), Optional.empty());
        } catch (ProtocolValidationException e) {
            logger.warn("Invalid party disband payload: {}", e.getMessage());
        }
    }

    private void handlePartyWarp(ServerConnection connection, Player player, ProtocolEnvelope envelope) {
        try {
            MessagePayloads.PartyWarpPayload req = MessagePayloads.partyWarp(envelope.payload());
            if (!player.getUniqueId().equals(req.leaderId())) {
                rejectIdentity(connection, envelope, player);
                return;
            }
            PartySnapshot party = partyService.partyOf(player.getUniqueId()).orElse(null);
            if (party == null || !party.isLeader(player.getUniqueId())) {
                sendPartyResponse(connection, envelope, player.getUniqueId(), false, "Apenas o líder pode puxar a party.", Optional.empty());
                return;
            }
            if (party.state() != PartyState.IDLE) {
                sendPartyResponse(connection, envelope, player.getUniqueId(), false,
                        "Não é possível puxar a party no estado atual (" + party.state() + ").", Optional.of(party.partyId()));
                return;
            }
            Optional<ServerConnection> current = player.getCurrentServer();
            if (current.isEmpty()) {
                sendPartyResponse(connection, envelope, player.getUniqueId(), false, "Servidor atual indisponível.", Optional.of(party.partyId()));
                return;
            }
            RegisteredServer targetServer = current.get().getServer();
            int warped = 0;
            for (UUID memberId : party.memberIds()) {
                if (memberId.equals(player.getUniqueId())) continue;
                Player member = proxy.getPlayer(memberId).orElse(null);
                if (member != null && member.isActive()) {
                    boolean alreadyThere = member.getCurrentServer()
                            .map(s -> s.getServerInfo().equals(targetServer.getServerInfo()))
                            .orElse(false);
                    if (!alreadyThere) {
                        member.createConnectionRequest(targetServer).connect();
                        member.sendPlainMessage("§aO líder puxou a party para o servidor dele.");
                        warped++;
                    }
                }
            }
            sendPartyResponse(connection, envelope, player.getUniqueId(), true,
                    "Puxando " + warped + " membro(s) da party para seu servidor.", Optional.of(party.partyId()));
        } catch (ProtocolValidationException e) {
            logger.warn("Invalid party warp payload: {}", e.getMessage());
        }
    }

    private void sendPartyResponse(ServerConnection connection, ProtocolEnvelope request,
                                   UUID playerId, boolean success, String message, Optional<PartyId> partyId) {
        send(connection, new ProtocolEnvelope(1, MessageType.PARTY_RESPONSE, request.correlationId(),
                MessagePayloads.partyResponse(new MessagePayloads.PartyResponsePayload(playerId, success, message, partyId))));
    }

    private void handleQueueJoin(ServerConnection connection, Player player, ProtocolEnvelope envelope) {
        try {
            MessagePayloads.QueueJoin request = MessagePayloads.queueJoin(envelope.payload());
            if (!player.getUniqueId().equals(request.playerId())) { rejectIdentity(connection, envelope, player); return; }
            QueueValidation validation = validateQueueJoin(player.getUniqueId(), request.gameId());
            if (!validation.allowed()) {
                sendQueueResponse(connection, envelope, QueueResult.of(
                        QueueResult.Code.ERROR, request.gameId(), 0, 0, validation.message()), player.getUniqueId());
                return;
            }
            queues.join(player.getUniqueId(), request.gameId()).thenAccept(result -> {
                sendQueueResponse(connection, envelope, result, player.getUniqueId());
                if (result.code() == QueueResult.Code.JOINED || result.code() == QueueResult.Code.ALREADY_QUEUED) {
                    if (validation.party() != null) {
                        partyService.transitionState(validation.party().partyId(), PartyState.QUEUED);
                        for (UUID memberId : validation.party().memberIds()) {
                            if (!memberId.equals(player.getUniqueId())) {
                                proxy.getPlayer(memberId).ifPresent(p ->
                                        p.sendPlainMessage("Sua party entrou na fila para " + request.gameId().value() + "."));
                            }
                        }
                    }
                    dispatchQueue(request.gameId());
                }
            });
        } catch (ProtocolValidationException exception) { logger.warn("Invalid queue join payload: {}", exception.getMessage()); }
    }

    private void handleQueueLeave(ServerConnection connection, Player player, ProtocolEnvelope envelope) {
        try {
            MessagePayloads.PlayerRequest request = MessagePayloads.playerRequest(envelope.payload());
            if (!player.getUniqueId().equals(request.playerId())) { rejectIdentity(connection, envelope, player); return; }
            Optional<PartySnapshot> partyOpt = (partyService != null) ? partyService.partyOf(player.getUniqueId()) : Optional.empty();
            if (partyOpt.isPresent()) {
                PartySnapshot party = partyOpt.get();
                if (party.state() == PartyState.QUEUED) {
                    if (!party.isLeader(player.getUniqueId())) {
                        sendQueueResponse(connection, envelope, QueueResult.of(QueueResult.Code.ERROR, null, 0, 0,
                                "Apenas o líder pode retirar a party da fila."), player.getUniqueId());
                        return;
                    }
                    partyService.transitionState(party.partyId(), PartyState.IDLE);
                    for (UUID memberId : party.memberIds()) {
                        if (!memberId.equals(player.getUniqueId())) {
                            proxy.getPlayer(memberId).ifPresent(p ->
                                    p.sendPlainMessage("Sua party saiu da fila."));
                        }
                    }
                }
            }
            queues.leave(player.getUniqueId()).thenAccept(result -> sendQueueResponse(connection, envelope, result, player.getUniqueId()));
        } catch (ProtocolValidationException exception) { logger.warn("Invalid queue leave payload: {}", exception.getMessage()); }
    }

    private void handleQueueStatus(ServerConnection connection, Player player, ProtocolEnvelope envelope) {
        try {
            MessagePayloads.PlayerRequest request = MessagePayloads.playerRequest(envelope.payload());
            if (!player.getUniqueId().equals(request.playerId())) { rejectIdentity(connection, envelope, player); return; }
            queueStatus(player.getUniqueId()).thenAccept(status -> sendQueueResponse(connection, envelope,
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

    void install(HubConfigSnapshot snapshot) throws ConfigException, IOException {
        if (codec == null) {
            codec = createCodec(snapshot);
        }
        if (channel == null) {
            channel = MinecraftChannelIdentifier.from(snapshot.proxy().channel());
        }
        GameRegistry nextGames = new InMemoryGameRegistry(snapshot.games());
        ServerRegistry nextServers = new InMemoryServerRegistry(snapshot.servers());
        ensureConfiguredServers(snapshot, true);
        queues = new InMemoryQueueService(new QueueEventBus());
        instanceEventBus = new InstanceEventBus();
        instanceRegistry = new InMemoryInstanceRegistry(instanceEventBus);
        reservationService = new InMemoryReservationService(instanceRegistry, snapshot.registry().reservationTtl());
        instanceRouting = new InstanceAwareRoutingService(nextGames, instanceRegistry, nextServers);
        transfers = new VelocityTransferService(proxy, instanceRegistry, nextServers);

        matchEventBus = new MatchEventBus();
        matchRegistry = new InMemoryMatchRegistry(matchEventBus, snapshot.match().finishedRetention());
        ticketService = new AdmissionTicketService(snapshot.match().admissionTimeout());
        matchManager = new VelocityMatchManager(this, matchRegistry);

        partyEventBus = new PartyEventBus();
        partyService = new InMemoryPartyService(snapshot.party(), partyEventBus);

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

        matchEventBus.add(event -> {
            if (event instanceof MatchCreatedEvent created) {
                matchesCreatedCount.incrementAndGet();
                dispatchQueue(created.match().gameId());
            } else if (event instanceof MatchStateChangedEvent changed) {
                if (changed.newState() == MatchState.IN_GAME) {
                    matchesStartedCount.incrementAndGet();
                } else if (changed.newState() == MatchState.FINISHED) {
                    matchesFinishedCount.incrementAndGet();
                } else if (changed.newState() == MatchState.ABORTED) {
                    matchesAbortedCount.incrementAndGet();
                } else if (changed.newState() == MatchState.WAITING) {
                    matchRegistry.find(changed.matchId()).ifPresent(m -> dispatchQueue(m.gameId()));
                }
            } else if (event instanceof MatchParticipantLeftEvent left) {
                matchRegistry.find(left.matchId()).ifPresent(m -> {
                    if (m.canAcceptParticipants()) dispatchQueue(m.gameId());
                });
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

    private static MatchState mapWireState(MessagePayloads.MatchStateWire wire) {
        return switch (wire) {
            case CREATED -> MatchState.CREATED;
            case WAITING -> MatchState.WAITING;
            case COUNTDOWN -> MatchState.COUNTDOWN;
            case LOCKED -> MatchState.LOCKED;
            case IN_GAME -> MatchState.IN_GAME;
            case ENDING -> MatchState.ENDING;
            case FINISHED -> MatchState.FINISHED;
            case ABORTED -> MatchState.ABORTED;
        };
    }

    InMemoryServerRegistry serversMutable() { return (InMemoryServerRegistry) servers.get(); }
    public HubConfigSnapshot configSnapshot() { return Objects.requireNonNull(config.get(), "plugin is not enabled"); }
    public ProxyServer proxy() { return proxy; }
    public Logger getLogger() { return logger; }
    public InMemoryInstanceRegistry instanceRegistry() { return instanceRegistry; }
    public InMemoryReservationService reservationService() { return reservationService; }
    public InMemoryQueueService queueService() { return queues; }
    public InMemoryMatchRegistry matchRegistry() { return matchRegistry; }
    public AdmissionTicketService ticketService() { return ticketService; }
    public ProtocolCodec codec() { return codec; }

    // Telemetry metric accessors
    public long registrationsCount() { return registrationsCount.get(); }
    public long heartbeatsReceivedCount() { return heartbeatsReceivedCount.get(); }
    public long heartbeatsRejectedCount() { return heartbeatsRejectedCount.get(); }
    public long matchesCreatedCount() { return matchesCreatedCount.get(); }
    public long matchesStartedCount() { return matchesStartedCount.get(); }
    public long matchesFinishedCount() { return matchesFinishedCount.get(); }
    public long matchesAbortedCount() { return matchesAbortedCount.get(); }
    public long admissionsAcceptedCount() { return admissionsAcceptedCount.get(); }
    public long admissionsRejectedCount() { return admissionsRejectedCount.get(); }
    public long routingAttemptsCount() { return routingAttemptsCount.get(); }
    public long routingFailuresCount() { return routingFailuresCount.get(); }
    public long transfersInitiatedCount() { return transfersInitiatedCount.get(); }
    public long transfersSucceededCount() { return transfersSucceededCount.get(); }
    public long transfersFailedCount() { return transfersFailedCount.get(); }
    public long returnFailuresCount() { return returnFailuresCount.get(); }
    public long reservationExpirationsCount() { return reservationExpirationsCount.get(); }

    @Override public ServerRole role() { return ServerRole.GENERIC; }
    @Override public GameRegistry games() { return games.get(); }
    @Override public ServerRegistry servers() { return servers.get(); }
    @Override public InstanceRegistry instances() { return instanceRegistry; }
    @Override public Optional<InstanceService> instance() { return Optional.empty(); }
    @Override public MatchManager matches() { return matchManager; }
    @Override public QueueService queues() { return queues; }
    @Override public PartyService parties() { return partyService; }
    public InMemoryPartyService partyService() { return partyService; }
    public ProxyServer proxyServer() { return proxy; }
    @Override public RoutingService routing() { return routing.get(); }
    @Override public PlayerTransferService transfers() { return transfers; }
    @Override public void addQueueListener(Consumer<QueueEvent> listener) { queues.addListener(listener); }
    @Override public void removeQueueListener(Consumer<QueueEvent> listener) { queues.removeListener(listener); }
    @Override public void addInstanceListener(Consumer<InstanceEvent> listener) { instanceEventBus.add(listener); }
    @Override public void removeInstanceListener(Consumer<InstanceEvent> listener) { instanceEventBus.remove(listener); }
    @Override public void addMatchListener(Consumer<MatchEvent> listener) { matchEventBus.add(listener); }
    @Override public void removeMatchListener(Consumer<MatchEvent> listener) { matchEventBus.remove(listener); }
    @Override public void addPartyListener(Consumer<PartyEvent> listener) { if (partyEventBus != null) partyEventBus.add(listener); }
    @Override public void removePartyListener(Consumer<PartyEvent> listener) { if (partyEventBus != null) partyEventBus.remove(listener); }
}
