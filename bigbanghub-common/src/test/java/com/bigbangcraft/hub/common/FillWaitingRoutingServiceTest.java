package com.bigbangcraft.hub.common;

import com.bigbangcraft.hub.api.GameDefinition;
import com.bigbangcraft.hub.api.GameId;
import com.bigbangcraft.hub.api.GameState;
import com.bigbangcraft.hub.api.RoutingStrategy;
import com.bigbangcraft.hub.api.ServerDefinition;
import com.bigbangcraft.hub.api.ServerId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FillWaitingRoutingServiceTest {
    private final GameId gameId = GameId.of("campominado");

    @Test
    void choosesFullestWaitingServerAndBreaksTiesById() {
        GameDefinition game = new GameDefinition(gameId, "Campo Minado", true, true, 2, 10,
                RoutingStrategy.FILL_WAITING);
        ServerDefinition low = server("campominado-01", 3, GameState.WAITING);
        ServerDefinition high = server("campominado-02", 8, GameState.WAITING);
        ServerDefinition unavailable = server("campominado-03", 9, GameState.IN_GAME);
        var routing = new FillWaitingRoutingService(new InMemoryGameRegistry(List.of(game)),
                new InMemoryServerRegistry(List.of(low, high, unavailable)));

        assertEquals(high.id(), routing.select(gameId).orElseThrow().id());
    }

    @Test
    void returnsEmptyWhenThereIsNoEligibleInstance() {
        GameDefinition game = new GameDefinition(gameId, "Campo Minado", true, true, 2, 10,
                RoutingStrategy.FILL_WAITING);
        var routing = new FillWaitingRoutingService(new InMemoryGameRegistry(List.of(game)),
                new InMemoryServerRegistry(List.of(server("campominado-01", 10, GameState.FULL))));

        assertTrue(routing.select(gameId).isEmpty());
    }

    private ServerDefinition server(String id, int players, GameState state) {
        return new ServerDefinition(ServerId.of(id), gameId, "10.8.0.2", 25567, state, players, 10);
    }
}
