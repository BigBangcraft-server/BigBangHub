package com.bigbangcraft.hub.common;

import com.bigbangcraft.hub.api.GameId;
import com.bigbangcraft.hub.api.QueueResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryQueueServiceTest {
    private final GameId game = GameId.of("campominado");

    @Test
    void joinIsIdempotentAndLeaveCleansMembership() {
        InMemoryQueueService queues = new InMemoryQueueService(new QueueEventBus());
        UUID player = UUID.randomUUID();

        assertEquals(QueueResult.Code.JOINED, queues.join(player, game).toCompletableFuture().join().code());
        assertEquals(QueueResult.Code.ALREADY_QUEUED, queues.join(player, game).toCompletableFuture().join().code());
        assertEquals(1, queues.size(game));
        assertTrue(queues.contains(player));
        assertEquals(QueueResult.Code.LEFT, queues.leave(player).toCompletableFuture().join().code());
        assertEquals(0, queues.size(game));
        assertTrue(!queues.contains(player));
    }

    @Test
    void concurrentJoinsKeepOneMembershipPerPlayer() throws Exception {
        InMemoryQueueService queues = new InMemoryQueueService(new QueueEventBus());
        var pool = Executors.newFixedThreadPool(8);
        var ready = new CountDownLatch(1);
        List<UUID> players = new ArrayList<>();
        List<java.util.concurrent.Future<QueueResult>> futures = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            UUID player = UUID.randomUUID();
            players.add(player);
            futures.add(pool.submit(() -> {
                ready.await();
                return queues.join(player, game).toCompletableFuture().join();
            }));
        }
        ready.countDown();
        for (var future : futures) assertEquals(QueueResult.Code.JOINED, future.get().code());
        pool.shutdownNow();
        assertEquals(players.size(), queues.size(game));
    }
}
