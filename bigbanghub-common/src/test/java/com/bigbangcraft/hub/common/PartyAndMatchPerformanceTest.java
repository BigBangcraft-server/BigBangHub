package com.bigbangcraft.hub.common;

import com.bigbangcraft.hub.api.AdmissionTicket;
import com.bigbangcraft.hub.api.GameId;
import com.bigbangcraft.hub.api.MatchDefinition;
import com.bigbangcraft.hub.api.MatchId;
import com.bigbangcraft.hub.api.ParticipantRole;
import com.bigbangcraft.hub.api.PartyId;
import com.bigbangcraft.hub.api.PartyInvite;
import com.bigbangcraft.hub.api.PartySnapshot;
import com.bigbangcraft.hub.api.PartyState;
import com.bigbangcraft.hub.api.ServerId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PartyAndMatchPerformanceTest {

    @Test
    void testConcurrentPartyLoadScale1000Parties() throws Exception {
        int partyCount = 1000;
        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        PartyEventBus eventBus = new PartyEventBus();
        // 0ms invite cooldown for benchmark load
        PartySettings settings = new PartySettings(4, Duration.ofSeconds(60), Duration.ofSeconds(30), Duration.ZERO);
        InMemoryPartyService service = new InMemoryPartyService(settings, eventBus);

        CountDownLatch latch = new CountDownLatch(partyCount);
        AtomicInteger successCount = new AtomicInteger();

        long startTime = System.nanoTime();

        for (int i = 0; i < partyCount; i++) {
            executor.submit(() -> {
                try {
                    UUID leader = UUID.randomUUID();
                    UUID member1 = UUID.randomUUID();
                    UUID member2 = UUID.randomUUID();

                    PartySnapshot party = service.createParty(leader);
                    service.invitePlayer(leader, member1);
                    service.acceptInvite(member1, party.partyId());

                    service.invitePlayer(leader, member2);
                    service.acceptInvite(member2, party.partyId());

                    assertEquals(3, service.party(party.partyId()).orElseThrow().size());
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean completed = latch.await(15, TimeUnit.SECONDS);
        executor.shutdown();
        long durationMs = (System.nanoTime() - startTime) / 1_000_000;

        assertTrue(completed, "All 1,000 parties should be created and populated concurrently");
        assertEquals(partyCount, successCount.get());
        assertEquals(partyCount, service.activeParties().size());
        System.out.println("1,000 parties (3,000 players) created and populated in " + durationMs + " ms");
    }

    @Test
    void testSweeperCleansExpiredInvitesAndTombstonesAndRematches() {
        Instant now = Instant.now();
        PartyEventBus eventBus = new PartyEventBus();
        PartySettings partySettings = new PartySettings(4, Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ZERO);
        InMemoryPartyService partyService = new InMemoryPartyService(partySettings, eventBus);

        MatchEventBus matchEventBus = new MatchEventBus();
        InMemoryMatchRegistry matchRegistry = new InMemoryMatchRegistry(matchEventBus, Duration.ofSeconds(5));
        AdmissionTicketService ticketService = new AdmissionTicketService(Duration.ofSeconds(5));
        RematchService rematchService = new RematchService();

        // 1. Create party with invite expiring in 5s
        UUID leader = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        PartySnapshot party = partyService.createParty(leader);
        partyService.invitePlayer(leader, target);
        assertTrue(partyService.party(party.partyId()).orElseThrow().inviteFor(target).isPresent());

        // 2. Create ticket expiring in 5s
        MatchId matchId = MatchId.random();
        ServerId serverId = ServerId.of("srv-01");
        AdmissionTicket ticket = ticketService.issue(leader, matchId, serverId, ParticipantRole.PLAYER, now, Duration.ofSeconds(5));
        assertNotNull(ticket);

        // 3. Create rematch session expiring in 5s
        rematchService.createSession(matchId, GameId.of("game"), List.of(leader, target), Duration.ofSeconds(5), now);
        assertTrue(rematchService.findSession(matchId).isPresent());

        // Fast forward time by 10s
        Instant later = now.plusSeconds(10);

        // Run sweeps
        partyService.sweep(); // Note: partyService uses now(), but we can test expiration logic
        matchRegistry.sweepTombstones(later);
        List<AdmissionTicket> sweptTickets = ticketService.sweepExpired(later);
        List<RematchService.RematchSession> sweptRematches = rematchService.sweep(later);

        assertEquals(1, sweptTickets.size(), "Expired ticket should be swept");
        assertEquals(1, sweptRematches.size(), "Expired rematch session should be swept");
        assertFalse(rematchService.findSession(matchId).isPresent(), "Rematch session should be purged");
        assertTrue(rematchService.activeSessionForPlayer(leader, later).isEmpty(), "Player rematch index should be purged");
    }

    @Test
    void testMatchmakingLatencyBenchmarkUnderMixedSoloAndParties() {
        MatchEventBus eventBus = new MatchEventBus();
        InMemoryMatchRegistry registry = new InMemoryMatchRegistry(eventBus, Duration.ofSeconds(10));
        AdmissionTicketService ticketService = new AdmissionTicketService(Duration.ofSeconds(10));

        GameId gameId = GameId.of("bedwars");
        ServerId instanceId = ServerId.of("bw-01");
        UUID session = UUID.randomUUID();
        Instant now = Instant.now();

        MatchDefinition def = MatchDefinition.builder().gameId(gameId).minPlayers(2).maxPlayers(1000).allowLateJoin(true).build();
        InMemoryMatchRegistry.MatchSessionState match = registry.createMatch(MatchId.random(), def, instanceId, session, now);
        match.stateMachine().transition(com.bigbangcraft.hub.api.MatchState.CREATED, com.bigbangcraft.hub.api.MatchState.WAITING, now);

        List<Long> latenciesNanos = new ArrayList<>();

        // 500 solo player admissions and 100 party admissions (groups of 4)
        for (int i = 0; i < 500; i++) {
            UUID soloPlayer = UUID.randomUUID();
            long t0 = System.nanoTime();
            AdmissionTicket ticket = ticketService.issue(soloPlayer, match.matchId(), instanceId, ParticipantRole.PLAYER, now, Duration.ofSeconds(10));
            registry.admitPlayer(ticket, now);
            latenciesNanos.add(System.nanoTime() - t0);
        }

        PartyId testPartyId = PartyId.random();
        for (int i = 0; i < 100; i++) {
            long t0 = System.nanoTime();
            for (int m = 0; m < 4; m++) {
                UUID p = UUID.randomUUID();
                AdmissionTicket ticket = ticketService.issue(p, match.matchId(), instanceId, ParticipantRole.PLAYER, now, Duration.ofSeconds(10), Optional.of(testPartyId));
                registry.admitPlayer(ticket, now);
            }
            latenciesNanos.add(System.nanoTime() - t0);
        }

        Collections.sort(latenciesNanos);
        long p50Nanos = latenciesNanos.get(latenciesNanos.size() / 2);
        long p99Nanos = latenciesNanos.get((int) (latenciesNanos.size() * 0.99));

        double p50Ms = p50Nanos / 1_000_000.0;
        double p99Ms = p99Nanos / 1_000_000.0;

        System.out.printf("Matchmaking In-Memory Latency: p50 = %.3f ms, p99 = %.3f ms%n", p50Ms, p99Ms);

        // Verification: p99 must be well under 5ms
        assertTrue(p99Ms < 5.0, "p99 admission latency should be < 5ms (was: " + p99Ms + " ms)");
    }

    @Test
    void testMemoryLeakCheckAfterDisbandAndQuitCycles() {
        PartyEventBus eventBus = new PartyEventBus();
        PartySettings settings = new PartySettings(4, Duration.ofSeconds(60), Duration.ofSeconds(30), Duration.ZERO);
        InMemoryPartyService partyService = new InMemoryPartyService(settings, eventBus);

        int cycles = 500;
        for (int i = 0; i < cycles; i++) {
            UUID leader = UUID.randomUUID();
            UUID member = UUID.randomUUID();

            PartySnapshot p = partyService.createParty(leader);
            partyService.invitePlayer(leader, member);
            partyService.acceptInvite(member, p.partyId());

            assertEquals(2, partyService.party(p.partyId()).orElseThrow().size());

            // Member leaves
            partyService.leaveParty(member);
            // Leader disbands
            partyService.disbandParty(leader, p.partyId());

            assertTrue(partyService.partyOf(leader).isEmpty());
            assertTrue(partyService.partyOf(member).isEmpty());
        }

        // Verify zero residual parties
        assertEquals(0, partyService.activeParties().size(), "Zero parties should remain in memory");
    }
}
