package com.bigbangcraft.hub.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PlayerAdmissionAcceptedEvent(
        MatchId matchId,
        UUID playerId,
        ParticipantRole role,
        Instant timestamp) implements MatchEvent {

    public PlayerAdmissionAcceptedEvent(MatchId matchId, UUID playerId, ParticipantRole role) {
        this(matchId, playerId, role, Instant.now());
    }

    public PlayerAdmissionAcceptedEvent {
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(timestamp, "timestamp");
    }
}
