package com.bigbangcraft.hub.common;

import com.bigbangcraft.hub.api.AdmissionTicket;
import com.bigbangcraft.hub.api.GameId;
import com.bigbangcraft.hub.api.MatchDefinition;
import com.bigbangcraft.hub.api.MatchId;
import com.bigbangcraft.hub.api.MatchSnapshot;
import com.bigbangcraft.hub.api.MatchState;
import com.bigbangcraft.hub.api.ParticipantRole;
import com.bigbangcraft.hub.api.ServerId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MatchConcurrencyTest {
    private MatchEventBus eventBus;
    private InMemoryMatchRegistry registry;
    private AdmissionTicketService ticketService;

    private final GameId gameId = GameId.of("campominado");
    private final ServerId instance1 = ServerId.of("campominado-01");
    private final UUID sessionId1 = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        eventBus = new MatchEventBus();
        registry = new InMemoryMatchRegistry(eventBus, Duration.ofSeconds(60));
        ticketService = new AdmissionTicketService(Duration.ofSeconds(10));
    }

    @Test
    void oneHundredThreadsCompetingForLastSlotAllowsExactlyOneAdmission() throws Exception {
        MatchId matchId = MatchId.random();
        // Capacity: 10 players. Fill 9, leave exactly 1 slot!
        MatchDefinition def = MatchDefinition.builder().gameId(gameId).minPlayers(2).maxPlayers(10).build();
        Instant now = Instant.now();
        registry.createMatch(matchId, def, instance1, sessionId1, now);
        registry.transitionState(matchId, sessionId1, 1, MatchState.CREATED, MatchState.WAITING, now);

        for (int i = 0; i < 9; i++) {
            UUID p = UUID.randomUUID();
            AdmissionTicket ticket = ticketService.issue(p, matchId, instance1, ParticipantRole.PLAYER, now, Duration.ofSeconds(10));
            registry.admitPlayer(ticketService.consume(ticket.ticketId(), p, matchId, instance1, ticket.token(), now), now);
        }

        MatchSnapshot snapBefore = registry.find(matchId).orElseThrow();
        assertEquals(9, snapBefore.participantCount());
        assertEquals(1, snapBefore.effectiveCapacity());

        int count = 100;
        ExecutorService executor = Executors.newFixedThreadPool(count);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successReservations = new AtomicInteger(0);
        AtomicInteger failedReservations = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();
                    boolean reserved = registry.reserveAdmission(matchId);
                    if (reserved) {
                        successReservations.incrementAndGet();
                    } else {
                        failedReservations.incrementAndGet();
                    }
                } catch (Exception e) {
                    failedReservations.incrementAndGet();
                }
            }));
        }

        startLatch.countDown();
        for (Future<?> f : futures) f.get();
        executor.shutdown();

        // Exactly 1 thread must have succeeded in reserving the final admission slot!
        assertEquals(1, successReservations.get(), "Expected exactly 1 successful reservation for the final slot");
        assertEquals(99, failedReservations.get(), "Expected 99 failed reservations due to capacity limit");

        MatchSnapshot snapAfter = registry.find(matchId).orElseThrow();
        assertEquals(0, snapAfter.effectiveCapacity(), "Effective capacity must now be 0");
    }

    @Test
    void concurrentMonotonicRevisionUnderConflictingStateTransitions() throws Exception {
        MatchId matchId = MatchId.random();
        MatchDefinition def = MatchDefinition.builder().gameId(gameId).minPlayers(2).maxPlayers(10).build();
        Instant now = Instant.now();
        registry.createMatch(matchId, def, instance1, sessionId1, now);

        int count = 20;
        ExecutorService executor = Executors.newFixedThreadPool(count);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successTransitions = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();
                    // All threads attempt transition CREATED -> WAITING with expected revision 1
                    boolean ok = registry.transitionState(matchId, sessionId1, 1, MatchState.CREATED, MatchState.WAITING, Instant.now());
                    if (ok) successTransitions.incrementAndGet();
                } catch (Exception ignored) { }
            }));
        }

        startLatch.countDown();
        for (Future<?> f : futures) f.get();
        executor.shutdown();

        // Exactly ONE thread succeeds in CAS transition from revision 1 -> 2!
        assertEquals(1, successTransitions.get(), "Expected exactly 1 thread to succeed in CAS transition");
        assertEquals(2, registry.find(matchId).orElseThrow().revision());
        assertEquals(MatchState.WAITING, registry.find(matchId).orElseThrow().state());
    }
}
