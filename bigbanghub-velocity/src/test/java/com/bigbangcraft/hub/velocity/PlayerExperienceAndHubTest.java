package com.bigbangcraft.hub.velocity;

import com.bigbangcraft.hub.api.AdmissionTicket;
import com.bigbangcraft.hub.api.GameId;
import com.bigbangcraft.hub.api.MatchDefinition;
import com.bigbangcraft.hub.api.MatchId;
import com.bigbangcraft.hub.api.ParticipantRole;
import com.bigbangcraft.hub.api.PartySnapshot;
import com.bigbangcraft.hub.api.ServerId;
import com.bigbangcraft.hub.common.ConfigLoader;
import com.bigbangcraft.hub.common.HubConfigSnapshot;
import com.bigbangcraft.hub.common.InMemoryMatchRegistry;
import com.bigbangcraft.hub.common.MessagePayloads;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerExperienceAndHubTest {
    private BigBangHubVelocityPlugin plugin;
    private MockProxyServer mockProxy;
    private GameId gameId;
    private ServerId instanceId;
    private UUID sessionId;

    static class MockPlayer {
        final UUID uuid = UUID.randomUUID();
        final String name;
        final List<String> messages = new ArrayList<>();
        final List<Title> titles = new ArrayList<>();
        final List<Sound> sounds = new ArrayList<>();
        final List<Component> actionBars = new ArrayList<>();
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
                        if (method.getName().equals("sendMessage")) {
                            return null;
                        }
                        if (method.getName().equals("showTitle") && args.length > 0 && args[0] instanceof Title t) {
                            titles.add(t);
                            return null;
                        }
                        if (method.getName().equals("playSound") && args.length > 0 && args[0] instanceof Sound s) {
                            sounds.add(s);
                            return null;
                        }
                        if (method.getName().equals("sendActionBar") && args.length > 0 && args[0] instanceof Component c) {
                            actionBars.add(c);
                            return null;
                        }
                        if (method.getName().equals("hasPermission")) return true;
                        if (method.getName().equals("getCurrentServer")) return Optional.ofNullable(currentServer != null ? mockConn : null);
                        if (method.getName().equals("createConnectionRequest")) {
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
        Path directory = Files.createTempDirectory("bbhub-fx-test");
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
    void testMatchFoundTriggersSoundAndTitle() {
        MockPlayer p1 = mockProxy.register("GamerA");
        MockPlayer p2 = mockProxy.register("GamerB");

        // Pre-create match
        MatchDefinition def = MatchDefinition.builder().gameId(gameId).minPlayers(2).maxPlayers(4).allowLateJoin(false).build();
        InMemoryMatchRegistry.MatchSessionState matchState =
                plugin.matchRegistry().createMatch(MatchId.random(), def, instanceId, sessionId, Instant.now());
        matchState.stateMachine().transition(com.bigbangcraft.hub.api.MatchState.CREATED, com.bigbangcraft.hub.api.MatchState.WAITING, Instant.now());

        // Queue both players -> triggers dispatchQueue
        plugin.join(p1.proxy, gameId);
        plugin.join(p2.proxy, gameId);

        // Both players must have received match found title and levelup sound!
        assertFalse(p1.titles.isEmpty(), "Player 1 must receive match found title");
        assertFalse(p1.sounds.isEmpty(), "Player 1 must receive match found sound");
        assertEquals("entity.player.levelup", p1.sounds.get(0).name().value());

        assertFalse(p2.titles.isEmpty(), "Player 2 must receive match found title");
        assertFalse(p2.sounds.isEmpty(), "Player 2 must receive match found sound");
        assertEquals("entity.player.levelup", p2.sounds.get(0).name().value());
    }

    @Test
    void testReconnectAvailableTriggersSoundAndTitle() {
        MockPlayer p1 = mockProxy.register("GamerRec");

        MatchDefinition def = MatchDefinition.builder().gameId(gameId).minPlayers(2).maxPlayers(4).allowLateJoin(false).build();
        InMemoryMatchRegistry.MatchSessionState matchState =
                plugin.matchRegistry().createMatch(MatchId.random(), def, instanceId, sessionId, Instant.now());
        matchState.stateMachine().transition(com.bigbangcraft.hub.api.MatchState.CREATED, com.bigbangcraft.hub.api.MatchState.WAITING, Instant.now());

        AdmissionTicket t1 = plugin.ticketService().issue(p1.uuid, matchState.matchId(), instanceId, ParticipantRole.PLAYER, Instant.now(), Duration.ofSeconds(10));
        plugin.matchRegistry().admitPlayer(t1, Instant.now());

        // Disconnect
        DisconnectEvent disconnectEvent = new DisconnectEvent(p1.proxy, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN);
        plugin.onDisconnect(disconnectEvent);

        // Player re-connects to hub
        RegisteredServer hubServer = (RegisteredServer) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{RegisteredServer.class},
                (s, smethod, sargs) -> smethod.getName().equals("getServerInfo") ? new ServerInfo("hubminigame", new InetSocketAddress("127.0.0.1", 25565)) : null);
        p1.currentServer = hubServer;

        ServerPostConnectEvent postConnect = new ServerPostConnectEvent(p1.proxy, null);
        plugin.onServerPostConnect(postConnect);

        // Reconnect sound and title must be triggered
        assertFalse(p1.titles.isEmpty());
        assertFalse(p1.sounds.isEmpty());
        assertEquals("block.note_block.pling", p1.sounds.get(0).name().value());
    }

    @Test
    void testPartyInviteDisbandAndKickFx() {
        MockPlayer leader = mockProxy.register("LeadFX");
        MockPlayer member = mockProxy.register("MemFX");

        VelocityPartyCommand cmd = new VelocityPartyCommand(plugin);

        // Leader invites member
        cmd.execute(new com.velocitypowered.api.command.SimpleCommand.Invocation() {
            @Override public com.velocitypowered.api.command.CommandSource source() { return leader.proxy; }
            @Override public String[] arguments() { return new String[]{"invite", member.name}; }
            @Override public String alias() { return "party"; }
        });

        assertFalse(member.titles.isEmpty());
        assertFalse(member.sounds.isEmpty());
        assertEquals("entity.experience_orb.pickup", member.sounds.get(0).name().value());

        // Member accepts
        PartySnapshot party = plugin.parties().partyOf(leader.uuid).orElseThrow();
        plugin.parties().acceptInvite(member.uuid, party.partyId());

        // Leader disbands party
        cmd.execute(new com.velocitypowered.api.command.SimpleCommand.Invocation() {
            @Override public com.velocitypowered.api.command.CommandSource source() { return leader.proxy; }
            @Override public String[] arguments() { return new String[]{"disband"}; }
            @Override public String alias() { return "party"; }
        });

        // Member must receive disband title & villager.no sound
        assertEquals(2, member.titles.size());
        assertEquals(2, member.sounds.size());
        assertEquals("entity.villager.no", member.sounds.get(1).name().value());
    }

    @Test
    void testPartyHudActionbarDisplaysLeaderMemberAndStatus() {
        MockPlayer leader = mockProxy.register("LeadHud");
        MockPlayer member = mockProxy.register("MemHud");

        PartySnapshot party = plugin.parties().createParty(leader.uuid);
        plugin.parties().invitePlayer(leader.uuid, member.uuid);
        plugin.parties().acceptInvite(member.uuid, party.partyId());

        PartySnapshot activeParty = plugin.parties().partyOf(leader.uuid).orElseThrow();

        // Update Party HUD for leader
        plugin.updatePartyHud(leader.proxy, activeParty);
        assertFalse(leader.actionBars.isEmpty());

        // Update Party HUD for member
        plugin.updatePartyHud(member.proxy, activeParty);
        assertFalse(member.actionBars.isEmpty());
    }
}
