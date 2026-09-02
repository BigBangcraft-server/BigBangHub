package com.bigbangcraft.hub.common;

import com.bigbangcraft.hub.api.AdmissionTicket;
import com.bigbangcraft.hub.api.GameId;
import com.bigbangcraft.hub.api.MatchDefinition;
import com.bigbangcraft.hub.api.MatchEvent;
import com.bigbangcraft.hub.api.MatchException;
import com.bigbangcraft.hub.api.MatchFinishedEvent;
import com.bigbangcraft.hub.api.MatchId;
import com.bigbangcraft.hub.api.MatchParticipant;
import com.bigbangcraft.hub.api.MatchResult;
import com.bigbangcraft.hub.api.MatchSnapshot;
import com.bigbangcraft.hub.api.MatchState;
import com.bigbangcraft.hub.api.MatchStateChangedEvent;
import com.bigbangcraft.hub.api.ParticipantRole;
import com.bigbangcraft.hub.api.ParticipantState;
import com.bigbangcraft.hub.api.PlayerEliminatedEvent;
import com.bigbangcraft.hub.api.ServerId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MatchLifecycleTest {
    private MatchEventBus eventBus;
    private InMemoryMatchRegistry registry;
    private AdmissionTicketService ticketService;
    private final List<MatchEvent> receivedEvents = new ArrayList<>();

    private final GameId gameId = GameId.of("campominado");
    private final ServerId instance1 = ServerId.of("campominado-01");
    private final UUID sessionId1 = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        eventBus = new MatchEventBus();
        eventBus.add(receivedEvents::add);
        registry = new InMemoryMatchRegistry(eventBus, Duration.ofSeconds(60));
        ticketService = new AdmissionTicketService(Duration.ofSeconds(10));
    }

    @Test
    void testFullValidMatchLifecycle() {
        MatchId matchId = MatchId.random();
        MatchDefinition def = MatchDefinition.builder()
                .gameId(gameId)
                .minPlayers(2)
                .maxPlayers(4)
                .arenaId("arena_alpha")
                .build();

        Instant now = Instant.now();
        InMemoryMatchRegistry.MatchSessionState state = registry.createMatch(matchId, def, instance1, sessionId1, now);
        assertEquals(MatchState.CREATED, state.stateMachine().state());
        assertEquals(1, state.stateMachine().revision());
        assertEquals(1, receivedEvents.size()); // MatchCreatedEvent

        // CREATED -> WAITING
        boolean ok = registry.transitionState(matchId, sessionId1, 1, MatchState.CREATED, MatchState.WAITING, now);
        assertTrue(ok);
        assertEquals(MatchState.WAITING, state.stateMachine().state());
        assertEquals(2, state.stateMachine().revision());

        // Admit players
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        AdmissionTicket t1 = ticketService.issue(p1, matchId, instance1, ParticipantRole.PLAYER, now, Duration.ofSeconds(10));
        AdmissionTicket t2 = ticketService.issue(p2, matchId, instance1, ParticipantRole.PLAYER, now, Duration.ofSeconds(10));

        AdmissionTicket consumed1 = ticketService.consume(t1.ticketId(), p1, matchId, instance1, t1.token(), now);
        AdmissionTicket consumed2 = ticketService.consume(t2.ticketId(), p2, matchId, instance1, t2.token(), now);

        registry.admitPlayer(consumed1, now);
        registry.admitPlayer(consumed2, now);

        MatchSnapshot snap = registry.find(matchId).orElseThrow();
        assertEquals(2, snap.participantCount());
        assertEquals(2, snap.effectiveCapacity());

        // WAITING -> COUNTDOWN
        ok = registry.transitionState(matchId, sessionId1, 2, MatchState.WAITING, MatchState.COUNTDOWN, now);
        assertTrue(ok);
        assertEquals(MatchState.COUNTDOWN, state.stateMachine().state());

        // COUNTDOWN -> LOCKED
        ok = registry.transitionState(matchId, sessionId1, 3, MatchState.COUNTDOWN, MatchState.LOCKED, now);
        assertTrue(ok);
        assertEquals(MatchState.LOCKED, state.stateMachine().state());

        // LOCKED -> IN_GAME
        ok = registry.transitionState(matchId, sessionId1, 4, MatchState.LOCKED, MatchState.IN_GAME, now);
        assertTrue(ok);
        assertEquals(MatchState.IN_GAME, state.stateMachine().state());
        assertTrue(snap.startedAt().isEmpty()); // old snapshot
        assertTrue(registry.find(matchId).orElseThrow().startedAt().isPresent());

        // Eliminate p1
        registry.eliminatePlayer(matchId, p1, now);
        MatchParticipant part1 = state.participant(p1).orElseThrow();
        assertEquals(ParticipantState.ELIMINATED, part1.state());

        // Finish match with p2 as winner
        MatchResult result = MatchResult.singleWinner(p2, Duration.ofSeconds(45));
        ok = registry.finishMatch(matchId, sessionId1, 5, result, now);
        assertTrue(ok);
        assertEquals(MatchState.FINISHED, state.stateMachine().state());
        assertTrue(registry.find(matchId).orElseThrow().endedAt().isPresent());

        // Instance cleanup handshake
        assertTrue(registry.findActiveForInstance(instance1).isPresent());
        boolean ready = registry.markInstanceReady(instance1, matchId, now);
        assertTrue(ready);
        assertTrue(registry.findActiveForInstance(instance1).isEmpty());
    }

    @Test
    void testInvalidTransitionsRejected() {
        MatchId matchId = MatchId.random();
        MatchDefinition def = MatchDefinition.builder().gameId(gameId).minPlayers(2).maxPlayers(4).build();
        Instant now = Instant.now();
        registry.createMatch(matchId, def, instance1, sessionId1, now);

        // Cannot skip directly from CREATED to IN_GAME (returns false)
        assertFalse(registry.transitionState(matchId, sessionId1, 1, MatchState.CREATED, MatchState.IN_GAME, now));

        // Move to WAITING
        assertTrue(registry.transitionState(matchId, sessionId1, 1, MatchState.CREATED, MatchState.WAITING, now));

        // Cannot transition with stale revision (e.g. revision 1 instead of 2)
        assertFalse(registry.transitionState(matchId, sessionId1, 1, MatchState.WAITING, MatchState.COUNTDOWN, now));
    }

    @Test
    void testDoubleFinishAndDoubleAbortAreRejected() {
        MatchId matchId = MatchId.random();
        MatchDefinition def = MatchDefinition.builder().gameId(gameId).minPlayers(2).maxPlayers(4).build();
        Instant now = Instant.now();
        InMemoryMatchRegistry.MatchSessionState state = registry.createMatch(matchId, def, instance1, sessionId1, now);

        registry.transitionState(matchId, sessionId1, 1, MatchState.CREATED, MatchState.WAITING, now);
        registry.transitionState(matchId, sessionId1, 2, MatchState.WAITING, MatchState.COUNTDOWN, now);
        registry.transitionState(matchId, sessionId1, 3, MatchState.COUNTDOWN, MatchState.LOCKED, now);
        registry.transitionState(matchId, sessionId1, 4, MatchState.LOCKED, MatchState.IN_GAME, now);

        MatchResult result = MatchResult.draw(Duration.ofSeconds(30));
        assertTrue(registry.finishMatch(matchId, sessionId1, 5, result, now));
        assertEquals(MatchState.FINISHED, state.stateMachine().state());

        // Double finish is rejected
        assertFalse(registry.finishMatch(matchId, sessionId1, 6, result, now));

        // Aborting an already finished match is rejected
        assertFalse(registry.abortMatch(matchId, "Too late", now));
    }

    @Test
    void testAbortFromAnyState() {
        MatchId matchId = MatchId.random();
        MatchDefinition def = MatchDefinition.builder().gameId(gameId).minPlayers(2).maxPlayers(4).build();
        Instant now = Instant.now();
        registry.createMatch(matchId, def, instance1, sessionId1, now);

        // Abort directly from CREATED
        assertTrue(registry.abortMatch(matchId, "Instance failure", now));
        assertEquals(MatchState.ABORTED, registry.find(matchId).orElseThrow().state());

        // Cannot abort again (double abort rejected)
        assertFalse(registry.abortMatch(matchId, "Again", now));
    }

    @Test
    void testPlayerSessionInvariantRejectsDualActiveMatches() {
        MatchId match1 = MatchId.random();
        MatchId match2 = MatchId.random();
        ServerId instance2 = ServerId.of("campominado-02");
        UUID sessionId2 = UUID.randomUUID();

        MatchDefinition def = MatchDefinition.builder().gameId(gameId).minPlayers(2).maxPlayers(4).build();
        Instant now = Instant.now();

        registry.createMatch(match1, def, instance1, sessionId1, now);
        registry.createMatch(match2, def, instance2, sessionId2, now);

        registry.transitionState(match1, sessionId1, 1, MatchState.CREATED, MatchState.WAITING, now);
        registry.transitionState(match2, sessionId2, 1, MatchState.CREATED, MatchState.WAITING, now);

        UUID player = UUID.randomUUID();

        // Issue and admit to match 1
        AdmissionTicket t1 = ticketService.issue(player, match1, instance1, ParticipantRole.PLAYER, now, Duration.ofSeconds(10));
        AdmissionTicket consumed1 = ticketService.consume(t1.ticketId(), player, match1, instance1, t1.token(), now);
        registry.admitPlayer(consumed1, now);

        // Attempt to admit player to match 2 while still in match 1 -> MUST BE REJECTED!
        AdmissionTicket t2 = ticketService.issue(player, match2, instance2, ParticipantRole.PLAYER, now, Duration.ofSeconds(10));
        AdmissionTicket consumed2 = ticketService.consume(t2.ticketId(), player, match2, instance2, t2.token(), now);

        MatchException exception = assertThrows(MatchException.class, () -> registry.admitPlayer(consumed2, now));
        assertEquals(MatchException.ErrorCode.PLAYER_ALREADY_ASSIGNED, exception.errorCode());

        // Player leaves match 1
        registry.removePlayer(match1, player, "left", now);

        // Now player can be admitted to match 2!
        AdmissionTicket t3 = ticketService.issue(player, match2, instance2, ParticipantRole.PLAYER, now, Duration.ofSeconds(10));
        AdmissionTicket consumed3 = ticketService.consume(t3.ticketId(), player, match2, instance2, t3.token(), now);
        assertDoesNotThrow(() -> registry.admitPlayer(consumed3, now));
    }

    @Test
    void testAdmissionTicketLifecycleAndReplayProtection() {
        MatchId matchId = MatchId.random();
        UUID player = UUID.randomUUID();
        Instant now = Instant.now();

        AdmissionTicket ticket = ticketService.issue(player, matchId, instance1, ParticipantRole.PLAYER, now, Duration.ofSeconds(5));
        assertEquals(player, ticket.playerId());
        assertEquals(matchId, ticket.matchId());
        assertEquals(instance1, ticket.instanceId());
        assertFalse(ticket.isExpired(now));

        // Wrong token rejected
        assertThrows(MatchException.class, () ->
                ticketService.consume(ticket.ticketId(), player, matchId, instance1, "wrong_token", now));

        // Wrong player rejected
        assertThrows(MatchException.class, () ->
                ticketService.consume(ticket.ticketId(), UUID.randomUUID(), matchId, instance1, ticket.token(), now));

        // Wrong match rejected
        assertThrows(MatchException.class, () ->
                ticketService.consume(ticket.ticketId(), player, MatchId.random(), instance1, ticket.token(), now));

        // Wrong instance rejected
        assertThrows(MatchException.class, () ->
                ticketService.consume(ticket.ticketId(), player, matchId, ServerId.of("wrong-server"), ticket.token(), now));

        // Valid consumption succeeds
        AdmissionTicket consumed = ticketService.consume(ticket.ticketId(), player, matchId, instance1, ticket.token(), now);
        assertNotNull(consumed);

        // Replay attack: second consumption MUST be rejected
        assertThrows(MatchException.class, () ->
                ticketService.consume(ticket.ticketId(), player, matchId, instance1, ticket.token(), now));

        // Expired ticket consumption rejected
        AdmissionTicket expiredTicket = ticketService.issue(player, matchId, instance1, ParticipantRole.PLAYER, now, Duration.ofSeconds(1));
        Instant later = now.plusSeconds(2);
        assertTrue(expiredTicket.isExpired(later));
        assertThrows(MatchException.class, () ->
                ticketService.consume(expiredTicket.ticketId(), player, matchId, instance1, expiredTicket.token(), later));
    }

    @Test
    void testReconcileInstanceCrashOrShutdown() {
        MatchId matchId = MatchId.random();
        MatchDefinition def = MatchDefinition.builder().gameId(gameId).minPlayers(2).maxPlayers(4).build();
        Instant now = Instant.now();
        registry.createMatch(matchId, def, instance1, sessionId1, now);
        registry.transitionState(matchId, sessionId1, 1, MatchState.CREATED, MatchState.WAITING, now);

        UUID p1 = UUID.randomUUID();
        AdmissionTicket ticket = ticketService.issue(p1, matchId, instance1, ParticipantRole.PLAYER, now, Duration.ofSeconds(10));
        registry.admitPlayer(ticketService.consume(ticket.ticketId(), p1, matchId, instance1, ticket.token(), now), now);

        assertTrue(registry.findActiveForPlayer(p1).isPresent());

        // Instance crashes / times out
        registry.reconcileInstanceCrashOrShutdown(instance1, sessionId1, now);

        // Match was automatically aborted
        MatchSnapshot snapshot = registry.find(matchId).orElseThrow();
        assertEquals(MatchState.ABORTED, snapshot.state());

        // Player is no longer marked in an active match
        assertTrue(registry.findActiveForPlayer(p1).isEmpty());

        // Instance is freed
        assertTrue(registry.findActiveForInstance(instance1).isEmpty());
    }
}
