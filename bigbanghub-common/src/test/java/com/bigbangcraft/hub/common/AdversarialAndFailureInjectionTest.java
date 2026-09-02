package com.bigbangcraft.hub.common;

import com.bigbangcraft.hub.api.GameDefinition;
import com.bigbangcraft.hub.api.GameId;
import com.bigbangcraft.hub.api.GameState;
import com.bigbangcraft.hub.api.InstanceHealth;
import com.bigbangcraft.hub.api.InstanceSnapshot;
import com.bigbangcraft.hub.api.QueueResult;
import com.bigbangcraft.hub.api.Reservation;
import com.bigbangcraft.hub.api.RoutingStrategy;
import com.bigbangcraft.hub.api.ServerId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AdversarialAndFailureInjectionTest {
    private InstanceEventBus eventBus;
    private InMemoryInstanceRegistry registry;
    private InMemoryReservationService reservations;
    private InMemoryQueueService queues;
    private InstanceAwareRoutingService routing;

    private final GameId gameId = GameId.of("campominado");
    private final ServerId serverId = ServerId.of("campominado-01");

    @BeforeEach
    void setUp() {
        eventBus = new InstanceEventBus();
        registry = new InMemoryInstanceRegistry(eventBus);
        reservations = new InMemoryReservationService(registry, Duration.ofSeconds(10));
        queues = new InMemoryQueueService(new QueueEventBus());

        GameDefinition gameDef = new GameDefinition(gameId, "Campo Minado", true, true, 2, 10, RoutingStrategy.FILL_WAITING);
        InMemoryGameRegistry gameReg = new InMemoryGameRegistry(List.of(gameDef));
        InMemoryServerRegistry serverReg = new InMemoryServerRegistry(List.of());
        routing = new InstanceAwareRoutingService(gameReg, registry, serverReg);
    }

    @Test
    void staleSessionMessagesAreRejectedAndIgnored() {
        UUID oldSession = UUID.randomUUID();
        UUID newSession = UUID.randomUUID();
        Instant now = Instant.now();

        // 1. Initial registration with oldSession
        registry.register(new MessagePayloads.InstanceRegister(
                serverId, gameId, "cm-01", oldSession,
                MessagePayloads.GameStateWire.WAITING, 4, 2, 10, true), 1_000_000_000L, now);

        // 2. Server restarts and registers with newSession
        InMemoryInstanceRegistry.RegisterOutcome outcome = registry.register(new MessagePayloads.InstanceRegister(
                serverId, gameId, "cm-01", newSession,
                MessagePayloads.GameStateWire.WAITING, 0, 2, 10, true), 2_000_000_000L, now.plusSeconds(2));
        assertEquals(InMemoryInstanceRegistry.RegisterOutcome.REPLACED, outcome);

        InstanceSnapshot current = registry.find(serverId).orElseThrow();
        assertEquals(newSession, current.sessionId());
        assertEquals(0, current.playerCount());

        // 3. Stale heartbeat from oldSession
        InMemoryInstanceRegistry.HeartbeatOutcome hb = registry.heartbeat(
                new MessagePayloads.InstanceHeartbeat(serverId, oldSession, MessagePayloads.GameStateWire.WAITING, 9, 10, true),
                3_000_000_000L, now.plusSeconds(3));
        assertEquals(InMemoryInstanceRegistry.HeartbeatOutcome.REJECTED_STALE_SESSION, hb);
        assertEquals(0, registry.find(serverId).orElseThrow().playerCount(), "Player count must NOT be mutated by stale heartbeat");

        // 4. Stale state change from oldSession
        InMemoryInstanceRegistry.StateChangeOutcome sc = registry.updateState(
                new MessagePayloads.InstanceStateChange(serverId, oldSession, MessagePayloads.GameStateWire.IN_GAME, false, 9, 10),
                4_000_000_000L, now.plusSeconds(4));
        assertEquals(InMemoryInstanceRegistry.StateChangeOutcome.REJECTED_STALE_SESSION, sc);
        assertEquals(GameState.WAITING, registry.find(serverId).orElseThrow().state(), "State must NOT be mutated by stale state change");

        // 5. Stale unregister from oldSession
        InMemoryInstanceRegistry.UnregisterOutcome unreg = registry.unregister(serverId, oldSession, "old unregister");
        assertEquals(InMemoryInstanceRegistry.UnregisterOutcome.STALE_SESSION, unreg);
        assertEquals(InstanceHealth.HEALTHY, registry.find(serverId).orElseThrow().health(), "Health must NOT be marked UNAVAILABLE by stale unregister");
    }

    @Test
    void livenessDegradationAndRecoveryCycle() {
        UUID session = UUID.randomUUID();
        Instant now = Instant.now();

        registry.register(new MessagePayloads.InstanceRegister(
                serverId, gameId, "cm-01", session,
                MessagePayloads.GameStateWire.WAITING, 2, 2, 10, true), 0L, now);

        // Healthy: Routing selects it
        assertTrue(routing.selectInstance(gameId).isPresent());

        // Stop sending heartbeats: simulate 6 seconds elapsed (> 5s suspect threshold)
        List<InMemoryInstanceRegistry.LivenessTransition> transitions = registry.sweepLiveness(
                6_000_000_000L, 5_000_000_000L, 10_000_000_000L);
        assertEquals(1, transitions.size());
        assertEquals(InstanceHealth.SUSPECT, transitions.get(0).newHealth());

        // SUSPECT instance must NOT be selected by routing
        assertTrue(routing.selectInstance(gameId).isEmpty());

        // Simulate 12 seconds elapsed (> 10s timeout)
        transitions = registry.sweepLiveness(12_000_000_000L, 5_000_000_000L, 10_000_000_000L);
        assertEquals(1, transitions.size());
        assertEquals(InstanceHealth.UNAVAILABLE, transitions.get(0).newHealth());
        assertTrue(routing.selectInstance(gameId).isEmpty());

        // Backend recovers and sends heartbeat
        InMemoryInstanceRegistry.HeartbeatOutcome hb = registry.heartbeat(
                new MessagePayloads.InstanceHeartbeat(serverId, session, MessagePayloads.GameStateWire.WAITING, 2, 10, true),
                13_000_000_000L, now.plusSeconds(13));
        assertEquals(InMemoryInstanceRegistry.HeartbeatOutcome.ACCEPTED_RECOVERED, hb);

        // Recovered: Routing selects it again!
        assertTrue(routing.selectInstance(gameId).isPresent());
        assertEquals(serverId, routing.selectInstance(gameId).get().instanceId());
    }

    @Test
    void orphanedReservationsCleanedWhenInstanceFails() {
        UUID session = UUID.randomUUID();
        Instant now = Instant.now();

        registry.register(new MessagePayloads.InstanceRegister(
                serverId, gameId, "cm-01", session,
                MessagePayloads.GameStateWire.WAITING, 2, 2, 10, true), 0L, now);

        UUID player = UUID.randomUUID();
        Optional<Reservation> res = reservations.reserve(serverId, player, gameId, now);
        assertTrue(res.isPresent());
        assertEquals(1, reservations.activeCount());

        // Instance dies (> 10s timeout)
        List<InMemoryInstanceRegistry.LivenessTransition> transitions = registry.sweepLiveness(
                12_000_000_000L, 5_000_000_000L, 10_000_000_000L);
        assertEquals(1, transitions.size());
        assertEquals(InstanceHealth.UNAVAILABLE, transitions.get(0).newHealth());
        assertTrue(transitions.get(0).orphanedReservations().contains(res.get().reservationId()));

        // Cleanup orphaned reservations from reservation service
        for (UUID resId : transitions.get(0).orphanedReservations()) {
            reservations.find(resId).ifPresent(r -> reservations.cancel(r.playerId(), "instance died"));
        }
        assertEquals(0, reservations.activeCount());
    }

    @Test
    void queueDispatcherPreservesStrictFifoWhenCapacityBecomesAvailable() throws Exception {
        // 5 players join queue when 0 servers available
        List<UUID> players = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            UUID p = UUID.randomUUID();
            players.add(p);
            QueueResult res = queues.join(p, gameId).toCompletableFuture().get();
            assertEquals(QueueResult.Code.JOINED, res.code());
            assertEquals(i + 1, res.position());
        }
        assertEquals(5, queues.size(gameId));

        // Now an instance becomes available with capacity for 2 players (8/10)
        registry.register(new MessagePayloads.InstanceRegister(
                serverId, gameId, "cm-01", UUID.randomUUID(),
                MessagePayloads.GameStateWire.WAITING, 8, 2, 10, true), 0L, Instant.now());

        // Dispatch players: exactly 2 players should be dispatched
        List<UUID> dispatched = new ArrayList<>();
        while (true) {
            Optional<UUID> next = queues.peekNext(gameId);
            if (next.isEmpty()) break;
            Optional<InstanceSnapshot> inst = routing.selectInstance(gameId);
            if (inst.isEmpty()) break;

            UUID candidate = next.get();
            Optional<Reservation> res = reservations.reserve(inst.get().instanceId(), candidate, gameId, Instant.now());
            if (res.isEmpty()) break;

            queues.assign(candidate, gameId, inst.get().instanceId());
            dispatched.add(candidate);
        }

        // Exactly first 2 players in FIFO order!
        assertEquals(2, dispatched.size());
        assertEquals(players.get(0), dispatched.get(0), "First player in FIFO must be dispatched first");
        assertEquals(players.get(1), dispatched.get(1), "Second player in FIFO must be dispatched second");

        // Remaining 3 players preserve queue order
        assertEquals(3, queues.size(gameId));
        assertEquals(players.get(2), queues.peekNext(gameId).orElseThrow());
    }
}
