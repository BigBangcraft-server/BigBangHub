package com.bigbangcraft.hub.common;

import com.bigbangcraft.hub.api.GameDefinition;
import com.bigbangcraft.hub.api.GameId;
import com.bigbangcraft.hub.api.GameRegistry;
import com.bigbangcraft.hub.api.GameState;
import com.bigbangcraft.hub.api.InstanceHealth;
import com.bigbangcraft.hub.api.InstanceSnapshot;
import com.bigbangcraft.hub.api.RoutingStrategy;
import com.bigbangcraft.hub.api.ServerDefinition;
import com.bigbangcraft.hub.api.ServerId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class InstanceAwareRoutingServiceTest {
    private InMemoryInstanceRegistry instanceRegistry;
    private InMemoryGameRegistry gameRegistry;
    private InMemoryServerRegistry serverRegistry;
    private InstanceAwareRoutingService routingService;

    private final GameId gameId = GameId.of("campominado");

    @BeforeEach
    void setUp() {
        instanceRegistry = new InMemoryInstanceRegistry(new InstanceEventBus());
        GameDefinition gameDef = new GameDefinition(gameId, "Campo Minado", true, true, 2, 10, RoutingStrategy.FILL_WAITING);
        gameRegistry = new InMemoryGameRegistry(List.of(gameDef));
        serverRegistry = new InMemoryServerRegistry(List.of());
        routingService = new InstanceAwareRoutingService(gameRegistry, instanceRegistry, serverRegistry);
    }

    @Test
    void fillWaitingSelectsInstanceWithMostPlayersAndDeterministicTieBreak() {
        ServerId cm1 = ServerId.of("campominado-01");
        ServerId cm2 = ServerId.of("campominado-02");
        ServerId cm3 = ServerId.of("campominado-03");
        Instant now = Instant.now();

        // cm1: 3/10, cm2: 8/10, cm3: 5/10
        instanceRegistry.register(new MessagePayloads.InstanceRegister(cm1, gameId, "cm-01", UUID.randomUUID(), MessagePayloads.GameStateWire.WAITING, 3, 2, 10, true), 0L, now);
        instanceRegistry.register(new MessagePayloads.InstanceRegister(cm2, gameId, "cm-02", UUID.randomUUID(), MessagePayloads.GameStateWire.WAITING, 8, 2, 10, true), 0L, now);
        instanceRegistry.register(new MessagePayloads.InstanceRegister(cm3, gameId, "cm-03", UUID.randomUUID(), MessagePayloads.GameStateWire.WAITING, 5, 2, 10, true), 0L, now);

        // Must pick cm2 (8/10)
        Optional<InstanceSnapshot> selected = routingService.selectInstance(gameId);
        assertTrue(selected.isPresent());
        assertEquals(cm2, selected.get().instanceId());

        // Now test tie break: cm1: 8/10, cm2: 8/10 -> cm1 comes before cm2 lexicographically
        instanceRegistry.register(new MessagePayloads.InstanceRegister(cm1, gameId, "cm-01", UUID.randomUUID(), MessagePayloads.GameStateWire.WAITING, 8, 2, 10, true), 0L, now);
        selected = routingService.selectInstance(gameId);
        assertTrue(selected.isPresent());
        assertEquals(cm1, selected.get().instanceId());
    }

    @Test
    void filtersOutUnhealthyOrFullOrInGameInstances() {
        ServerId cm1 = ServerId.of("campominado-01");
        ServerId cm2 = ServerId.of("campominado-02");
        ServerId cm3 = ServerId.of("campominado-03");
        Instant now = Instant.now();

        // cm1 is IN_GAME
        instanceRegistry.register(new MessagePayloads.InstanceRegister(cm1, gameId, "cm-01", UUID.randomUUID(), MessagePayloads.GameStateWire.IN_GAME, 5, 2, 10, false), 0L, now);
        // cm2 is FULL (10/10)
        instanceRegistry.register(new MessagePayloads.InstanceRegister(cm2, gameId, "cm-02", UUID.randomUUID(), MessagePayloads.GameStateWire.WAITING, 10, 2, 10, true), 0L, now);
        // cm3 is WAITING (2/10)
        instanceRegistry.register(new MessagePayloads.InstanceRegister(cm3, gameId, "cm-03", UUID.randomUUID(), MessagePayloads.GameStateWire.WAITING, 2, 2, 10, true), 0L, now);

        Optional<InstanceSnapshot> selected = routingService.selectInstance(gameId);
        assertTrue(selected.isPresent());
        assertEquals(cm3, selected.get().instanceId());

        // If cm3 times out and becomes UNAVAILABLE
        instanceRegistry.sweepLiveness(20_000_000_000L, 5_000_000_000L, 10_000_000_000L);
        selected = routingService.selectInstance(gameId);
        assertTrue(selected.isEmpty()); // No healthy instance available!
    }

    @Test
    void leastPlayersStrategyBalancesLoad() {
        GameDefinition gameDef = new GameDefinition(gameId, "Campo Minado", true, true, 2, 10, RoutingStrategy.LEAST_PLAYERS);
        gameRegistry = new InMemoryGameRegistry(List.of(gameDef));
        routingService = new InstanceAwareRoutingService(gameRegistry, instanceRegistry, serverRegistry);

        ServerId cm1 = ServerId.of("campominado-01");
        ServerId cm2 = ServerId.of("campominado-02");
        Instant now = Instant.now();

        instanceRegistry.register(new MessagePayloads.InstanceRegister(cm1, gameId, "cm-01", UUID.randomUUID(), MessagePayloads.GameStateWire.WAITING, 6, 2, 10, true), 0L, now);
        instanceRegistry.register(new MessagePayloads.InstanceRegister(cm2, gameId, "cm-02", UUID.randomUUID(), MessagePayloads.GameStateWire.WAITING, 2, 2, 10, true), 0L, now);

        Optional<InstanceSnapshot> selected = routingService.selectInstance(gameId);
        assertTrue(selected.isPresent());
        assertEquals(cm2, selected.get().instanceId()); // Picked 2/10 instead of 6/10
    }
}
