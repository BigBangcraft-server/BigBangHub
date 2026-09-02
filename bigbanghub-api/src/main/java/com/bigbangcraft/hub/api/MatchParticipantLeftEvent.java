package com.bigbangcraft.hub.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record MatchParticipantLeftEvent(
        MatchId matchId,
        UUID playerId,
        String reason,
        Instant timestamp) implements MatchEvent {

    public MatchParticipantLeftEvent(MatchId matchId, UUID playerId, String reason) {
        this(matchId, playerId, reason, Instant.now());
    }

    public MatchParticipantLeftEvent {
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(timestamp, "timestamp");
    }
}
