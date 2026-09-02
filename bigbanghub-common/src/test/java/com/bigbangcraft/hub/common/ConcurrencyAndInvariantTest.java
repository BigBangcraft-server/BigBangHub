package com.bigbangcraft.hub.common;

import com.bigbangcraft.hub.api.GameId;
import com.bigbangcraft.hub.api.InstanceHealth;
import com.bigbangcraft.hub.api.InstanceSnapshot;
import com.bigbangcraft.hub.api.Reservation;
import com.bigbangcraft.hub.api.ReservationState;
import com.bigbangcraft.hub.api.ServerId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrencyAndInvariantTest {
    private InstanceEventBus eventBus;
    private InMemoryInstanceRegistry registry;
    private InMemoryReservationService reservations;

    private final GameId gameId = GameId.of("bedwars");
    private final ServerId server1 = ServerId.of("bedwars-01");
    private final ServerId server2 = ServerId.of("bedwars-02");
    private final ServerId server3 = ServerId.of("bedwars-03");

    @BeforeEach
    void setUp() {
        eventBus = new InstanceEventBus();
        registry = new InMemoryInstanceRegistry(eventBus);
        reservations = new InMemoryReservationService(registry, Duration.ofSeconds(10));
    }

    @Test
    void oneHundredPlayersCompeteForSingleSlotAllowsExactlyOneWinner() throws Exception {
        // Instance has 9/10 players -> only 1 slot left!
        registry.register(new MessagePayloads.InstanceRegister(
                server1, gameId, "bw-01", UUID.randomUUID(),
                MessagePayloads.GameStateWire.WAITING, 9, 2, 10, true), 0L, Instant.now());

        int count = 100;
        ExecutorService executor = Executors.newFixedThreadPool(count);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        Set<UUID> reservedPlayers = ConcurrentHashMap.newKeySet();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            futures.add(executor.submit(() -> {
                try {
                    latch.await();
                    UUID player = UUID.randomUUID();
                    Optional<Reservation> res = reservations.reserve(server1, player, gameId, Instant.now());
                    if (res.isPresent()) {
                        successCount.incrementAndGet();
                        reservedPlayers.add(player);
                    }
                } catch (InterruptedException ignored) {
                }
            }));
        }

        latch.countDown();
        for (Future<?> f : futures) f.get();
        executor.shutdown();

        // Invariants:
        assertEquals(1, successCount.get(), "Exactly 1 player must win the slot");
        assertEquals(1, reservedPlayers.size(), "Reserved players count must equal 1");
        assertEquals(1, reservations.activeCount(), "Active reservation count must equal 1");

        InstanceSnapshot snap = registry.find(server1).orElseThrow();
        assertEquals(1, snap.activeReservations());
        assertEquals(10, snap.playerCount() + snap.activeReservations(), "Total must not exceed maxPlayers 10");
        assertFalse(snap.canAcceptPlayers(), "Instance must no longer accept players");
    }

    @Test
    void multipleInstancesTotalCapacityInvariantMaintainedUnderHeavyContention() throws Exception {
        // 3 servers: server1 has 5 slots free (5/10), server2 has 3 slots free (7/10), server3 has 2 slots free (8/10)
        // Total available capacity across all instances = 5 + 3 + 2 = 10 slots.
        registry.register(new MessagePayloads.InstanceRegister(server1, gameId, "bw-01", UUID.randomUUID(), MessagePayloads.GameStateWire.WAITING, 5, 2, 10, true), 0L, Instant.now());
        registry.register(new MessagePayloads.InstanceRegister(server2, gameId, "bw-02", UUID.randomUUID(), MessagePayloads.GameStateWire.WAITING, 7, 2, 10, true), 0L, Instant.now());
        registry.register(new MessagePayloads.InstanceRegister(server3, gameId, "bw-03", UUID.randomUUID(), MessagePayloads.GameStateWire.WAITING, 8, 2, 10, true), 0L, Instant.now());

        int count = 100;
        ExecutorService executor = Executors.newFixedThreadPool(count);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger totalSuccess = new AtomicInteger(0);
        List<ServerId> serverList = List.of(server1, server2, server3);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                try {
                    latch.await();
                    UUID player = UUID.randomUUID();
                    ServerId target = serverList.get(idx % 3);
                    if (reservations.reserve(target, player, gameId, Instant.now()).isPresent()) {
                        totalSuccess.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                }
            }));
        }

        latch.countDown();
        for (Future<?> f : futures) f.get();
        executor.shutdown();

        // Invariants:
        InstanceSnapshot s1 = registry.find(server1).orElseThrow();
        InstanceSnapshot s2 = registry.find(server2).orElseThrow();
        InstanceSnapshot s3 = registry.find(server3).orElseThrow();

        assertTrue(s1.playerCount() + s1.activeReservations() <= s1.maxPlayers(), "Server1 invariant violated");
        assertTrue(s2.playerCount() + s2.activeReservations() <= s2.maxPlayers(), "Server2 invariant violated");
        assertTrue(s3.playerCount() + s3.activeReservations() <= s3.maxPlayers(), "Server3 invariant violated");

        assertEquals(totalSuccess.get(), s1.activeReservations() + s2.activeReservations() + s3.activeReservations());
        assertTrue(totalSuccess.get() <= 10, "Total reservations must not exceed total free capacity of 10");
    }

    @Test
    void singlePlayerConcurrentReservationInvariantAllowsAtMostOneActiveReservation() throws Exception {
        // A single player tries to reserve across 10 threads concurrently
        registry.register(new MessagePayloads.InstanceRegister(server1, gameId, "bw-01", UUID.randomUUID(), MessagePayloads.GameStateWire.WAITING, 0, 2, 10, true), 0L, Instant.now());
        registry.register(new MessagePayloads.InstanceRegister(server2, gameId, "bw-02", UUID.randomUUID(), MessagePayloads.GameStateWire.WAITING, 0, 2, 10, true), 0L, Instant.now());

        UUID player = UUID.randomUUID();
        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final ServerId target = (i % 2 == 0) ? server1 : server2;
            futures.add(executor.submit(() -> {
                try {
                    latch.await();
                    if (reservations.reserve(target, player, gameId, Instant.now()).isPresent()) {
                        successCount.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                }
            }));
        }

        latch.countDown();
        for (Future<?> f : futures) f.get();
        executor.shutdown();

        // INVARIANT: Player has at most ONE active reservation
        assertEquals(1, successCount.get(), "Player cannot hold more than 1 active reservation");
        assertTrue(reservations.getActive(player).isPresent());
    }
}
