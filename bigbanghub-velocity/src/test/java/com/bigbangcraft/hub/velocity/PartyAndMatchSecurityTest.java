package com.bigbangcraft.hub.velocity;

import com.bigbangcraft.hub.api.AdmissionTicket;
import com.bigbangcraft.hub.api.GameId;
import com.bigbangcraft.hub.api.InstanceHealth;
import com.bigbangcraft.hub.api.InstanceHealthChangedEvent;
import com.bigbangcraft.hub.api.MatchDefinition;
import com.bigbangcraft.hub.api.MatchException;
import com.bigbangcraft.hub.api.MatchId;
import com.bigbangcraft.hub.api.ParticipantRole;
import com.bigbangcraft.hub.api.PartySnapshot;
import com.bigbangcraft.hub.api.PartyState;
import com.bigbangcraft.hub.api.ServerId;
import com.bigbangcraft.hub.common.ConfigLoader;
import com.bigbangcraft.hub.common.HubConfigSnapshot;
import com.bigbangcraft.hub.common.InMemoryMatchRegistry;
import com.bigbangcraft.hub.common.MessagePayloads;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PartyAndMatchSecurityTest {
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
        Path directory = Files.createTempDirectory("bbhub-sec-test");
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

        plugin.instanceRegistry().register(new MessagePayloads.InstanceRegister(
                instanceId, gameId, "campominado-01", sessionId,
                MessagePayloads.GameStateWire.WAITING, 0, 2, 10, true), 0L, Instant.now());
    }

    @Test
    void testPlayerCannotJoinQueueWhileInActiveMatch() {
        MockPlayer player = mockProxy.register("SecPlayer1");

        // Player is currently admitted to a match
        MatchDefinition def = MatchDefinition.builder().gameId(gameId).minPlayers(2).maxPlayers(4).allowLateJoin(false).build();
        InMemoryMatchRegistry.MatchSessionState matchState =
                plugin.matchRegistry().createMatch(MatchId.random(), def, instanceId, sessionId, Instant.now());
        matchState.stateMachine().transition(com.bigbangcraft.hub.api.MatchState.CREATED, com.bigbangcraft.hub.api.MatchState.WAITING, Instant.now());

        AdmissionTicket ticket = plugin.ticketService().issue(player.uuid, matchState.matchId(), instanceId, ParticipantRole.PLAYER, Instant.now(), Duration.ofSeconds(10));
        plugin.matchRegistry().admitPlayer(ticket, Instant.now());

        // Attempting to join queue must be rejected
        plugin.join(player.proxy, gameId);

        assertTrue(player.messages.stream().anyMatch(m -> m.contains("Você já possui uma partida ativa")));
    }

    @Test
    void testUsernameSanitizationBlocksInvalidCharacters() {
        MockPlayer player = mockProxy.register("SecLeader");
        VelocityPartyCommand cmd = new VelocityPartyCommand(plugin);

        // Invalid usernames
        String[] invalidNames = {"a", "User with space", "Bad@char", "toolongusername123456789", "../hack"};

        for (String invalid : invalidNames) {
            cmd.execute(new com.velocitypowered.api.command.SimpleCommand.Invocation() {
                @Override public com.velocitypowered.api.command.CommandSource source() { return player.proxy; }
                @Override public String[] arguments() { return new String[]{"invite", invalid}; }
                @Override public String alias() { return "party"; }
            });
            assertTrue(player.messages.stream().anyMatch(m -> m.contains("Nome de jogador inválido")),
                    "Expected invalid username error for: " + invalid);
            player.messages.clear();
        }
    }

    @Test
    void testInviteRateLimitingPreventsFlood() {
        MockPlayer leader = mockProxy.register("FloodLead");
        MockPlayer t1 = mockProxy.register("TargetOne");
        MockPlayer t2 = mockProxy.register("TargetTwo");

        VelocityPartyCommand cmd = new VelocityPartyCommand(plugin);

        // Invite 1
        cmd.execute(new com.velocitypowered.api.command.SimpleCommand.Invocation() {
            @Override public com.velocitypowered.api.command.CommandSource source() { return leader.proxy; }
            @Override public String[] arguments() { return new String[]{"invite", t1.name}; }
            @Override public String alias() { return "party"; }
        });

        // Invite 2 immediately (within invite cooldown)
        cmd.execute(new com.velocitypowered.api.command.SimpleCommand.Invocation() {
            @Override public com.velocitypowered.api.command.CommandSource source() { return leader.proxy; }
            @Override public String[] arguments() { return new String[]{"invite", t2.name}; }
            @Override public String alias() { return "party"; }
        });

        assertTrue(leader.messages.stream().anyMatch(m -> m.contains("Aguarde alguns segundos")),
                "Expected invite rate limit message on second fast invite");
    }

    @Test
    void testGracefulDegradationOnInstanceCrashUnlocksParty() {
        MockPlayer leader = mockProxy.register("CrashLead");
        MockPlayer member = mockProxy.register("CrashMem");

        PartySnapshot party = plugin.parties().createParty(leader.uuid);
        plugin.parties().invitePlayer(leader.uuid, member.uuid);
        plugin.parties().acceptInvite(member.uuid, party.partyId());

        MatchDefinition def = MatchDefinition.builder().gameId(gameId).minPlayers(2).maxPlayers(4).allowLateJoin(false).build();
        InMemoryMatchRegistry.MatchSessionState matchState =
                plugin.matchRegistry().createMatch(MatchId.random(), def, instanceId, sessionId, Instant.now());
        matchState.stateMachine().transition(com.bigbangcraft.hub.api.MatchState.CREATED, com.bigbangcraft.hub.api.MatchState.WAITING, Instant.now());

        AdmissionTicket t1 = plugin.ticketService().issue(leader.uuid, matchState.matchId(), instanceId, ParticipantRole.PLAYER, Instant.now(), Duration.ofSeconds(10), Optional.of(party.partyId()));
        AdmissionTicket t2 = plugin.ticketService().issue(member.uuid, matchState.matchId(), instanceId, ParticipantRole.PLAYER, Instant.now(), Duration.ofSeconds(10), Optional.of(party.partyId()));

        plugin.matchRegistry().admitPlayer(t1, Instant.now());
        plugin.matchRegistry().admitPlayer(t2, Instant.now());
        plugin.parties().transitionState(party.partyId(), PartyState.IN_MATCH);

        assertEquals(PartyState.IN_MATCH, plugin.parties().partyOf(leader.uuid).orElseThrow().state());

        // Instance crashes / becomes UNAVAILABLE
        plugin.handleInstanceCrash(instanceId);

        // Party must be reset to IDLE and members notified
        assertEquals(PartyState.IDLE, plugin.parties().partyOf(leader.uuid).orElseThrow().state());
        assertTrue(leader.messages.stream().anyMatch(m -> m.contains("ficou indisponível")));
        assertTrue(member.messages.stream().anyMatch(m -> m.contains("ficou indisponível")));
    }

    @Test
    void testExpiredTicketCannotBeConsumed() {
        Instant now = Instant.now();
        AdmissionTicket ticket = plugin.ticketService().issue(
                UUID.randomUUID(), MatchId.random(), instanceId, ParticipantRole.PLAYER, now, Duration.ofSeconds(5));

        // Consume 10s later -> expired
        Instant later = now.plusSeconds(10);
        assertThrows(MatchException.class, () ->
                plugin.ticketService().consume(
                        ticket.ticketId(), ticket.playerId(), ticket.matchId(), ticket.instanceId(), ticket.token(), later));
    }
}
