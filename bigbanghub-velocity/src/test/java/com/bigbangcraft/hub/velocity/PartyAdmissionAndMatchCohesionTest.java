package com.bigbangcraft.hub.velocity;

import com.bigbangcraft.hub.api.AdmissionTicket;
import com.bigbangcraft.hub.api.GameId;
import com.bigbangcraft.hub.api.MatchDefinition;
import com.bigbangcraft.hub.api.MatchId;
import com.bigbangcraft.hub.api.MatchParticipant;
import com.bigbangcraft.hub.api.ParticipantRole;
import com.bigbangcraft.hub.api.ParticipantState;
import com.bigbangcraft.hub.api.PartyId;
import com.bigbangcraft.hub.api.PartySnapshot;
import com.bigbangcraft.hub.api.PartyState;
import com.bigbangcraft.hub.api.ReturnReason;
import com.bigbangcraft.hub.api.ServerId;
import com.bigbangcraft.hub.common.ConfigException;
import com.bigbangcraft.hub.common.ConfigLoader;
import com.bigbangcraft.hub.common.HubConfigSnapshot;
import com.bigbangcraft.hub.common.InMemoryMatchRegistry;
import com.bigbangcraft.hub.common.MessagePayloads;
import com.bigbangcraft.hub.common.MessageType;
import com.bigbangcraft.hub.common.ProtocolEnvelope;
import com.velocitypowered.api.command.SimpleCommand;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PartyAdmissionAndMatchCohesionTest {
    private BigBangHubVelocityPlugin plugin;
    private MockProxyServer mockProxy;
    private GameId gameId;

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
        Path directory = Files.createTempDirectory("bbh-cohesion-test");
        for (String file : List.of("config.yml", "menus.yml", "games.yml", "servers.yml", "messages.yml")) {
            try (var input = getClass().getResourceAsStream("/" + file)) {
                Files.copy(input, directory.resolve(file));
            }
        }

        Files.writeString(directory.resolve("config.yml"), "\nparty:\n  invite-cooldown: 0s\n", java.nio.file.StandardOpenOption.APPEND);

        mockProxy = new MockProxyServer();
        plugin = new BigBangHubVelocityPlugin(mockProxy.proxy, NOPLogger.NOP_LOGGER, directory);
        HubConfigSnapshot snapshot = ConfigLoader.load(directory);
        plugin.install(snapshot);

        gameId = GameId.of("campominado");
    }

    @Test
    void testPartyAdmissionCarriesPartyIdAndEnforcesCohesion() {
        MockPlayer leader = mockProxy.register("Leader");
        MockPlayer member = mockProxy.register("Member");

        PartySnapshot party = plugin.parties().createParty(leader.uuid);
        plugin.parties().invitePlayer(leader.uuid, member.uuid);
        plugin.parties().acceptInvite(member.uuid, party.partyId());

        // Register instance cm-01
        ServerId cm1 = ServerId.of("campominado-01");
        Instant now = Instant.now();
        UUID sessionId = UUID.randomUUID();
        plugin.instanceRegistry().register(new MessagePayloads.InstanceRegister(
                cm1, gameId, "cm-01", sessionId, MessagePayloads.GameStateWire.WAITING, 0, 2, 10, true), 0L, now);

        // Pre-create match on cm-01
        MatchId matchId = MatchId.random();
        InMemoryMatchRegistry.MatchSessionState matchState = plugin.matchRegistry().createMatch(
                matchId,
                MatchDefinition.builder().gameId(gameId).minPlayers(2).maxPlayers(10).build(),
                cm1, sessionId, now);
        matchState.stateMachine().transition(com.bigbangcraft.hub.api.MatchState.CREATED, com.bigbangcraft.hub.api.MatchState.WAITING, now);

        // Leader joins queue -> triggers dispatchQueue
        plugin.join(leader.proxy, gameId);

        // Party should be in ASSIGNED
        assertEquals(PartyState.ASSIGNED, plugin.parties().partyOf(leader.uuid).orElseThrow().state());

        // Both members must have tickets with partyId
        AdmissionTicket leaderTicket = plugin.ticketService().findActive(leader.uuid).orElseThrow();
        AdmissionTicket memberTicket = plugin.ticketService().findActive(member.uuid).orElseThrow();

        assertTrue(leaderTicket.partyId().isPresent());
        assertTrue(memberTicket.partyId().isPresent());
        assertEquals(party.partyId(), leaderTicket.partyId().get());
        assertEquals(party.partyId(), memberTicket.partyId().get());

        // Consume leader ticket via match admission
        MatchParticipant leaderParticipant = plugin.matchRegistry().admitPlayer(leaderTicket, now);
        assertEquals(party.partyId(), leaderParticipant.partyId().orElse(null));

        // Consume member ticket via match admission
        MatchParticipant memberParticipant = plugin.matchRegistry().admitPlayer(memberTicket, now);
        assertEquals(party.partyId(), memberParticipant.partyId().orElse(null));

        // Check cohesion queries in matchState
        Collection<MatchParticipant> partyParticipants = matchState.participantsOfParty(party.partyId());
        assertEquals(2, partyParticipants.size());
        assertTrue(partyParticipants.stream().anyMatch(p -> p.playerId().equals(leader.uuid)));
        assertTrue(partyParticipants.stream().anyMatch(p -> p.playerId().equals(member.uuid)));

        // Transition member to ELIMINATED: partyId must still be retained!
        Optional<MatchParticipant> eliminated = matchState.eliminate(member.uuid);
        assertTrue(eliminated.isPresent());
        assertEquals(ParticipantState.ELIMINATED, eliminated.get().state());
        assertEquals(party.partyId(), eliminated.get().partyId().orElse(null));

        Collection<MatchParticipant> partyParticipantsAfterElim = matchState.participantsOfParty(party.partyId());
        assertEquals(2, partyParticipantsAfterElim.size());
    }

    @Test
    void testSafeReturnToHubPreservesPartyAndTransitionsToIdle() {
        MockPlayer leader = mockProxy.register("Leader");
        MockPlayer member = mockProxy.register("Member");

        PartySnapshot party = plugin.parties().createParty(leader.uuid);
        plugin.parties().invitePlayer(leader.uuid, member.uuid);
        plugin.parties().acceptInvite(member.uuid, party.partyId());

        // Manually set party to IN_MATCH
        plugin.parties().transitionState(party.partyId(), PartyState.IN_MATCH);
        assertEquals(PartyState.IN_MATCH, plugin.parties().partyOf(leader.uuid).orElseThrow().state());

        // Safe return player to hub
        plugin.safeReturnPlayerToHub(leader.uuid, ReturnReason.MATCH_FINISHED, "Match over");

        // Party state must return to IDLE
        assertEquals(PartyState.IDLE, plugin.parties().partyOf(leader.uuid).orElseThrow().state());
        assertEquals(PartyState.IDLE, plugin.parties().partyOf(member.uuid).orElseThrow().state());
        assertEquals(2, plugin.parties().partyOf(leader.uuid).orElseThrow().size());
    }

    @Test
    void testPartyWarpMovesAllMembersToLeaderServer() {
        MockPlayer leader = mockProxy.register("Leader");
        MockPlayer member = mockProxy.register("Member");

        PartySnapshot party = plugin.parties().createParty(leader.uuid);
        plugin.parties().invitePlayer(leader.uuid, member.uuid);
        plugin.parties().acceptInvite(member.uuid, party.partyId());

        RegisteredServer hub = mockProxy.proxy.getServer("hubminigame").orElseThrow();
        RegisteredServer cm = mockProxy.proxy.getServer("campominado").orElseThrow();

        leader.currentServer = hub;
        member.currentServer = cm;

        // Leader executes /party warp
        VelocityPartyCommand cmd = new VelocityPartyCommand(plugin);
        cmd.execute(new SimpleCommand.Invocation() {
            @Override public com.velocitypowered.api.command.CommandSource source() { return leader.proxy; }
            @Override public String[] arguments() { return new String[]{"warp"}; }
            @Override public String alias() { return "party"; }
        });

        // Member must have been transferred to hub
        assertTrue(member.transferredServers.contains("hubminigame"));
        assertTrue(member.messages.stream().anyMatch(m -> m.contains("O líder puxou a party")));
        assertTrue(leader.messages.stream().anyMatch(m -> m.contains("Puxando 1 membro(s)")));
    }

    @Test
    void testPartyWarpNonLeaderRejected() {
        MockPlayer leader = mockProxy.register("Leader");
        MockPlayer member = mockProxy.register("Member");

        PartySnapshot party = plugin.parties().createParty(leader.uuid);
        plugin.parties().invitePlayer(leader.uuid, member.uuid);
        plugin.parties().acceptInvite(member.uuid, party.partyId());

        VelocityPartyCommand cmd = new VelocityPartyCommand(plugin);
        cmd.execute(new SimpleCommand.Invocation() {
            @Override public com.velocitypowered.api.command.CommandSource source() { return member.proxy; }
            @Override public String[] arguments() { return new String[]{"warp"}; }
            @Override public String alias() { return "party"; }
        });

        assertTrue(member.messages.stream().anyMatch(m -> m.contains("Apenas o líder")));
    }

    @Test
    void testPartyWarpRejectedWhenPartyNotInIdle() {
        MockPlayer leader = mockProxy.register("Leader");
        MockPlayer member = mockProxy.register("Member");

        PartySnapshot party = plugin.parties().createParty(leader.uuid);
        plugin.parties().invitePlayer(leader.uuid, member.uuid);
        plugin.parties().acceptInvite(member.uuid, party.partyId());

        plugin.parties().transitionState(party.partyId(), PartyState.QUEUED);

        VelocityPartyCommand cmd = new VelocityPartyCommand(plugin);
        cmd.execute(new SimpleCommand.Invocation() {
            @Override public com.velocitypowered.api.command.CommandSource source() { return leader.proxy; }
            @Override public String[] arguments() { return new String[]{"warp"}; }
            @Override public String alias() { return "party"; }
        });

        assertTrue(leader.messages.stream().anyMatch(m -> m.contains("Não é possível puxar a party no estado atual")));
    }

    @Test
    void testPartyWarpViaPluginMessage() throws Exception {
        MockPlayer leader = mockProxy.register("Leader");
        MockPlayer member = mockProxy.register("Member");

        PartySnapshot party = plugin.parties().createParty(leader.uuid);
        plugin.parties().invitePlayer(leader.uuid, member.uuid);
        plugin.parties().acceptInvite(member.uuid, party.partyId());

        RegisteredServer hub = mockProxy.proxy.getServer("hubminigame").orElseThrow();
        RegisteredServer cm = mockProxy.proxy.getServer("campominado").orElseThrow();

        leader.currentServer = hub;
        member.currentServer = cm;

        final List<byte[]> sentResponses = new ArrayList<>();
        ServerConnection mockConn = (ServerConnection) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{ServerConnection.class},
                (c, method, args) -> {
                    if (method.getName().equals("sendPluginMessage")) {
                        sentResponses.add((byte[]) args[1]);
                        return true;
                    }
                    if (method.getName().equals("getServer")) return hub;
                    if (method.getName().equals("getServerInfo")) return hub.getServerInfo();
                    if (method.getName().equals("getPlayer")) return leader.proxy;
                    return null;
                });

        byte[] payload = MessagePayloads.partyWarp(new MessagePayloads.PartyWarpPayload(leader.uuid));
        ProtocolEnvelope envelope = new ProtocolEnvelope(1, MessageType.PARTY_WARP, UUID.randomUUID(), payload);
        byte[] rawEnvelope = plugin.codec().encode(envelope);

        com.velocitypowered.api.event.connection.PluginMessageEvent event =
                new com.velocitypowered.api.event.connection.PluginMessageEvent(
                        mockConn,
                        leader.proxy,
                        com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier.from("bigbanghub:main"),
                        rawEnvelope);

        plugin.onPluginMessage(event);

        assertTrue(member.transferredServers.contains("hubminigame"));
        assertFalse(sentResponses.isEmpty());
        ProtocolEnvelope respEnv = plugin.codec().decode(sentResponses.get(0));
        assertEquals(MessageType.PARTY_RESPONSE, respEnv.messageType());
        MessagePayloads.PartyResponsePayload resp = MessagePayloads.partyResponse(respEnv.payload());
        assertTrue(resp.success());
        assertTrue(resp.message().contains("Puxando 1"));
    }
}
