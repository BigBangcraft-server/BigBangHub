package com.bigbangcraft.hub.velocity;

import com.bigbangcraft.hub.api.GameId;
import com.bigbangcraft.hub.api.MatchDefinition;
import com.bigbangcraft.hub.api.MatchId;
import com.bigbangcraft.hub.api.MatchParticipant;
import com.bigbangcraft.hub.api.MatchSnapshot;
import com.bigbangcraft.hub.api.MatchState;
import com.bigbangcraft.hub.api.ParticipantRole;
import com.bigbangcraft.hub.api.ParticipantState;
import com.bigbangcraft.hub.api.ServerId;
import com.bigbangcraft.hub.api.AdmissionTicket;
import com.bigbangcraft.hub.common.ConfigLoader;
import com.bigbangcraft.hub.common.HubConfigSnapshot;
import com.bigbangcraft.hub.common.InMemoryMatchRegistry;
import com.bigbangcraft.hub.common.MessagePayloads;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class StuckMatchFixRegressionTest {
    private BigBangHubVelocityPlugin plugin;
    private MockProxy mockProxy;
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
                                if (rmethod.getName().equals("getStatus"))
                                    return com.velocitypowered.api.proxy.ConnectionRequestBuilder.Status.SUCCESS;
                                return null;
                            });
            com.velocitypowered.api.proxy.ConnectionRequestBuilder mockReq =
                    (com.velocitypowered.api.proxy.ConnectionRequestBuilder) Proxy.newProxyInstance(
                            getClass().getClassLoader(),
                            new Class<?>[]{com.velocitypowered.api.proxy.ConnectionRequestBuilder.class},
                            (b, bmethod, bargs) -> {
                                if (bmethod.getName().equals("connect")) {
                                    return CompletableFuture.completedFuture(mockResult);
                                }
                                return null;
                            });
            ServerConnection mockConn = (ServerConnection) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{ServerConnection.class},
                    (c, cmethod, cargs) -> {
                        if (cmethod.getName().equals("getServer")) return currentServer;
                        if (cmethod.getName().equals("getServerInfo"))
                            return currentServer != null ? currentServer.getServerInfo() : null;
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
                        if (method.getName().equals("sendMessage")) return null;
                        if (method.getName().equals("hasPermission")) return true;
                        if (method.getName().equals("getCurrentServer"))
                            return Optional.ofNullable(currentServer != null ? mockConn : null);
                        if (method.getName().equals("createConnectionRequest")) {
                            if (args.length > 0 && args[0] instanceof RegisteredServer rs) {
                                transferredServers.add(rs.getServerInfo().getName());
                                currentServer = rs;
                            }
                            return mockReq;
                        }
                        return null;
                    });
        }
    }

    static class MockProxy {
        final Map<String, MockPlayer> byName = new HashMap<>();
        final Map<UUID, MockPlayer> byId = new HashMap<>();
        final Map<String, RegisteredServer> servers = new HashMap<>();
        final ProxyServer proxy;

        MockProxy() {
            ScheduledTask dummyTask = (ScheduledTask) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{ScheduledTask.class}, (p, m, a) -> null);
            com.velocitypowered.api.scheduler.Scheduler.TaskBuilder dummyBuilder =
                    (com.velocitypowered.api.scheduler.Scheduler.TaskBuilder) Proxy.newProxyInstance(
                            getClass().getClassLoader(),
                            new Class<?>[]{com.velocitypowered.api.scheduler.Scheduler.TaskBuilder.class},
                            (p, m, a) -> {
                                if (m.getName().equals("schedule")) return dummyTask;
                                return p;
                            });
            com.velocitypowered.api.scheduler.Scheduler mockScheduler =
                    (com.velocitypowered.api.scheduler.Scheduler) Proxy.newProxyInstance(
                            getClass().getClassLoader(),
                            new Class<?>[]{com.velocitypowered.api.scheduler.Scheduler.class},
                            (p, m, a) -> {
                                if (m.getName().equals("buildTask")) return dummyBuilder;
                                return null;
                            });
            this.proxy = (ProxyServer) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{ProxyServer.class},
                    (p, method, args) -> {
                        if (method.getName().equals("getPlayer") && args[0] instanceof String n) {
                            MockPlayer mp = byName.get(n.toLowerCase(Locale.ROOT));
                            return mp != null ? Optional.of(mp.proxy) : Optional.empty();
                        }
                        if (method.getName().equals("getPlayer") && args[0] instanceof UUID id) {
                            MockPlayer mp = byId.get(id);
                            return mp != null ? Optional.of(mp.proxy) : Optional.empty();
                        }
                        if (method.getName().equals("getAllPlayers"))
                            return byId.values().stream().map(mp -> mp.proxy).toList();
                        if (method.getName().equals("getServer") && args[0] instanceof String n) {
                            RegisteredServer srv = servers.get(n);
                            if (srv != null) return Optional.of(srv);
                            if (n.startsWith("cm-") || n.startsWith("campominado-") || n.equals("hubminigame")) {
                                RegisteredServer dyn = mockServer(n);
                                servers.put(n, dyn);
                                return Optional.of(dyn);
                            }
                            return Optional.empty();
                        }
                        if (method.getName().equals("registerServer") && args[0] instanceof ServerInfo info) {
                            RegisteredServer srv = mockServer(info.getName());
                            // Preserve requested address semantics via ServerInfo name only for test;
                            // use real info object for getServerInfo to satisfy ensureConfiguredServers
                            RegisteredServer withInfo = (RegisteredServer) Proxy.newProxyInstance(
                                    MockProxy.class.getClassLoader(),
                                    new Class<?>[]{RegisteredServer.class},
                                    (s, m, a) -> {
                                        if (m.getName().equals("getServerInfo")) return info;
                                        return null;
                                    });
                            servers.put(info.getName(), withInfo);
                            return withInfo;
                        }
                        if (method.getName().equals("getScheduler")) return mockScheduler;
                        return null;
                    });
        }

        static RegisteredServer mockServer(String name) {
            return (RegisteredServer) Proxy.newProxyInstance(
                    MockProxy.class.getClassLoader(), new Class<?>[]{RegisteredServer.class},
                    (s, m, a) -> {
                        if (m.getName().equals("getServerInfo"))
                            return new ServerInfo(name, new InetSocketAddress("127.0.0.1", 25565));
                        return null;
                    });
        }

        MockPlayer register(String name) {
            MockPlayer mp = new MockPlayer(name);
            byName.put(name.toLowerCase(Locale.ROOT), mp);
            byId.put(mp.uuid, mp);
            return mp;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        Path dir = Files.createTempDirectory("bbh-stuck-test");
        for (String file : List.of("config.yml", "menus.yml", "games.yml", "servers.yml", "messages.yml")) {
            try (var input = getClass().getResourceAsStream("/" + file)) {
                assertNotNull(input, "Missing bundled " + file);
                Files.copy(input, dir.resolve(file));
            }
        }
        mockProxy = new MockProxy();
        plugin = new BigBangHubVelocityPlugin(mockProxy.proxy, NOPLogger.NOP_LOGGER, dir);
        HubConfigSnapshot snapshot = ConfigLoader.load(dir);
        plugin.install(snapshot);
        gameId = GameId.of("campominado");
        instanceId = ServerId.of("campominado-01");
        sessionId = UUID.randomUUID();
        plugin.instanceRegistry().register(new MessagePayloads.InstanceRegister(
                instanceId, gameId, "campominado-01", sessionId,
                MessagePayloads.GameStateWire.WAITING, 0, 2, 10, true), 0L, Instant.now());
    }

    private InMemoryMatchRegistry.MatchSessionState newMatch() {
        MatchDefinition def = MatchDefinition.builder().gameId(gameId).minPlayers(2).maxPlayers(4).allowLateJoin(true).build();
        InMemoryMatchRegistry.MatchSessionState st =
                plugin.matchRegistry().createMatch(MatchId.random(), def, instanceId, sessionId, Instant.now());
        st.stateMachine().transition(MatchState.CREATED, MatchState.WAITING, Instant.now());
        return st;
    }

    private void admitActive(MockPlayer player, InMemoryMatchRegistry.MatchSessionState st) {
        AdmissionTicket ticket = plugin.ticketService().issue(
                player.uuid, st.matchId(), instanceId, ParticipantRole.PLAYER, Instant.now(), Duration.ofSeconds(10));
        plugin.matchRegistry().admitPlayer(ticket, Instant.now());
        assertEquals(ParticipantState.ACTIVE, st.participant(player.uuid).orElseThrow().state());
    }

    @Test
    void bundledConfigMustContainAllThreeAliases() throws Exception {
        Path dir = Files.createTempDirectory("alias-all-test");
        for (String file : List.of("config.yml", "menus.yml", "games.yml", "servers.yml", "messages.yml")) {
            try (var input = getClass().getResourceAsStream("/" + file)) {
                Files.copy(input, dir.resolve(file));
            }
        }
        var snapshot = ConfigLoader.load(dir);
        assertEquals("campominado", snapshot.aliases().get("campominado"));
        assertEquals("bedwars", snapshot.aliases().get("bedwars"));
        assertEquals("hg", snapshot.aliases().get("hg"));
    }

    @Test
    void reconcileMarksActiveStaleAsDisconnectedWhenBackendMessageLost() {
        MockPlayer player = mockProxy.register("Stuck1");
        var st = newMatch();
        admitActive(player, st);

        // Simulate /server hub: backend QUIT message lost, registry still ACTIVE.
        // Player now on hub, previous was match instance.
        RegisteredServer prev = MockProxy.mockServer(instanceId.value());
        RegisteredServer hub = MockProxy.mockServer("hubminigame");
        player.currentServer = hub;
        ServerConnection currentConn = (ServerConnection) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{ServerConnection.class},
                (c, m, a) -> {
                    if (m.getName().equals("getServer")) return hub;
                    if (m.getName().equals("getServerInfo")) return hub.getServerInfo();
                    return null;
                });

        assertTrue(plugin.findPendingReconnect(player.uuid).isEmpty(),
                "ACTIVE stale must not be reconnectable before reconcile");
        plugin.reconcileServerSwitch(player.proxy, prev, currentConn);

        assertEquals(ParticipantState.DISCONNECTED, st.participant(player.uuid).orElseThrow().state());
        assertTrue(plugin.findPendingReconnect(player.uuid).isPresent(),
                "After reconcile, /reconnect must work");
    }

    @Test
    void requeueAfterLobbyAbandonsStaleDisconnected() {
        MockPlayer player = mockProxy.register("Stuck2");
        var st = newMatch();
        admitActive(player, st);

        // Simulate successful DISCONNECTED (backend message arrived or reconciled).
        plugin.matchRegistry().setPlayerDisconnected(st.matchId(), player.uuid,
                Instant.now().plusSeconds(60), Instant.now());
        assertTrue(plugin.findPendingReconnect(player.uuid).isPresent());

        // Trying new queue must auto-abandon stale instead of contradiction.
        boolean abandoned = plugin.tryAbandonStaleMatchForRequeue(player.uuid);
        assertTrue(abandoned);
        assertTrue(plugin.matchRegistry().findActiveForPlayer(player.uuid).isEmpty());
        assertTrue(plugin.findPendingReconnect(player.uuid).isEmpty());
    }

    @Test
    void activeMatchStillBlocksWithoutAbandon() {
        MockPlayer player = mockProxy.register("Stuck3");
        var st = newMatch();
        admitActive(player, st);

        boolean abandoned = plugin.tryAbandonStaleMatchForRequeue(player.uuid);
        assertFalse(abandoned, "ACTIVE must not be auto-abandoned");
        assertTrue(plugin.matchRegistry().findActiveForPlayer(player.uuid).isPresent());

        plugin.join(player.proxy, gameId);
        // join is async (queues.join thenAccept); give it a tick via sleep? join validation
        // blocks synchronously before async, message sent synchronously on validation fail.
        assertTrue(player.messages.stream().anyMatch(m -> m.contains("já possui uma partida")),
                "ACTIVE must still block with já possui partida, must /leave first. Got: " + player.messages);
    }

    @Test
    void leaveMatchClearsActiveAndReturnsToHub() {
        MockPlayer player = mockProxy.register("Stuck4");
        var st = newMatch();
        admitActive(player, st);
        RegisteredServer minigame = MockProxy.mockServer(instanceId.value());
        player.currentServer = minigame;

        boolean left = plugin.leaveMatch(player.proxy);
        assertTrue(left);
        assertTrue(plugin.matchRegistry().findActiveForPlayer(player.uuid).isEmpty());
        assertTrue(plugin.findPendingReconnect(player.uuid).isEmpty());
        assertTrue(player.transferredServers.contains("hubminigame"),
                "leave must transfer to hub, got: " + player.transferredServers);
    }

    @Test
    void leaveOnHubWithoutMatchDoesNotGetStuck() {
        MockPlayer player = mockProxy.register("Stuck5");
        RegisteredServer hub = MockProxy.mockServer("hubminigame");
        player.currentServer = hub;

        boolean left = plugin.leaveMatch(player.proxy);
        assertFalse(left);
        assertTrue(player.messages.stream().anyMatch(m -> m.contains("já está no Hub")
                || m.contains("não está em partida")));
    }

    @Test
    void velocityLeaveCommandClassExists() {
        assertNotNull(VelocityLeaveCommand.class);
    }

    @Test
    void voluntaryHubTransferAbandonsActiveMatchInsteadOfYankingBack() {
        MockPlayer player = mockProxy.register("HubLoop1");
        var st = newMatch();
        admitActive(player, st);
        // Player is on the minigame and voluntarily goes to hub (/server hub, foreign /hub).
        player.currentServer = MockProxy.mockServer(instanceId.value());
        RegisteredServer hub = MockProxy.mockServer("hubminigame");

        plugin.onServerPreConnect(new ServerPreConnectEvent(player.proxy, hub));

        assertTrue(plugin.matchRegistry().findActiveForPlayer(player.uuid).isEmpty(),
                "Voluntary hub transfer must abandon match so arrival doesn't auto-yank");
        assertTrue(plugin.findPendingReconnect(player.uuid).isEmpty());
        assertTrue(player.messages.stream().anyMatch(m -> m.contains("saiu da partida")),
                "Got: " + player.messages);
    }

    @Test
    void freshLoginToHubPreservesPendingReconnectForCrashRecovery() {
        MockPlayer player = mockProxy.register("HubLoop2");
        var st = newMatch();
        admitActive(player, st);
        // Crash: proxy DisconnectEvent holds the slot.
        plugin.matchRegistry().setPlayerDisconnected(st.matchId(), player.uuid,
                Instant.now().plusSeconds(60), Instant.now());
        assertTrue(plugin.findPendingReconnect(player.uuid).isPresent());

        // Fresh login lands on hub with no previous server: must NOT abandon.
        assertNull(player.currentServer);
        RegisteredServer hub = MockProxy.mockServer("hubminigame");
        plugin.onServerPreConnect(new ServerPreConnectEvent(player.proxy, hub));

        assertTrue(plugin.findPendingReconnect(player.uuid).isPresent(),
                "Fresh login must keep pending reconnect for auto-reconnect recovery");
    }

    @Test
    void switchToOtherMinigameDoesNotAbandonViaPreConnect() {
        MockPlayer player = mockProxy.register("HubLoop3");
        var st = newMatch();
        admitActive(player, st);
        player.currentServer = MockProxy.mockServer(instanceId.value());
        RegisteredServer bedwars = MockProxy.mockServer("bedwars");

        plugin.onServerPreConnect(new ServerPreConnectEvent(player.proxy, bedwars));

        assertTrue(plugin.matchRegistry().findActiveForPlayer(player.uuid).isPresent(),
                "Non-hub switches must not be touched by hub-abandon");
    }
}
