package com.bigbangcraft.hub.velocity;

import com.bigbangcraft.hub.api.AdmissionTicket;
import com.bigbangcraft.hub.api.GameId;
import com.bigbangcraft.hub.api.MatchDefinition;
import com.bigbangcraft.hub.api.MatchId;
import com.bigbangcraft.hub.api.MatchParticipant;
import com.bigbangcraft.hub.api.MatchResult;
import com.bigbangcraft.hub.api.MatchState;
import com.bigbangcraft.hub.api.ParticipantRole;
import com.bigbangcraft.hub.api.PartyId;
import com.bigbangcraft.hub.api.PartyRole;
import com.bigbangcraft.hub.api.PartySnapshot;
import com.bigbangcraft.hub.api.PartyState;
import com.bigbangcraft.hub.api.ServerId;
import com.bigbangcraft.hub.common.ConfigLoader;
import com.bigbangcraft.hub.common.HubConfigSnapshot;
import com.bigbangcraft.hub.common.InMemoryMatchRegistry;
import com.bigbangcraft.hub.common.MessagePayloads;
import com.bigbangcraft.hub.common.MessageType;
import com.bigbangcraft.hub.common.ProtocolEnvelope;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RematchAndPlayAgainTest {
    private BigBangHubVelocityPlugin plugin;
    private MockProxyServer mockProxy;
    private GameId gameId;
    private ServerId instanceId;
    private UUID sessionId;

    static class MockPlayer {
        final UUID uuid = UUID.randomUUID();
        final String name;
        final List<String> messages = new ArrayList<>();
        final List<Component> adventureMessages = new ArrayList<>();
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
                            if (args[0] instanceof Component comp) {
                                adventureMessages.add(comp);
                            }
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
        Path directory = Files.createTempDirectory("bbhub-rematch-test");
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
    void testMatchFinishTriggersDecisionPromptAndCreatesSession() {
        MockPlayer p1 = mockProxy.register("Alice");
        MockPlayer p2 = mockProxy.register("Bob");

        MatchDefinition def = MatchDefinition.builder().gameId(gameId).minPlayers(2).maxPlayers(4).allowLateJoin(false).build();
        InMemoryMatchRegistry.MatchSessionState matchState =
                plugin.matchRegistry().createMatch(MatchId.random(), def, instanceId, sessionId, Instant.now());
        matchState.stateMachine().transition(MatchState.CREATED, MatchState.WAITING, Instant.now());

        AdmissionTicket t1 = plugin.ticketService().issue(p1.uuid, matchState.matchId(), instanceId, ParticipantRole.PLAYER, Instant.now(), Duration.ofSeconds(10));
        AdmissionTicket t2 = plugin.ticketService().issue(p2.uuid, matchState.matchId(), instanceId, ParticipantRole.PLAYER, Instant.now(), Duration.ofSeconds(10));
        plugin.matchRegistry().admitPlayer(t1, Instant.now());
        plugin.matchRegistry().admitPlayer(t2, Instant.now());

        matchState.stateMachine().transition(MatchState.WAITING, MatchState.COUNTDOWN, Instant.now());
        matchState.stateMachine().transition(MatchState.COUNTDOWN, MatchState.LOCKED, Instant.now());
        matchState.stateMachine().transition(MatchState.LOCKED, MatchState.IN_GAME, Instant.now());

        // Match finishes
        plugin.matchRegistry().finishMatch(matchState.matchId(), sessionId, matchState.stateMachine().revision(),
                new MatchResult(MatchResult.Outcome.WIN, Set.of(p1.uuid), Duration.ofSeconds(60), Map.of()), Instant.now());

        plugin.handlePostMatchDecision(matchState.matchId());

        // Both players must have received the interactive post-match prompt
        assertFalse(p1.adventureMessages.isEmpty());
        assertFalse(p2.adventureMessages.isEmpty());

        // Rematch session must be created
        assertTrue(plugin.rematchService().activeSessionForPlayer(p1.uuid, Instant.now()).isPresent());
        assertTrue(plugin.rematchService().activeSessionForPlayer(p2.uuid, Instant.now()).isPresent());
    }

    @Test
    void testSoloPlayerPlayAgainRequeuesDirectly() {
        MockPlayer p1 = mockProxy.register("SoloGamer");

        MatchDefinition def = MatchDefinition.builder().gameId(gameId).minPlayers(2).maxPlayers(4).allowLateJoin(false).build();
        InMemoryMatchRegistry.MatchSessionState matchState =
                plugin.matchRegistry().createMatch(MatchId.random(), def, instanceId, sessionId, Instant.now());
        matchState.stateMachine().transition(MatchState.CREATED, MatchState.WAITING, Instant.now());

        AdmissionTicket t1 = plugin.ticketService().issue(p1.uuid, matchState.matchId(), instanceId, ParticipantRole.PLAYER, Instant.now(), Duration.ofSeconds(10));
        plugin.matchRegistry().admitPlayer(t1, Instant.now());

        matchState.stateMachine().transition(MatchState.WAITING, MatchState.COUNTDOWN, Instant.now());
        matchState.stateMachine().transition(MatchState.COUNTDOWN, MatchState.LOCKED, Instant.now());
        matchState.stateMachine().transition(MatchState.LOCKED, MatchState.IN_GAME, Instant.now());

        plugin.matchRegistry().finishMatch(matchState.matchId(), sessionId, matchState.stateMachine().revision(),
                new MatchResult(MatchResult.Outcome.WIN, Set.of(p1.uuid), Duration.ofSeconds(60), Map.of()), Instant.now());
        plugin.handlePostMatchDecision(matchState.matchId());

        // Player executes Play Again
        plugin.handlePlayAgain(p1.proxy);

        // Player must be enqueued into the game queue!
        assertTrue(plugin.queues().contains(p1.uuid));
        // Rematch session for player should be cleared
        assertTrue(plugin.rematchService().activeSessionForPlayer(p1.uuid, Instant.now()).isEmpty());
    }

    @Test
    void testPartyMemberPlayAgainBlocked() {
        MockPlayer leader = mockProxy.register("PartyLead");
        MockPlayer member = mockProxy.register("PartyMem");

        PartySnapshot party = plugin.parties().createParty(leader.uuid);
        plugin.parties().invitePlayer(leader.uuid, member.uuid);
        plugin.parties().acceptInvite(member.uuid, party.partyId());

        MatchDefinition def = MatchDefinition.builder().gameId(gameId).minPlayers(2).maxPlayers(4).allowLateJoin(false).build();
        InMemoryMatchRegistry.MatchSessionState matchState =
                plugin.matchRegistry().createMatch(MatchId.random(), def, instanceId, sessionId, Instant.now());
        matchState.stateMachine().transition(MatchState.CREATED, MatchState.WAITING, Instant.now());

        AdmissionTicket t1 = plugin.ticketService().issue(leader.uuid, matchState.matchId(), instanceId, ParticipantRole.PLAYER, Instant.now(), Duration.ofSeconds(10), Optional.of(party.partyId()), false);
        AdmissionTicket t2 = plugin.ticketService().issue(member.uuid, matchState.matchId(), instanceId, ParticipantRole.PLAYER, Instant.now(), Duration.ofSeconds(10), Optional.of(party.partyId()), false);
        plugin.matchRegistry().admitPlayer(t1, Instant.now());
        plugin.matchRegistry().admitPlayer(t2, Instant.now());

        matchState.stateMachine().transition(MatchState.WAITING, MatchState.COUNTDOWN, Instant.now());
        matchState.stateMachine().transition(MatchState.COUNTDOWN, MatchState.LOCKED, Instant.now());
        matchState.stateMachine().transition(MatchState.LOCKED, MatchState.IN_GAME, Instant.now());

        plugin.matchRegistry().finishMatch(matchState.matchId(), sessionId, matchState.stateMachine().revision(),
                new MatchResult(MatchResult.Outcome.WIN, Set.of(leader.uuid), Duration.ofSeconds(60), Map.of()), Instant.now());
        plugin.handlePostMatchDecision(matchState.matchId());

        // Non-leader member executes Play Again
        plugin.handlePlayAgain(member.proxy);

        // Member receives warning and is NOT queued
        assertTrue(member.messages.stream().anyMatch(m -> m.contains("Apenas o líder da party")));
        assertFalse(plugin.queues().contains(member.uuid));
    }

    @Test
    void testPartyLeaderPlayAgainRequeuesPartyAtomically() {
        MockPlayer leader = mockProxy.register("LeadGamer");
        MockPlayer member = mockProxy.register("MemGamer");

        PartySnapshot party = plugin.parties().createParty(leader.uuid);
        plugin.parties().invitePlayer(leader.uuid, member.uuid);
        plugin.parties().acceptInvite(member.uuid, party.partyId());

        MatchDefinition def = MatchDefinition.builder().gameId(gameId).minPlayers(2).maxPlayers(4).allowLateJoin(false).build();
        InMemoryMatchRegistry.MatchSessionState matchState =
                plugin.matchRegistry().createMatch(MatchId.random(), def, instanceId, sessionId, Instant.now());
        matchState.stateMachine().transition(MatchState.CREATED, MatchState.WAITING, Instant.now());

        AdmissionTicket t1 = plugin.ticketService().issue(leader.uuid, matchState.matchId(), instanceId, ParticipantRole.PLAYER, Instant.now(), Duration.ofSeconds(10), Optional.of(party.partyId()), false);
        AdmissionTicket t2 = plugin.ticketService().issue(member.uuid, matchState.matchId(), instanceId, ParticipantRole.PLAYER, Instant.now(), Duration.ofSeconds(10), Optional.of(party.partyId()), false);
        plugin.matchRegistry().admitPlayer(t1, Instant.now());
        plugin.matchRegistry().admitPlayer(t2, Instant.now());

        matchState.stateMachine().transition(MatchState.WAITING, MatchState.COUNTDOWN, Instant.now());
        matchState.stateMachine().transition(MatchState.COUNTDOWN, MatchState.LOCKED, Instant.now());
        matchState.stateMachine().transition(MatchState.LOCKED, MatchState.IN_GAME, Instant.now());

        plugin.matchRegistry().finishMatch(matchState.matchId(), sessionId, matchState.stateMachine().revision(),
                new MatchResult(MatchResult.Outcome.WIN, Set.of(leader.uuid), Duration.ofSeconds(60), Map.of()), Instant.now());
        plugin.handlePostMatchDecision(matchState.matchId());

        // Leader executes Play Again
        plugin.handlePlayAgain(leader.proxy);

        // Leader must be enqueued
        assertTrue(plugin.queues().contains(leader.uuid));
        // Member receives notification
        assertTrue(member.messages.stream().anyMatch(m -> m.contains("líder colocou a party na fila")));
    }

    @Test
    void testRematchVotingConsensus() {
        MockPlayer p1 = mockProxy.register("PlayerOne");
        MockPlayer p2 = mockProxy.register("PlayerTwo");

        MatchDefinition def = MatchDefinition.builder().gameId(gameId).minPlayers(2).maxPlayers(4).allowLateJoin(false).build();
        InMemoryMatchRegistry.MatchSessionState matchState =
                plugin.matchRegistry().createMatch(MatchId.random(), def, instanceId, sessionId, Instant.now());
        matchState.stateMachine().transition(MatchState.CREATED, MatchState.WAITING, Instant.now());

        AdmissionTicket t1 = plugin.ticketService().issue(p1.uuid, matchState.matchId(), instanceId, ParticipantRole.PLAYER, Instant.now(), Duration.ofSeconds(10));
        AdmissionTicket t2 = plugin.ticketService().issue(p2.uuid, matchState.matchId(), instanceId, ParticipantRole.PLAYER, Instant.now(), Duration.ofSeconds(10));
        plugin.matchRegistry().admitPlayer(t1, Instant.now());
        plugin.matchRegistry().admitPlayer(t2, Instant.now());

        matchState.stateMachine().transition(MatchState.WAITING, MatchState.COUNTDOWN, Instant.now());
        matchState.stateMachine().transition(MatchState.COUNTDOWN, MatchState.LOCKED, Instant.now());
        matchState.stateMachine().transition(MatchState.LOCKED, MatchState.IN_GAME, Instant.now());

        plugin.matchRegistry().finishMatch(matchState.matchId(), sessionId, matchState.stateMachine().revision(),
                new MatchResult(MatchResult.Outcome.WIN, Set.of(p1.uuid), Duration.ofSeconds(60), Map.of()), Instant.now());
        plugin.handlePostMatchDecision(matchState.matchId());

        // P1 votes for rematch
        plugin.handleRematchVote(p1.proxy);
        assertTrue(p1.messages.stream().anyMatch(m -> m.contains("votou por revanche! (1/2)")));
        assertTrue(p2.messages.stream().anyMatch(m -> m.contains("votou por revanche! (1/2)")));

        // P2 votes for rematch -> consensus reached!
        plugin.handleRematchVote(p2.proxy);
        assertTrue(p1.messages.stream().anyMatch(m -> m.contains("Revanche aceita por todos")));
        assertTrue(p2.messages.stream().anyMatch(m -> m.contains("Revanche aceita por todos")));

        // Both players are queued for the rematch!
        assertTrue(plugin.queues().contains(p1.uuid));
        assertTrue(plugin.queues().contains(p2.uuid));
    }
}
