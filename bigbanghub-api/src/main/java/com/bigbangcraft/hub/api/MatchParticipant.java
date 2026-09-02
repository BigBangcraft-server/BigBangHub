package com.bigbangcraft.hub.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record MatchParticipant(
        UUID playerId,
        MatchId matchId,
        ParticipantRole role,
        ParticipantState state,
        Instant joinedAt) {

    public MatchParticipant {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(joinedAt, "joinedAt");
    }

    public boolean isSpectator() {
        return role == ParticipantRole.SPECTATOR || state == ParticipantState.SPECTATING;
    }

    public boolean isPlayer() {
        return role == ParticipantRole.PLAYER;
    }

    public boolean isActive() {
        return state == ParticipantState.ACTIVE;
    }
}
