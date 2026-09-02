package com.bigbangcraft.hub.common;

import com.bigbangcraft.hub.api.GameId;
import com.bigbangcraft.hub.api.GameState;
import com.bigbangcraft.hub.api.ServerDefinition;
import com.bigbangcraft.hub.api.ServerId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InMemoryServerRegistryTest {
    @Test
    void capacityReservationIsAtomic() throws Exception {
        ServerId id = ServerId.of("campominado");
        var registry = new InMemoryServerRegistry(List.of(new ServerDefinition(id, GameId.of("campominado"),
                "10.8.0.2", 25567, GameState.WAITING, 0, 10)));
        var pool = Executors.newFixedThreadPool(8);
        List<java.util.concurrent.Future<Boolean>> attempts = new ArrayList<>();
        for (int i = 0; i < 32; i++) attempts.add(pool.submit(() -> registry.reserve(id)));
        long successful = 0;
        for (var attempt : attempts) if (attempt.get()) successful++;
        pool.shutdownNow();

        assertEquals(10, successful);
        assertEquals(10, registry.find(id).orElseThrow().playerCount());
    }
}
