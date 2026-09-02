package com.bigbangcraft.hub.common;

import com.bigbangcraft.hub.api.GameId;
import com.bigbangcraft.hub.api.Reservation;
import com.bigbangcraft.hub.api.ReservationState;
import com.bigbangcraft.hub.api.ServerId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryReservationServiceTest {
    private InMemoryInstanceRegistry registry;
    private InMemoryReservationService reservations;
    private final ServerId serverId = ServerId.of("campominado-01");
    private final GameId gameId = GameId.of("campominado");

    @BeforeEach
    void setUp() {
        registry = new InMemoryInstanceRegistry(new InstanceEventBus());
        reservations = new InMemoryReservationService(registry, Duration.ofSeconds(10));

        registry.register(new MessagePayloads.InstanceRegister(
                serverId, gameId, "campominado-01", UUID.randomUUID(),
                MessagePayloads.GameStateWire.WAITING, 9, 2, 10, true), 0L, Instant.now());
    }

    @Test
    void reservesAvailableSlotAndRespectsCapacity() {
        UUID player1 = UUID.randomUUID();
        UUID player2 = UUID.randomUUID();
        Instant now = Instant.now();

        // 9/10 capacity -> 1 slot available
        Optional<Reservation> res1 = reservations.reserve(serverId, player1, gameId, now);
        assertTrue(res1.isPresent());
        assertEquals(ReservationState.RESERVED, res1.get().state());
        assertEquals(1, reservations.activeCount());

        // 2nd player should be rejected because capacity (9 + 1 active reservation = 10) is exhausted
        Optional<Reservation> res2 = reservations.reserve(serverId, player2, gameId, now);
        assertTrue(res2.isEmpty());
    }

    @Test
    void playerCannotHaveTwoActiveReservations() {
        // Change capacity to 10 so slots are available
        registry.register(new MessagePayloads.InstanceRegister(
                serverId, gameId, "campominado-01", UUID.randomUUID(),
                MessagePayloads.GameStateWire.WAITING, 0, 2, 10, true), 0L, Instant.now());

        UUID player = UUID.randomUUID();
        Instant now = Instant.now();

        Optional<Reservation> res1 = reservations.reserve(serverId, player, gameId, now);
        assertTrue(res1.isPresent());

        // Duplicate reservation for same player
        Optional<Reservation> res2 = reservations.reserve(serverId, player, gameId, now);
        assertTrue(res2.isEmpty());
    }

    @Test
    void confirmingReservationFreesActiveMapAndKeepsRegistryConsistent() {
        UUID player = UUID.randomUUID();
        Instant now = Instant.now();

        Optional<Reservation> res = reservations.reserve(serverId, player, gameId, now);
        assertTrue(res.isPresent());

        boolean confirmed = reservations.confirm(player, serverId);
        assertTrue(confirmed);

        // Player no longer in active reservations
        assertTrue(reservations.getActive(player).isEmpty());
        assertEquals(0, reservations.activeCount());
    }

    @Test
    void sweepExpiredFreesSlot() {
        UUID player = UUID.randomUUID();
        Instant now = Instant.now();

        Optional<Reservation> res = reservations.reserve(serverId, player, gameId, now);
        assertTrue(res.isPresent());

        // At now + 5s (before 10s TTL) -> not expired
        List<Reservation> expired = reservations.sweepExpired(now.plusSeconds(5));
        assertTrue(expired.isEmpty());
        assertEquals(1, reservations.activeCount());

        // At now + 11s (> 10s TTL) -> expired!
        expired = reservations.sweepExpired(now.plusSeconds(11));
        assertEquals(1, expired.size());
        assertEquals(ReservationState.EXPIRED, expired.get(0).state());
        assertEquals(0, reservations.activeCount());

        // Slot should be free again!
        UUID player2 = UUID.randomUUID();
        Optional<Reservation> res2 = reservations.reserve(serverId, player2, gameId, now.plusSeconds(12));
        assertTrue(res2.isPresent());
    }

    @Test
    void concurrentReservationForLastSlotAllowsExactlyOneWinner() throws Exception {
        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();
        Instant now = Instant.now();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();
                    UUID player = UUID.randomUUID();
                    if (reservations.reserve(serverId, player, gameId, now).isPresent()) {
                        successCount.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                }
            }));
        }

        startLatch.countDown();
        for (Future<?> f : futures) f.get();
        executor.shutdown();

        // Exactly 1 thread must have succeeded!
        assertEquals(1, successCount.get());
        assertEquals(1, reservations.activeCount());
    }
}
