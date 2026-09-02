package com.bigbangcraft.hub.velocity;

import com.bigbangcraft.hub.api.*;
import com.bigbangcraft.hub.common.*;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
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
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class GroupQueueAndPartyMatchmakingTest {
    private BigBangHubVelocityPlugin plugin;
    private MockProxyServer mockProxy;
    private GameId gameId;

    static class MockPlayer {
        final UUID uuid = UUID.randomUUID();
        final String name;
        final List<String> messages = new ArrayList<>();
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
                                if (bmethod.getName().equals("connect")) return java.util.concurrent.CompletableFuture.completedFuture(mockResult);
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
                            if (name.startsWith("cm-") || name.startsWith("campominado-")) {
                                return Optional.of((RegisteredServer) Proxy.newProxyInstance(
                                        getClass().getClassLoader(),
                                        new Class<?>[]{RegisteredServer.class},
                                        (s, smethod, sargs) -> {
                                            if (smethod.getName().equals("getServerInfo")) {
                                                return new ServerInfo(name, new InetSocketAddress("127.0.0.1", 25565));
                                            }
                                            return null;
                                        }));
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
        Path directory = Files.createTempDirectory("bbh-group-queue-test");
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
    void testNonLeaderCannotQueueParty() {
        MockPlayer leader = mockProxy.register("Leader");
        MockPlayer member = mockProxy.register("Member");

        PartySnapshot party = plugin.parties().createParty(leader.uuid);
        plugin.parties().invitePlayer(leader.uuid, member.uuid);
        plugin.parties().acceptInvite(member.uuid, party.partyId());

        // Member tries to queue
        plugin.join(member.proxy, gameId);
        assertTrue(member.messages.stream().anyMatch(m -> m.contains("Apenas o líder")));
        assertEquals(0, plugin.queues().size(gameId));
        assertEquals(PartyState.IDLE, plugin.parties().partyOf(leader.uuid).orElseThrow().state());
    }

    @Test
    void testLeaderQueuesEntirePartyAndNotifiesMembers() {
        MockPlayer leader = mockProxy.register("Leader");
        MockPlayer member = mockProxy.register("Member");

        PartySnapshot party = plugin.parties().createParty(leader.uuid);
        plugin.parties().invitePlayer(leader.uuid, member.uuid);
        plugin.parties().acceptInvite(member.uuid, party.partyId());

        // Leader queues
        plugin.join(leader.proxy, gameId);

        assertEquals(1, plugin.queues().size(gameId));
        assertEquals(PartyState.QUEUED, plugin.parties().partyOf(leader.uuid).orElseThrow().state());
        assertTrue(member.messages.stream().anyMatch(m -> m.contains("Sua party entrou na fila para campominado")));

        // Member checks queue status
        QueueStatus status = plugin.queueStatus(member.uuid).toCompletableFuture().join();
        assertTrue(status.game().isPresent());
        assertEquals(gameId, status.game().get());
        assertEquals(1, status.position());
    }

    @Test
    void testNonLeaderCannotDequeueParty() {
        MockPlayer leader = mockProxy.register("Leader");
        MockPlayer member = mockProxy.register("Member");

        PartySnapshot party = plugin.parties().createParty(leader.uuid);
        plugin.parties().invitePlayer(leader.uuid, member.uuid);
        plugin.parties().acceptInvite(member.uuid, party.partyId());

        plugin.join(leader.proxy, gameId);
        assertEquals(PartyState.QUEUED, plugin.parties().partyOf(leader.uuid).orElseThrow().state());

        // Member tries to dequeue
        plugin.leave(member.proxy);
        assertTrue(member.messages.stream().anyMatch(m -> m.contains("Apenas o líder pode retirar a party da fila")));
        assertEquals(1, plugin.queues().size(gameId));
        assertEquals(PartyState.QUEUED, plugin.parties().partyOf(leader.uuid).orElseThrow().state());
    }

    @Test
    void testLeaderDequeuesPartyAndReturnsToIdle() {
        MockPlayer leader = mockProxy.register("Leader");
        MockPlayer member = mockProxy.register("Member");

        PartySnapshot party = plugin.parties().createParty(leader.uuid);
        plugin.parties().invitePlayer(leader.uuid, member.uuid);
        plugin.parties().acceptInvite(member.uuid, party.partyId());

        plugin.join(leader.proxy, gameId);
        assertEquals(PartyState.QUEUED, plugin.parties().partyOf(leader.uuid).orElseThrow().state());

        // Leader leaves queue
        plugin.leave(leader.proxy);
        assertEquals(0, plugin.queues().size(gameId));
        assertEquals(PartyState.IDLE, plugin.parties().partyOf(leader.uuid).orElseThrow().state());
        assertTrue(member.messages.stream().anyMatch(m -> m.contains("Sua party saiu da fila")));
    }

    @Test
    void testMemberDisconnectCancelsPartyQueue() {
        MockPlayer leader = mockProxy.register("Leader");
        MockPlayer member = mockProxy.register("Member");

        PartySnapshot party = plugin.parties().createParty(leader.uuid);
        plugin.parties().invitePlayer(leader.uuid, member.uuid);
        plugin.parties().acceptInvite(member.uuid, party.partyId());

        plugin.join(leader.proxy, gameId);
        assertEquals(PartyState.QUEUED, plugin.parties().partyOf(leader.uuid).orElseThrow().state());

        // Member disconnects
        DisconnectEvent event = new DisconnectEvent(member.proxy, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN);
        plugin.onDisconnect(event);

        assertEquals(0, plugin.queues().size(gameId));
        assertEquals(PartyState.IDLE, plugin.parties().partyOf(leader.uuid).orElseThrow().state());
        assertTrue(leader.messages.stream().anyMatch(m -> m.contains("A party saiu da fila pois um membro desconectou")));
    }

    @Test
    void testAtomicPartyMatchmakingDoesNotSplitParty() {
        MockPlayer leader = mockProxy.register("Leader");
        MockPlayer member1 = mockProxy.register("Member1");
        MockPlayer member2 = mockProxy.register("Member2");

        PartySnapshot party = plugin.parties().createParty(leader.uuid);
        plugin.parties().invitePlayer(leader.uuid, member1.uuid);
        plugin.parties().acceptInvite(member1.uuid, party.partyId());
        plugin.parties().invitePlayer(leader.uuid, member2.uuid);
        plugin.parties().acceptInvite(member2.uuid, party.partyId());
        assertEquals(3, plugin.parties().partyOf(leader.uuid).orElseThrow().size());

        // Register 2 instances:
        // cm-01 has 8/10 (only 2 slots available - NOT enough for party of 3!)
        // cm-02 has 2/10 (8 slots available - ENOUGH for party of 3!)
        ServerId cm1 = ServerId.of("campominado-01");
        ServerId cm2 = ServerId.of("campominado-02");
        Instant now = Instant.now();

        plugin.instanceRegistry().register(new MessagePayloads.InstanceRegister(
                cm1, gameId, "cm-01", UUID.randomUUID(), MessagePayloads.GameStateWire.WAITING, 8, 2, 10, true), 0L, now);
        plugin.instanceRegistry().register(new MessagePayloads.InstanceRegister(
                cm2, gameId, "cm-02", UUID.randomUUID(), MessagePayloads.GameStateWire.WAITING, 2, 2, 10, true), 0L, now);

        // Leader joins queue -> triggers dispatchQueue
        plugin.join(leader.proxy, gameId);

        // Party must be assigned to cm2 together!
        PartySnapshot partyAfter = plugin.parties().partyOf(leader.uuid).orElseThrow();
        assertEquals(PartyState.ASSIGNED, partyAfter.state());

        // All 3 members must have active reservations on cm2
        assertTrue(plugin.reservationService().getActive(leader.uuid).isPresent());
        assertTrue(plugin.reservationService().getActive(member1.uuid).isPresent());
        assertTrue(plugin.reservationService().getActive(member2.uuid).isPresent());

        assertEquals(cm2, plugin.reservationService().getActive(leader.uuid).orElseThrow().instanceId());
        assertEquals(cm2, plugin.reservationService().getActive(member1.uuid).orElseThrow().instanceId());
        assertEquals(cm2, plugin.reservationService().getActive(member2.uuid).orElseThrow().instanceId());
    }

    @Test
    void testAtomicPartyMatchmakingWaitsWhenNoInstanceHasEnoughCapacity() {
        MockPlayer leader = mockProxy.register("Leader");
        MockPlayer member1 = mockProxy.register("Member1");
        MockPlayer member2 = mockProxy.register("Member2");

        PartySnapshot party = plugin.parties().createParty(leader.uuid);
        plugin.parties().invitePlayer(leader.uuid, member1.uuid);
        plugin.parties().acceptInvite(member1.uuid, party.partyId());
        plugin.parties().invitePlayer(leader.uuid, member2.uuid);
        plugin.parties().acceptInvite(member2.uuid, party.partyId());

        // Only cm-01 exists and has 9/10 (only 1 slot available - cannot fit party of 3)
        ServerId cm1 = ServerId.of("campominado-01");
        Instant now = Instant.now();
        plugin.instanceRegistry().register(new MessagePayloads.InstanceRegister(
                cm1, gameId, "cm-01", UUID.randomUUID(), MessagePayloads.GameStateWire.WAITING, 9, 2, 10, true), 0L, now);

        plugin.join(leader.proxy, gameId);

        // Party must remain in queue as an indivisible unit
        assertEquals(1, plugin.queues().size(gameId));
        assertEquals(PartyState.QUEUED, plugin.parties().partyOf(leader.uuid).orElseThrow().state());
        assertTrue(plugin.reservationService().getActive(leader.uuid).isEmpty());
        assertTrue(plugin.reservationService().getActive(member1.uuid).isEmpty());
        assertTrue(plugin.reservationService().getActive(member2.uuid).isEmpty());
    }
}
