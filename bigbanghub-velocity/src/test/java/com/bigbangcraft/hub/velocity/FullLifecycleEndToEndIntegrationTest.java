package com.bigbangcraft.hub.velocity;

import com.bigbangcraft.hub.api.AdmissionTicket;
import com.bigbangcraft.hub.api.GameId;
import com.bigbangcraft.hub.api.MatchDefinition;
import com.bigbangcraft.hub.api.MatchId;
import com.bigbangcraft.hub.api.MatchResult;
import com.bigbangcraft.hub.api.MatchState;
import com.bigbangcraft.hub.api.ParticipantRole;
import com.bigbangcraft.hub.api.ParticipantState;
import com.bigbangcraft.hub.api.PartyId;
import com.bigbangcraft.hub.api.PartySnapshot;
import com.bigbangcraft.hub.api.PartyState;
import com.bigbangcraft.hub.api.ServerId;
import com.bigbangcraft.hub.common.ConfigLoader;
import com.bigbangcraft.hub.common.HubConfigSnapshot;
import com.bigbangcraft.hub.common.InMemoryMatchRegistry;
import com.bigbangcraft.hub.common.MessagePayloads;
import com.bigbangcraft.hub.common.RematchService;
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

class FullLifecycleEndToEndIntegrationTest {
    private BigBangHubVelocityPlugin plugin;
    private MockProxyServer mockProxy;
    private GameId gameId;
    private ServerId instanceId;
    private UUID sessionId;

    static class MockPlayer {
        final UUID uuid = UUID.randomUUID();
        final String name;
        final List<String> messages = new ArrayList<>();
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
                        if (method.getName().equals("sendMessage")) return null;
                        if (method.getName().equals("hasPermission")) return true;
                        if (method.getName().equals("getCurrentServer")) return Optional.ofNullable(currentServer != null ? mockConn : null);
                        if (method.getName().equals("createConnectionRequest")) return mockReq;
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
        Path directory = Files.createTempDirectory("bbhub-e2e-test");
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

        // Register healthy backend instance
        plugin.instanceRegistry().register(new MessagePayloads.InstanceRegister(
                instanceId, gameId, "campominado-01", sessionId,
                MessagePayloads.GameStateWire.WAITING, 0, 3, 10, true), 0L, Instant.now());
    }

    @Test
    void testCompletePartyMatchReconnectAndRematchLifecycle() {
        Instant now = Instant.now();

        // Phase 1: Players join Hub and form a 3-player party
        MockPlayer leader = mockProxy.register("E2ELeader");
        MockPlayer mem1 = mockProxy.register("E2EMember1");
        MockPlayer mem2 = mockProxy.register("E2EMember2");

        PartySnapshot party = plugin.parties().createParty(leader.uuid);
        PartyId partyId = party.partyId();
        assertEquals(PartyState.IDLE, party.state());

        plugin.parties().invitePlayer(leader.uuid, mem1.uuid);
        plugin.parties().acceptInvite(mem1.uuid, partyId);

        if (plugin.parties() instanceof com.bigbangcraft.hub.common.InMemoryPartyService memParties) {
            memParties.setClock(java.time.Clock.offset(java.time.Clock.systemUTC(), java.time.Duration.ofSeconds(10)));
        }

        plugin.parties().invitePlayer(leader.uuid, mem2.uuid);
        plugin.parties().acceptInvite(mem2.uuid, partyId);

        assertEquals(3, plugin.parties().party(partyId).orElseThrow().size());

        // Phase 2: Party leader queues party for campominado
        plugin.join(leader.proxy, gameId);

        // Queue dispatch reserves capacity and moves party to ASSIGNED
        assertEquals(PartyState.ASSIGNED, plugin.parties().party(partyId).orElseThrow().state());
        assertTrue(plugin.reservationService().getActive(leader.uuid).isPresent());
        assertTrue(plugin.reservationService().getActive(mem1.uuid).isPresent());
        assertTrue(plugin.reservationService().getActive(mem2.uuid).isPresent());

        // Phase 3: Match created on assigned instance
        MatchId matchId = MatchId.random();
        MatchDefinition def = MatchDefinition.builder().gameId(gameId).minPlayers(2).maxPlayers(10).allowLateJoin(true).build();
        InMemoryMatchRegistry.MatchSessionState session = plugin.matchRegistry().createMatch(matchId, def, instanceId, sessionId, now);
        session.stateMachine().transition(MatchState.CREATED, MatchState.WAITING, now);

        // Phase 3: Admission tickets issued with party metadata and consumed
        AdmissionTicket tLead = plugin.ticketService().issue(leader.uuid, matchId, instanceId, ParticipantRole.PLAYER, now, Duration.ofSeconds(10), Optional.of(partyId));
        AdmissionTicket tMem1 = plugin.ticketService().issue(mem1.uuid, matchId, instanceId, ParticipantRole.PLAYER, now, Duration.ofSeconds(10), Optional.of(partyId));
        AdmissionTicket tMem2 = plugin.ticketService().issue(mem2.uuid, matchId, instanceId, ParticipantRole.PLAYER, now, Duration.ofSeconds(10), Optional.of(partyId));

        plugin.matchRegistry().admitPlayer(tLead, now);
        plugin.matchRegistry().admitPlayer(tMem1, now);
        plugin.matchRegistry().admitPlayer(tMem2, now);

        plugin.parties().transitionState(partyId, PartyState.IN_MATCH);
        assertEquals(PartyState.IN_MATCH, plugin.parties().party(partyId).orElseThrow().state());

        // Match progresses to IN_GAME
        session.stateMachine().transition(MatchState.WAITING, MatchState.COUNTDOWN, now);
        session.stateMachine().transition(MatchState.COUNTDOWN, MatchState.LOCKED, now);
        session.stateMachine().transition(MatchState.LOCKED, MatchState.IN_GAME, now);
        assertEquals(MatchState.IN_GAME, session.stateMachine().state());

        // Phase 4: Member2 disconnects and recovers within reconnect window
        session.setDisconnected(mem2.uuid, now.plusSeconds(30));
        assertEquals(ParticipantState.DISCONNECTED, session.participant(mem2.uuid).orElseThrow().state());

        // Member2 reconnects
        AdmissionTicket reconnectTicket = plugin.ticketService().issue(mem2.uuid, matchId, instanceId, ParticipantRole.PLAYER, now.plusSeconds(5), Duration.ofSeconds(10), Optional.of(partyId));
        plugin.matchRegistry().admitPlayer(reconnectTicket, now.plusSeconds(5));
        assertEquals(ParticipantState.ACTIVE, session.participant(mem2.uuid).orElseThrow().state());

        // Phase 5: Match finishes and rematch vote consensus
        MatchResult result = MatchResult.singleWinner(leader.uuid, Duration.ofSeconds(45));
        plugin.matchRegistry().finishMatch(matchId, sessionId, session.snapshot().revision(), result, now.plusSeconds(50));
        assertEquals(MatchState.FINISHED, session.stateMachine().state());

        // Start rematch session
        RematchService.RematchSession rematch = plugin.rematchService().createSession(
                matchId, gameId, List.of(leader.uuid, mem1.uuid, mem2.uuid), Duration.ofSeconds(15), now.plusSeconds(50));

        // All 3 cast votes
        plugin.rematchService().vote(leader.uuid, now.plusSeconds(51));
        plugin.rematchService().vote(mem1.uuid, now.plusSeconds(52));
        var consensus = plugin.rematchService().vote(mem2.uuid, now.plusSeconds(53));

        assertTrue(consensus.isPresent() && consensus.get().consensusReached(), "Consensus should be reached when all players vote");

        // Phase 6: Teardown, arena ready handshake, and party returned to IDLE
        plugin.parties().transitionState(partyId, PartyState.IDLE);
        assertEquals(PartyState.IDLE, plugin.parties().party(partyId).orElseThrow().state());

        assertTrue(plugin.matchRegistry().markInstanceReady(instanceId, matchId, now.plusSeconds(60)));
        assertTrue(plugin.matchRegistry().findActiveForInstance(instanceId).isEmpty(), "Instance should be freed and ready for new matches");
    }
}
