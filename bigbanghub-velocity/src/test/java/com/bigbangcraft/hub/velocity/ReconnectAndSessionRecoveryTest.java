package com.bigbangcraft.hub.velocity;

import com.bigbangcraft.hub.api.AdmissionTicket;
import com.bigbangcraft.hub.api.GameId;
import com.bigbangcraft.hub.api.MatchDefinition;
import com.bigbangcraft.hub.api.MatchId;
import com.bigbangcraft.hub.api.MatchParticipant;
import com.bigbangcraft.hub.api.MatchResult;
import com.bigbangcraft.hub.api.MatchSnapshot;
import com.bigbangcraft.hub.api.MatchState;
import com.bigbangcraft.hub.api.ParticipantRole;
import com.bigbangcraft.hub.api.ParticipantState;
import com.bigbangcraft.hub.api.PlayerReconnectedEvent;
import com.bigbangcraft.hub.api.ServerId;
import com.bigbangcraft.hub.common.ConfigLoader;
import com.bigbangcraft.hub.common.HubConfigSnapshot;
import com.bigbangcraft.hub.common.InMemoryMatchRegistry;
import com.bigbangcraft.hub.common.MessagePayloads;
import com.bigbangcraft.hub.common.MessageType;
import com.bigbangcraft.hub.common.ProtocolEnvelope;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.scheduler.ScheduledTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconnectAndSessionRecoveryTest {
    private BigBangHubVelocityPlugin plugin;
    private MockProxyServer mockProxy;
    private GameId gameId;
    private ServerId instanceId;
    private UUID sessionId;

    static class MockPlayer {
        final UUID uuid = UUID.randomUUID();
        final String name;
        final List<String> messages = new ArrayList<>();
        final List<String> transferredServers = new ArrayList<>();
        RegisteredServer currentServer;
        boolean active = true;
        Player proxy;

        MockPlayer(String name) {
            this.name = name;
            com.velocitypowered.api.proxy.ConnectionRequestBuilder.Result mockResult =
                    (com.velocitypowered.api.proxy.ConnectionRequestBuilder.Result) Proxy.newProxyInstance(
                            getClass().getClassLoader(),
                            new Class<?>[]{com.velocitypowered.api.proxy.ConnectionRequestBuilder.Result.class},
                            (r, rmethod, rargs) -> {
                                if (rmethod.getName().equals("isSuccessful")) return true;
                                if (rmethod.getName().equals("getStatus")) return com.velocitypowered.api.proxy.ConnectionRequestBuilder.Status.SUCCESS;
                                return null;
                            });

            com.velocitypowered.api.proxy.ConnectionRequestBuilder mockReq =
                    (com.velocitypowered.api.proxy.ConnectionRequestBuilder) Proxy.newProxyInstance(
                            getClass().getClassLoader(),
                            new Class<?>[]{com.velocitypowered.api.proxy.ConnectionRequestBuilder.class},
                            (b, bmethod, bargs) -> {
                                if (bmethod.getName().equals("connect")) {
                                    if (bargs != null && bargs.length > 0 && bargs[0] instanceof RegisteredServer rs) {
                                        transferredServers.add(rs.getServerInfo().getName());
                                        currentServer = rs;
                                    }
                                    return CompletableFuture.completedFuture(mockResult);
                                }
                                return null;
                            });

            ServerConnection mockConn = (ServerConnection) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{ServerConnection.class},
                    (c, cmethod, cargs) -> {
                        if (cmethod.getName().equals("getServer")) return currentServer;
                        if (cmethod.getName().equals("getServerInfo")) return currentServer != null ? currentServer.getServerInfo() : null;
                        return null;
                    });

            this.proxy = (Player) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{Player.class},
                    (p, method, args) -> {
                        if (method.getName().equals("getUniqueId")) return uuid;
                        if (method.getName().equals("getUsername")) return name;
                        if (method.getName().equals("isActive")) return active;
                        if (method.getName().equals("sendPlainMessage")) {
                            messages.add((String) args[0]);
                            return null;
                        }
                        if (method.getName().equals("sendMessage")) {
                            return null;
                        }
                        if (method.getName().equals("hasPermission")) return true;
                        if (method.getName().equals("getCurrentServer")) return Optional.ofNullable(currentServer != null ? mockConn : null);
                        if (method.getName().equals("createConnectionRequest")) {
                            if (args.length > 0 && args[0] instanceof RegisteredServer rs) {
                                transferredServers.add(rs.getServerInfo().getName());
                            }
                            return mockReq;
                        }
                        return null;
                    });
        }
    }

    static class MockProxyServer {
        final Map<String, MockPlayer> playersByName = new HashMap<>();
        final Map<UUID, MockPlayer> playersById = new HashMap<>();
        final Map<String, RegisteredServer> serversByName = new HashMap<>();
        final ProxyServer proxy;

        MockProxyServer() {
            ScheduledTask dummyTask = (ScheduledTask) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{ScheduledTask.class},
                    (p, method, args) -> null);

            com.velocitypowered.api.scheduler.Scheduler.TaskBuilder dummyTaskBuilder =
                    (com.velocitypowered.api.scheduler.Scheduler.TaskBuilder) Proxy.newProxyInstance(
                            getClass().getClassLoader(),
                            new Class<?>[]{com.velocitypowered.api.scheduler.Scheduler.TaskBuilder.class},
                            (p, method, args) -> {
                                if (method.getName().equals("schedule")) return dummyTask;
                                return p;
                            });

            com.velocitypowered.api.scheduler.Scheduler mockScheduler =
                    (com.velocitypowered.api.scheduler.Scheduler) Proxy.newProxyInstance(
                            getClass().getClassLoader(),
                            new Class<?>[]{com.velocitypowered.api.scheduler.Scheduler.class},
                            (p, method, args) -> {
                                if (method.getName().equals("buildTask")) return dummyTaskBuilder;
                                return null;
                            });

            this.proxy = (ProxyServer) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{ProxyServer.class},
                    (p, method, args) -> {
                        if (method.getName().equals("getPlayer") && args[0] instanceof String name) {
                            MockPlayer mp = playersByName.get(name.toLowerCase(Locale.ROOT));
                            return mp != null ? Optional.of(mp.proxy) : Optional.empty();
                        }
                        if (method.getName().equals("getPlayer") && args[0] instanceof UUID id) {
                            MockPlayer mp = playersById.get(id);
                            return mp != null ? Optional.of(mp.proxy) : Optional.empty();
                        }
                        if (method.getName().equals("getAllPlayers")) {
                            return playersById.values().stream().map(mp -> mp.proxy).toList();
                        }
                        if (method.getName().equals("getServer") && args[0] instanceof String name) {
                            RegisteredServer srv = serversByName.get(name);
                            if (srv != null) return Optional.of(srv);
                            if (name.startsWith("cm-") || name.startsWith("campominado-") || name.equals("hubminigame")) {
                                RegisteredServer dyn = (RegisteredServer) Proxy.newProxyInstance(
                                        getClass().getClassLoader(),
                                        new Class<?>[]{RegisteredServer.class},
                                        (s, smethod, sargs) -> {
                                            if (smethod.getName().equals("getServerInfo")) {
                                                return new ServerInfo(name, new InetSocketAddress("127.0.0.1", 25565));
                                            }
                                            return null;
                                        });
                                return Optional.of(dyn);
                            }
                            return Optional.empty();
                        }
                        if (method.getName().equals("registerServer") && args[0] instanceof ServerInfo info) {
                            RegisteredServer srv = (RegisteredServer) Proxy.newProxyInstance(
                                    getClass().getClassLoader(),
                                    new Class<?>[]{RegisteredServer.class},
                                    (s, smethod, sargs) -> {
                                        if (smethod.getName().equals("getServerInfo")) return info;
                                        return null;
                                    });
                            serversByName.put(info.getName(), srv);
                            return srv;
                        }
                        if (method.getName().equals("getScheduler")) {
                            return mockScheduler;
                        }
                        return null;
                    });
        }

        MockPlayer register(String name) {
            MockPlayer mp = new MockPlayer(name);
            playersByName.put(name.toLowerCase(Locale.ROOT), mp);
            playersById.put(mp.uuid, mp);
            return mp;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        Path directory = Files.createTempDirectory("bbh-reconnect-test");
        for (String file : List.of("config.yml", "menus.yml", "games.yml", "servers.yml", "messages.yml")) {
            try (var input = getClass().getResourceAsStream("/" + file)) {
                Files.copy(input, directory.resolve(file));
            }
        }

        mockProxy = new MockProxyServer();
        plugin = new BigBangHubVelocityPlugin(mockProxy.proxy, NOPLogger.NOP_LOGGER, directory);
        HubConfigSnapshot snapshot = ConfigLoader.load(directory);
        plugin.install(snapshot);

        gameId = GameId.of("campominado");
        instanceId = ServerId.of("campominado-01");
        sessionId = UUID.randomUUID();

        // Register backend instance
        plugin.instanceRegistry().register(new MessagePayloads.InstanceRegister(
                instanceId, gameId, "campominado-01", sessionId,
                MessagePayloads.GameStateWire.WAITING, 0, 2, 10, true), 0L, Instant.now());
    }

    @Test
    void testDisconnectDuringActiveMatchPreservesSlotInDisconnectedState() {
        MockPlayer player = mockProxy.register("Gamer1");

        MatchDefinition def = MatchDefinition.builder().gameId(gameId).minPlayers(2).maxPlayers(4).allowLateJoin(false).build();
        InMemoryMatchRegistry.MatchSessionState matchState =
                plugin.matchRegistry().createMatch(MatchId.random(), def, instanceId, sessionId, Instant.now());
        matchState.stateMachine().transition(MatchState.CREATED, MatchState.WAITING, Instant.now());

        AdmissionTicket ticket = plugin.ticketService().issue(
                player.uuid, matchState.matchId(), instanceId, ParticipantRole.PLAYER, Instant.now(), Duration.ofSeconds(10));
        MatchParticipant admitted = plugin.matchRegistry().admitPlayer(ticket, Instant.now());
        assertEquals(ParticipantState.ACTIVE, admitted.state());
        assertEquals(1, matchState.activePlayerCount());

        // Player disconnects from proxy
        DisconnectEvent disconnectEvent = new DisconnectEvent(player.proxy, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN);
        plugin.onDisconnect(disconnectEvent);

        // Verify slot is preserved as DISCONNECTED and active player count remains 1
        MatchParticipant participant = matchState.participant(player.uuid).orElseThrow();
        assertEquals(ParticipantState.DISCONNECTED, participant.state());
        assertEquals(1, matchState.activePlayerCount(), "Active player count must retain slot during reconnect window");

        // Verify pending reconnect can be discovered
        Optional<MatchSnapshot> pending = plugin.findPendingReconnect(player.uuid);
        assertTrue(pending.isPresent());
        assertEquals(matchState.matchId(), pending.get().matchId());
    }

    @Test
    void testReconnectWithinWindowIssuesReconnectTicket() {
        MockPlayer player = mockProxy.register("Gamer2");

        MatchDefinition def = MatchDefinition.builder().gameId(gameId).minPlayers(2).maxPlayers(4).allowLateJoin(false).build();
        InMemoryMatchRegistry.MatchSessionState matchState =
                plugin.matchRegistry().createMatch(MatchId.random(), def, instanceId, sessionId, Instant.now());
        matchState.stateMachine().transition(MatchState.CREATED, MatchState.WAITING, Instant.now());

        AdmissionTicket ticket = plugin.ticketService().issue(
                player.uuid, matchState.matchId(), instanceId, ParticipantRole.PLAYER, Instant.now(), Duration.ofSeconds(10));
        plugin.matchRegistry().admitPlayer(ticket, Instant.now());

        DisconnectEvent disconnectEvent = new DisconnectEvent(player.proxy, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN);
        plugin.onDisconnect(disconnectEvent);

        // Player reconnects
        boolean reconnected = plugin.reconnectPlayer(player.proxy);
        assertTrue(reconnected);
        assertTrue(player.transferredServers.contains(instanceId.value()));

        // Check the issued ticket
        Optional<AdmissionTicket> reconnectTicket = plugin.ticketService().findActive(player.uuid);
        assertTrue(reconnectTicket.isPresent());
        assertTrue(reconnectTicket.get().isReconnect(), "Issued ticket must have isReconnect set to true");
        assertEquals(matchState.matchId(), reconnectTicket.get().matchId());
    }

    @Test
    void testReconnectAdmissionInRunningMatchAcceptsAndRestoresActiveState() {
        MockPlayer player = mockProxy.register("Gamer3");

        MatchDefinition def = MatchDefinition.builder().gameId(gameId).minPlayers(2).maxPlayers(4).allowLateJoin(false).build();
        InMemoryMatchRegistry.MatchSessionState matchState =
                plugin.matchRegistry().createMatch(MatchId.random(), def, instanceId, sessionId, Instant.now());
        matchState.stateMachine().transition(MatchState.CREATED, MatchState.WAITING, Instant.now());

        AdmissionTicket ticket = plugin.ticketService().issue(
                player.uuid, matchState.matchId(), instanceId, ParticipantRole.PLAYER, Instant.now(), Duration.ofSeconds(10));
        plugin.matchRegistry().admitPlayer(ticket, Instant.now());

        // Match transitions to IN_GAME: WAITING -> COUNTDOWN -> LOCKED -> IN_GAME
        matchState.stateMachine().transition(MatchState.WAITING, MatchState.COUNTDOWN, Instant.now());
        matchState.stateMachine().transition(MatchState.COUNTDOWN, MatchState.LOCKED, Instant.now());
        matchState.stateMachine().transition(MatchState.LOCKED, MatchState.IN_GAME, Instant.now());
        assertEquals(MatchState.IN_GAME, matchState.stateMachine().state());

        // Player disconnects
        DisconnectEvent disconnectEvent = new DisconnectEvent(player.proxy, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN);
        plugin.onDisconnect(disconnectEvent);
        assertEquals(ParticipantState.DISCONNECTED, matchState.participant(player.uuid).orElseThrow().state());

        // Normal late join would fail on IN_GAME with allowLateJoin=false
        // But reconnect ticket should be admitted!
        AdmissionTicket reconnectTicket = plugin.ticketService().issue(
                player.uuid, matchState.matchId(), instanceId, ParticipantRole.PLAYER, Instant.now(), Duration.ofSeconds(10),
                Optional.empty(), true);
        assertTrue(reconnectTicket.isReconnect());

        AtomicBoolean eventFired = new AtomicBoolean(false);
        plugin.addMatchListener(evt -> {
            if (evt instanceof PlayerReconnectedEvent reconnectedEvent) {
                if (reconnectedEvent.playerId().equals(player.uuid) && reconnectedEvent.matchId().equals(matchState.matchId())) {
                    eventFired.set(true);
                }
            }
        });

        MatchParticipant readmitted = plugin.matchRegistry().admitPlayer(reconnectTicket, Instant.now());
        assertEquals(ParticipantState.ACTIVE, readmitted.state());
        assertTrue(eventFired.get(), "PlayerReconnectedEvent must be published upon reconnect admission");
    }

    @Test
    void testReconnectExpirationReleasesSlotAndEvictsPlayer() {
        MockPlayer player = mockProxy.register("Gamer4");

        MatchDefinition def = MatchDefinition.builder().gameId(gameId).minPlayers(2).maxPlayers(4).allowLateJoin(false).build();
        InMemoryMatchRegistry.MatchSessionState matchState =
                plugin.matchRegistry().createMatch(MatchId.random(), def, instanceId, sessionId, Instant.now());
        matchState.stateMachine().transition(MatchState.CREATED, MatchState.WAITING, Instant.now());

        AdmissionTicket ticket = plugin.ticketService().issue(
                player.uuid, matchState.matchId(), instanceId, ParticipantRole.PLAYER, Instant.now(), Duration.ofSeconds(10));
        plugin.matchRegistry().admitPlayer(ticket, Instant.now());

        DisconnectEvent disconnectEvent = new DisconnectEvent(player.proxy, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN);
        plugin.onDisconnect(disconnectEvent);

        // Before timeout, player is still disconnected & slot reserved
        assertEquals(1, matchState.activePlayerCount());

        // Advance time by 65 seconds (default timeout is 60s)
        Instant future = Instant.now().plusSeconds(65);
        plugin.matchRegistry().sweepTombstones(future);

        // Verify player has been swept and slot released
        assertEquals(0, matchState.activePlayerCount());
        assertTrue(matchState.participant(player.uuid).isEmpty() ||
                matchState.participant(player.uuid).get().state() == ParticipantState.LEFT);
        assertTrue(plugin.findPendingReconnect(player.uuid).isEmpty());
    }

    @Test
    void testMatchFinishCancelsPendingReconnectGracefully() {
        MockPlayer player = mockProxy.register("Gamer5");

        MatchDefinition def = MatchDefinition.builder().gameId(gameId).minPlayers(2).maxPlayers(4).allowLateJoin(false).build();
        InMemoryMatchRegistry.MatchSessionState matchState =
                plugin.matchRegistry().createMatch(MatchId.random(), def, instanceId, sessionId, Instant.now());
        matchState.stateMachine().transition(MatchState.CREATED, MatchState.WAITING, Instant.now());

        AdmissionTicket ticket = plugin.ticketService().issue(
                player.uuid, matchState.matchId(), instanceId, ParticipantRole.PLAYER, Instant.now(), Duration.ofSeconds(10));
        plugin.matchRegistry().admitPlayer(ticket, Instant.now());

        // Match transitions to IN_GAME
        matchState.stateMachine().transition(MatchState.WAITING, MatchState.COUNTDOWN, Instant.now());
        matchState.stateMachine().transition(MatchState.COUNTDOWN, MatchState.LOCKED, Instant.now());
        matchState.stateMachine().transition(MatchState.LOCKED, MatchState.IN_GAME, Instant.now());

        DisconnectEvent disconnectEvent = new DisconnectEvent(player.proxy, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN);
        plugin.onDisconnect(disconnectEvent);
        assertTrue(plugin.findPendingReconnect(player.uuid).isPresent());

        // Match finishes while player is disconnected
        boolean finished = plugin.matchRegistry().finishMatch(matchState.matchId(), sessionId, matchState.stateMachine().revision(),
                new MatchResult(MatchResult.Outcome.WIN, Set.of(), Duration.ofSeconds(100), Map.of()), Instant.now());
        assertTrue(finished);

        // Pending reconnect should no longer be valid
        assertTrue(plugin.findPendingReconnect(player.uuid).isEmpty());
        boolean reconnected = plugin.reconnectPlayer(player.proxy);
        assertFalse(reconnected);
        assertTrue(player.messages.stream().anyMatch(m -> m.contains("não possui nenhuma partida")));
    }

    @Test
    void testAutoReconnectOnHubJoin() {
        MockPlayer player = mockProxy.register("Gamer6");

        MatchDefinition def = MatchDefinition.builder().gameId(gameId).minPlayers(2).maxPlayers(4).allowLateJoin(false).build();
        InMemoryMatchRegistry.MatchSessionState matchState =
                plugin.matchRegistry().createMatch(MatchId.random(), def, instanceId, sessionId, Instant.now());
        matchState.stateMachine().transition(MatchState.CREATED, MatchState.WAITING, Instant.now());

        AdmissionTicket ticket = plugin.ticketService().issue(
                player.uuid, matchState.matchId(), instanceId, ParticipantRole.PLAYER, Instant.now(), Duration.ofSeconds(10));
        plugin.matchRegistry().admitPlayer(ticket, Instant.now());

        // Disconnect
        DisconnectEvent disconnectEvent = new DisconnectEvent(player.proxy, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN);
        plugin.onDisconnect(disconnectEvent);

        // Player connects to hubminigame
        RegisteredServer hubServer = (RegisteredServer) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{RegisteredServer.class},
                (s, smethod, sargs) -> {
                    if (smethod.getName().equals("getServerInfo")) {
                        return new ServerInfo("hubminigame", new InetSocketAddress("127.0.0.1", 25565));
                    }
                    return null;
                });
        player.currentServer = hubServer;

        ServerPostConnectEvent postConnect = new ServerPostConnectEvent(player.proxy, null);
        plugin.onServerPostConnect(postConnect);

        // Auto-reconnect triggered: player transferred to game instance
        assertTrue(player.transferredServers.contains(instanceId.value()));
        assertTrue(player.messages.stream().anyMatch(m -> m.contains("Partida em andamento encontrada!")));
    }
}
