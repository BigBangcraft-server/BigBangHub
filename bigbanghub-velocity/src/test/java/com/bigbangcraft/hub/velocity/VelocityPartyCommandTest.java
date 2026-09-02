package com.bigbangcraft.hub.velocity;

import com.bigbangcraft.hub.api.PartyId;
import com.bigbangcraft.hub.api.PartyRole;
import com.bigbangcraft.hub.api.PartySnapshot;
import com.bigbangcraft.hub.common.HubConfigSnapshot;
import com.bigbangcraft.hub.common.InMemoryPartyService;
import com.bigbangcraft.hub.common.PartyEventBus;
import com.bigbangcraft.hub.common.PartySettings;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class VelocityPartyCommandTest {
    private InMemoryPartyService partyService;
    private VelocityPartyCommand partyCommand;
    private MockProxyServer mockProxy;

    @BeforeEach
    void setUp() {
        partyService = new InMemoryPartyService(new PartySettings(4, Duration.ofSeconds(60), Duration.ofSeconds(15), Duration.ofMillis(500)), new PartyEventBus());
        mockProxy = new MockProxyServer();
        partyCommand = new VelocityPartyCommand(partyService, mockProxy.proxy);
    }

    static class MockPlayer {
        final UUID uuid = UUID.randomUUID();
        final String name;
        final List<String> plainMessages = new ArrayList<>();
        final List<Component> adventureMessages = new ArrayList<>();
        final Map<String, Boolean> permissions = new HashMap<>();
        boolean active = true;
        Player proxy;

        MockPlayer(String name) {
            this.name = name;
            permissions.put("bigbanghub.party.use", true);
            permissions.put("bigbanghub.party.invite", true);
            this.proxy = (Player) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{Player.class},
                    (p, method, args) -> {
                        if (method.getName().equals("getUniqueId")) return uuid;
                        if (method.getName().equals("getUsername")) return name;
                        if (method.getName().equals("isActive")) return active;
                        if (method.getName().equals("hasPermission")) return permissions.getOrDefault((String) args[0], false);
                        if (method.getName().equals("sendPlainMessage")) {
                            plainMessages.add((String) args[0]);
                            return null;
                        }
                        if (method.getName().equals("sendMessage") && args[0] instanceof Component c) {
                            adventureMessages.add(c);
                            return null;
                        }
                        return null;
                    });
        }
    }

    static class MockProxyServer {
        final Map<String, MockPlayer> playersByName = new HashMap<>();
        final Map<UUID, MockPlayer> playersById = new HashMap<>();
        final ProxyServer proxy;

        MockProxyServer() {
            this.proxy = (ProxyServer) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{ProxyServer.class},
                    (p, method, args) -> {
                        if (method.getName().equals("getPlayer") && args[0] instanceof String name) {
                            MockPlayer mp = playersByName.get(name.toLowerCase());
                            return mp != null ? Optional.of(mp.proxy) : Optional.empty();
                        }
                        if (method.getName().equals("getPlayer") && args[0] instanceof UUID id) {
                            MockPlayer mp = playersById.get(id);
                            return mp != null ? Optional.of(mp.proxy) : Optional.empty();
                        }
                        if (method.getName().equals("getAllPlayers")) {
                            return playersById.values().stream().map(mp -> mp.proxy).toList();
                        }
                        return null;
                    });
        }

        MockPlayer register(String name) {
            MockPlayer mp = new MockPlayer(name);
            playersByName.put(name.toLowerCase(), mp);
            playersById.put(mp.uuid, mp);
            return mp;
        }
    }

    private SimpleCommand.Invocation createInvocation(MockPlayer sender, String... args) {
        return (SimpleCommand.Invocation) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{SimpleCommand.Invocation.class},
                (proxy, method, a) -> {
                    if (method.getName().equals("source")) return sender.proxy;
                    if (method.getName().equals("arguments")) return args;
                    return null;
                });
    }

    @Test
    void testPartyStatusWhenNotInParty() {
        MockPlayer player = mockProxy.register("Pedro");
        partyCommand.execute(createInvocation(player));

        assertFalse(player.plainMessages.isEmpty());
        assertTrue(player.plainMessages.stream().anyMatch(m -> m.contains("Você não está em uma party")));
    }

    @Test
    void testPartyInviteRequiresPermission() {
        MockPlayer leader = mockProxy.register("Leader");
        leader.permissions.put("bigbanghub.party.invite", false);
        MockPlayer target = mockProxy.register("Target");

        partyCommand.execute(createInvocation(leader, "invite", "Target"));
        assertTrue(leader.plainMessages.stream().anyMatch(m -> m.contains("Você não tem permissão")));
        assertTrue(partyService.activeParties().isEmpty());
    }

    @Test
    void testPartyInviteSelfFails() {
        MockPlayer player = mockProxy.register("Pedro");
        partyCommand.execute(createInvocation(player, "invite", "Pedro"));

        assertTrue(player.plainMessages.stream().anyMatch(m -> m.contains("Você não pode convidar a si mesmo")));
    }

    @Test
    void testPartyInviteCreatesPartyAndSendsInvite() {
        MockPlayer leader = mockProxy.register("Leader");
        MockPlayer target = mockProxy.register("Target");

        partyCommand.execute(createInvocation(leader, "invite", "Target"));

        assertTrue(leader.plainMessages.stream().anyMatch(m -> m.contains("Party criada com sucesso")));
        assertTrue(leader.plainMessages.stream().anyMatch(m -> m.contains("Convite de party enviado")));
        assertFalse(target.adventureMessages.isEmpty());

        Optional<PartySnapshot> party = partyService.partyOf(leader.uuid);
        assertTrue(party.isPresent());
        assertEquals(leader.uuid, party.get().leader());
        assertTrue(party.get().invitedPlayers().containsKey(target.uuid));
    }

    @Test
    void testPartyAcceptAndDecline() {
        MockPlayer leader = mockProxy.register("Leader");
        MockPlayer target = mockProxy.register("Target");

        // Leader invites target
        partyCommand.execute(createInvocation(leader, "invite", "Target"));
        Optional<PartySnapshot> partyOpt = partyService.partyOf(leader.uuid);
        assertTrue(partyOpt.isPresent());

        // Target accepts
        partyCommand.execute(createInvocation(target, "accept", "Leader"));
        assertTrue(target.plainMessages.stream().anyMatch(m -> m.contains("Você entrou na party")));

        PartySnapshot partyAfterAccept = partyService.partyOf(target.uuid).orElseThrow();
        assertEquals(2, partyAfterAccept.size());
        assertTrue(partyAfterAccept.containsMember(target.uuid));

        // Another target decline test
        MockPlayer target2 = mockProxy.register("Target2");
        partyCommand.execute(createInvocation(leader, "invite", "Target2"));
        partyCommand.execute(createInvocation(target2, "decline", "Leader"));
        assertTrue(target2.plainMessages.stream().anyMatch(m -> m.contains("Convite recusado")));
        assertFalse(partyService.partyOf(target2.uuid).isPresent());
    }

    @Test
    void testPartyKickAndTransferLeader() {
        MockPlayer leader = mockProxy.register("Leader");
        MockPlayer member = mockProxy.register("Member");

        partyCommand.execute(createInvocation(leader, "invite", "Member"));
        partyCommand.execute(createInvocation(member, "accept", "Leader"));

        // Non-leader attempts kick
        partyCommand.execute(createInvocation(member, "kick", "Leader"));
        assertTrue(member.plainMessages.stream().anyMatch(m -> m.contains("Apenas o líder")));

        // Transfer leadership
        partyCommand.execute(createInvocation(leader, "leader", "Member"));
        PartySnapshot partyAfterTransfer = partyService.partyOf(leader.uuid).orElseThrow();
        assertEquals(member.uuid, partyAfterTransfer.leader());

        // Now new leader kicks old leader
        partyCommand.execute(createInvocation(member, "kick", "Leader"));
        assertFalse(partyService.partyOf(leader.uuid).isPresent());
        assertTrue(leader.plainMessages.stream().anyMatch(m -> m.contains("Você foi expulso da party")));
    }

    @Test
    void testPartyLeaveAndDisband() {
        MockPlayer leader = mockProxy.register("Leader");
        MockPlayer member = mockProxy.register("Member");

        partyCommand.execute(createInvocation(leader, "invite", "Member"));
        partyCommand.execute(createInvocation(member, "accept", "Leader"));

        // Member leaves
        partyCommand.execute(createInvocation(member, "leave"));
        assertTrue(member.plainMessages.stream().anyMatch(m -> m.contains("Você saiu da party")));
        assertFalse(partyService.partyOf(member.uuid).isPresent());

        // Leader disbands
        partyCommand.execute(createInvocation(leader, "disband"));
        assertFalse(partyService.partyOf(leader.uuid).isPresent());
    }

    @Test
    void testPartyList() {
        MockPlayer leader = mockProxy.register("Leader");
        MockPlayer member = mockProxy.register("Member");

        partyCommand.execute(createInvocation(leader, "invite", "Member"));
        partyCommand.execute(createInvocation(member, "accept", "Leader"));

        partyCommand.execute(createInvocation(leader, "list"));
        assertTrue(leader.plainMessages.stream().anyMatch(m -> m.contains("MEMBROS DA PARTY")));
        assertTrue(leader.plainMessages.stream().anyMatch(m -> m.contains("Leader")));
        assertTrue(leader.plainMessages.stream().anyMatch(m -> m.contains("Member")));
    }

    @Test
    void testPartyRateLimitCooldown() {
        MockPlayer leader = mockProxy.register("Leader");
        MockPlayer target1 = mockProxy.register("Target1");
        MockPlayer target2 = mockProxy.register("Target2");

        partyCommand.execute(createInvocation(leader, "invite", "Target1"));
        // Immediate second invite without waiting for cooldown
        partyCommand.execute(createInvocation(leader, "invite", "Target2"));

        assertTrue(leader.plainMessages.stream().anyMatch(m -> m.contains("Aguarde alguns segundos antes de enviar outro convite")));
    }

    @Test
    void testPartyTabCompletion() {
        MockPlayer leader = mockProxy.register("Leader");
        mockProxy.register("Alice");
        mockProxy.register("Bob");

        // Suggest subcommands
        List<String> subs = partyCommand.suggest(createInvocation(leader, "in"));
        assertTrue(subs.contains("invite"));
        assertFalse(subs.contains("leave"));

        // Suggest players for invite
        List<String> inviteTargets = partyCommand.suggest(createInvocation(leader, "invite", "Al"));
        assertTrue(inviteTargets.contains("Alice"));
        assertFalse(inviteTargets.contains("Bob"));
    }

    @Test
    void testPartyLockedWhenInMatch() {
        MockPlayer leader = mockProxy.register("Leader");
        MockPlayer target = mockProxy.register("Target");

        partyCommand.execute(createInvocation(leader, "invite", "Target"));
        PartySnapshot party = partyService.partyOf(leader.uuid).orElseThrow();

        // Lock party in match
        partyService.transitionState(party.partyId(), com.bigbangcraft.hub.api.PartyState.IN_MATCH);

        // Attempting to invite or leave fails due to locked state
        MockPlayer target2 = mockProxy.register("Target2");
        partyCommand.execute(createInvocation(leader, "invite", "Target2"));
        assertTrue(leader.plainMessages.stream().anyMatch(m -> m.contains("em partida ou na fila")));
    }
}
