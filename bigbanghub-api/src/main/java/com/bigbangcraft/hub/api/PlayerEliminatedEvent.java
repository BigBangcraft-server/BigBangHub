package com.bigbangcraft.hub.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PlayerEliminatedEvent(
        MatchId matchId,
        UUID playerId,
        Instant timestamp) implements MatchEvent {

    public PlayerEliminatedEvent(MatchId matchId, UUID playerId) {
        this(matchId, playerId, Instant.now());
    }

    public PlayerEliminatedEvent {
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(timestamp, "timestamp");
    }
}
