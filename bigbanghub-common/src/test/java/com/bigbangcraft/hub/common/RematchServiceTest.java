package com.bigbangcraft.hub.common;

import com.bigbangcraft.hub.api.GameId;
import com.bigbangcraft.hub.api.MatchId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RematchServiceTest {
    private RematchService service;
    private MatchId matchId;
    private GameId gameId;
    private UUID p1;
    private UUID p2;

    @BeforeEach
    void setUp() {
        service = new RematchService();
        matchId = MatchId.random();
        gameId = GameId.of("campominado");
        p1 = UUID.randomUUID();
        p2 = UUID.randomUUID();
    }

    @Test
    void testSessionCreationAndVotingConsensus() {
        Instant now = Instant.now();
        RematchService.RematchSession session = service.createSession(matchId, gameId, List.of(p1, p2), Duration.ofSeconds(15), now);
        assertEquals(matchId, session.matchId());
        assertEquals(2, session.eligiblePlayers().size());

        // First vote
        Optional<RematchService.RematchVoteResult> r1 = service.vote(p1, now);
        assertTrue(r1.isPresent());
        assertEquals(1, r1.get().currentVotes());
        assertEquals(2, r1.get().requiredVotes());
        assertFalse(r1.get().consensusReached());

        // Second vote -> consensus reached!
        Optional<RematchService.RematchVoteResult> r2 = service.vote(p2, now);
        assertTrue(r2.isPresent());
        assertEquals(2, r2.get().currentVotes());
        assertEquals(2, r2.get().requiredVotes());
        assertTrue(r2.get().consensusReached());

        // Once consensus reached, session is closed
        assertTrue(service.activeSessionForPlayer(p1, now).isEmpty());
        assertTrue(service.activeSessionForPlayer(p2, now).isEmpty());
    }

    @Test
    void testSessionExpirationAndSweep() {
        Instant now = Instant.now();
        service.createSession(matchId, gameId, List.of(p1, p2), Duration.ofSeconds(15), now);

        // Before expiration
        assertTrue(service.activeSessionForPlayer(p1, now).isPresent());

        // Advance past expiration
        Instant future = now.plusSeconds(16);
        List<RematchService.RematchSession> swept = service.sweep(future);
        assertEquals(1, swept.size());
        assertEquals(matchId, swept.get(0).matchId());

        assertTrue(service.activeSessionForPlayer(p1, future).isEmpty());
    }

    @Test
    void testRemovePlayer() {
        Instant now = Instant.now();
        service.createSession(matchId, gameId, List.of(p1, p2), Duration.ofSeconds(15), now);

        service.removePlayer(p1);
        assertTrue(service.activeSessionForPlayer(p1, now).isEmpty());
        assertTrue(service.activeSessionForPlayer(p2, now).isPresent());
    }
}
