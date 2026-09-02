package com.bigbangcraft.hub.common;

import com.bigbangcraft.hub.api.GameId;
import com.bigbangcraft.hub.api.GameState;
import com.bigbangcraft.hub.api.InstanceHealth;
import com.bigbangcraft.hub.api.InstanceSnapshot;
import com.bigbangcraft.hub.api.ServerId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryInstanceRegistryTest {
    private InstanceEventBus eventBus;
    private InMemoryInstanceRegistry registry;

    @BeforeEach
    void setUp() {
        eventBus = new InstanceEventBus();
        registry = new InMemoryInstanceRegistry(eventBus);
    }

    @Test
    void registersNewInstanceSuccessfully() {
        ServerId id = ServerId.of("campominado-01");
        GameId game = GameId.of("campominado");
        UUID session = UUID.randomUUID();
        Instant now = Instant.now();

        MessagePayloads.InstanceRegister register = new MessagePayloads.InstanceRegister(
                id, game, "campominado-01", session,
                MessagePayloads.GameStateWire.WAITING, 0, 2, 10, true);

        InMemoryInstanceRegistry.RegisterOutcome outcome = registry.register(register, 1_000_000_000L, now);
        assertEquals(InMemoryInstanceRegistry.RegisterOutcome.CREATED, outcome);

        InstanceSnapshot snapshot = registry.find(id).orElseThrow();
        assertEquals(id, snapshot.instanceId());
        assertEquals(game, snapshot.gameId());
        assertEquals(session, snapshot.sessionId());
        assertEquals(GameState.WAITING, snapshot.state());
        assertEquals(InstanceHealth.HEALTHY, snapshot.health());
        assertEquals(0, snapshot.playerCount());
        assertEquals(10, snapshot.maxPlayers());
        assertTrue(snapshot.canAcceptPlayers());
    }

    @Test
    void newSessionReplacesOldSessionAndClearsOldReservations() {
        ServerId id = ServerId.of("campominado-01");
        GameId game = GameId.of("campominado");
        UUID session1 = UUID.randomUUID();
        Instant now = Instant.now();

        registry.register(new MessagePayloads.InstanceRegister(
                id, game, "campominado-01", session1,
                MessagePayloads.GameStateWire.WAITING, 5, 2, 10, true), 1_000_000_000L, now);

        UUID resId = UUID.randomUUID();
        assertTrue(registry.reserveSlot(id, resId));
        assertEquals(1, registry.find(id).orElseThrow().activeReservations());

        // Restart with new session
        UUID session2 = UUID.randomUUID();
        InMemoryInstanceRegistry.RegisterOutcome outcome = registry.register(new MessagePayloads.InstanceRegister(
                id, game, "campominado-01", session2,
                MessagePayloads.GameStateWire.WAITING, 0, 2, 10, true), 2_000_000_000L, now.plusSeconds(5));

        assertEquals(InMemoryInstanceRegistry.RegisterOutcome.REPLACED, outcome);
        InstanceSnapshot snapshot = registry.find(id).orElseThrow();
        assertEquals(session2, snapshot.sessionId());
        assertEquals(0, snapshot.playerCount());
        assertEquals(0, snapshot.activeReservations()); // Old reservations cleared!

        // Heartbeat with old session MUST be rejected
        InMemoryInstanceRegistry.HeartbeatOutcome hbOutcome = registry.heartbeat(
                new MessagePayloads.InstanceHeartbeat(id, session1, MessagePayloads.GameStateWire.WAITING, 5, 10, true),
                2_500_000_000L, now.plusSeconds(6));
        assertEquals(InMemoryInstanceRegistry.HeartbeatOutcome.REJECTED_STALE_SESSION, hbOutcome);
    }

    @Test
    void livenessSweepDegradesSuspectAndUnavailable() {
        ServerId id = ServerId.of("campominado-01");
        GameId game = GameId.of("campominado");
        UUID session = UUID.randomUUID();
        Instant now = Instant.now();

        registry.register(new MessagePayloads.InstanceRegister(
                id, game, "campominado-01", session,
                MessagePayloads.GameStateWire.WAITING, 0, 2, 10, true), 0L, now);

        // Within 3 seconds -> still HEALTHY
        List<InMemoryInstanceRegistry.LivenessTransition> transitions = registry.sweepLiveness(
                2_000_000_000L, 5_000_000_000L, 10_000_000_000L);
        assertTrue(transitions.isEmpty());
        assertEquals(InstanceHealth.HEALTHY, registry.find(id).orElseThrow().health());

        // At 6 seconds (> 5s suspect threshold) -> SUSPECT
        transitions = registry.sweepLiveness(6_000_000_000L, 5_000_000_000L, 10_000_000_000L);
        assertEquals(1, transitions.size());
        assertEquals(InstanceHealth.SUSPECT, transitions.get(0).newHealth());
        assertEquals(InstanceHealth.SUSPECT, registry.find(id).orElseThrow().health());

        // At 11 seconds (> 10s timeout) -> UNAVAILABLE
        transitions = registry.sweepLiveness(11_000_000_000L, 5_000_000_000L, 10_000_000_000L);
        assertEquals(1, transitions.size());
        assertEquals(InstanceHealth.UNAVAILABLE, transitions.get(0).newHealth());
        assertEquals(InstanceHealth.UNAVAILABLE, registry.find(id).orElseThrow().health());
        assertFalse(registry.find(id).orElseThrow().canAcceptPlayers());

        // Heartbeat arrives -> recovers to HEALTHY!
        InMemoryInstanceRegistry.HeartbeatOutcome hbOutcome = registry.heartbeat(
                new MessagePayloads.InstanceHeartbeat(id, session, MessagePayloads.GameStateWire.WAITING, 0, 10, true),
                12_000_000_000L, now.plusSeconds(12));
        assertEquals(InMemoryInstanceRegistry.HeartbeatOutcome.ACCEPTED_RECOVERED, hbOutcome);
        assertEquals(InstanceHealth.HEALTHY, registry.find(id).orElseThrow().health());
        assertTrue(registry.find(id).orElseThrow().canAcceptPlayers());
    }

    @Test
    void unregisterMarksUnavailableAndClearsReservations() {
        ServerId id = ServerId.of("campominado-01");
        GameId game = GameId.of("campominado");
        UUID session = UUID.randomUUID();
        Instant now = Instant.now();

        registry.register(new MessagePayloads.InstanceRegister(
                id, game, "campominado-01", session,
                MessagePayloads.GameStateWire.WAITING, 0, 2, 10, true), 0L, now);

        UUID resId = UUID.randomUUID();
        assertTrue(registry.reserveSlot(id, resId));

        InMemoryInstanceRegistry.UnregisterOutcome outcome = registry.unregister(id, session, "stopping");
        assertEquals(InMemoryInstanceRegistry.UnregisterOutcome.SUCCESS, outcome);

        InstanceSnapshot snapshot = registry.find(id).orElseThrow();
        assertEquals(InstanceHealth.UNAVAILABLE, snapshot.health());
        assertFalse(snapshot.acceptingPlayers());
        assertEquals(0, snapshot.activeReservations());
    }
}
