package com.bigbangcraft.hub.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PlayerAdmissionRejectedEvent(
        MatchId matchId,
        UUID playerId,
        String reason,
        Instant timestamp) implements MatchEvent {

    public PlayerAdmissionRejectedEvent(MatchId matchId, UUID playerId, String reason) {
        this(matchId, playerId, reason, Instant.now());
    }

    public PlayerAdmissionRejectedEvent {
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(timestamp, "timestamp");
    }
}
