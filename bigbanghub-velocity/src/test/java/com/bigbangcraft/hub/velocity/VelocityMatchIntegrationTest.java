package com.bigbangcraft.hub.velocity;

import com.bigbangcraft.hub.api.GameId;
import com.bigbangcraft.hub.api.MatchDefinition;
import com.bigbangcraft.hub.api.MatchId;
import com.bigbangcraft.hub.api.MatchParticipant;
import com.bigbangcraft.hub.api.MatchResult;
import com.bigbangcraft.hub.api.MatchSnapshot;
import com.bigbangcraft.hub.api.MatchState;
import com.bigbangcraft.hub.api.ParticipantRole;
import com.bigbangcraft.hub.api.ParticipantState;
import com.bigbangcraft.hub.api.ServerId;
import com.bigbangcraft.hub.common.AdmissionTicketService;
import com.bigbangcraft.hub.common.InMemoryMatchRegistry;
import com.bigbangcraft.hub.common.MatchEventBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class VelocityMatchIntegrationTest {
    private MatchEventBus eventBus;
    private InMemoryMatchRegistry registry;
    private AdmissionTicketService ticketService;

    private final GameId gameId = GameId.of("campominado");
    private final ServerId instanceId = ServerId.of("campominado-01");
    private final UUID sessionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        eventBus = new MatchEventBus();
        registry = new InMemoryMatchRegistry(eventBus, Duration.ofSeconds(60));
        ticketService = new AdmissionTicketService(Duration.ofSeconds(10));
    }

    @Test
    void testMatchRegistryAndTicketLifecycle() {
        MatchId matchId = MatchId.random();
        MatchDefinition def = MatchDefinition.builder().gameId(gameId).minPlayers(2).maxPlayers(4).build();
        Instant now = Instant.now();

        InMemoryMatchRegistry.MatchSessionState session = registry.createMatch(matchId, def, instanceId, sessionId, now);
        assertEquals(MatchState.CREATED, session.stateMachine().state());

        // Proxy active lookup
        assertTrue(registry.find(matchId).isPresent());
        assertTrue(registry.findActiveForInstance(instanceId).isPresent());
        assertEquals(1, registry.activeMatchesForGame(gameId).size());

        // Open match
        assertTrue(registry.transitionState(matchId, sessionId, 1, MatchState.CREATED, MatchState.WAITING, now));

        // Issue ticket and reserve admission
        UUID player = UUID.randomUUID();
        assertTrue(registry.reserveAdmission(matchId));
        var ticket = ticketService.issue(player, matchId, instanceId, ParticipantRole.PLAYER, now, Duration.ofSeconds(10));

        // Consume and admit
        var consumed = ticketService.consume(ticket.ticketId(), player, matchId, instanceId, ticket.token(), now);
        registry.admitPlayer(consumed, now);

        // Active for player
        assertTrue(registry.findActiveForPlayer(player).isPresent());

        // Transition to in-game
        registry.transitionState(matchId, sessionId, 2, MatchState.WAITING, MatchState.COUNTDOWN, now);
        registry.transitionState(matchId, sessionId, 3, MatchState.COUNTDOWN, MatchState.LOCKED, now);
        registry.transitionState(matchId, sessionId, 4, MatchState.LOCKED, MatchState.IN_GAME, now);

        // Finish match
        assertTrue(registry.finishMatch(matchId, sessionId, 5, MatchResult.singleWinner(player, Duration.ofSeconds(30)), now));

        // Player is freed
        assertTrue(registry.findActiveForPlayer(player).isEmpty());

        // Instance cleanup handshake
        assertTrue(registry.findActiveForInstance(instanceId).isPresent());
        assertTrue(registry.markInstanceReady(instanceId, matchId, now));
        assertTrue(registry.findActiveForInstance(instanceId).isEmpty());
    }
}
