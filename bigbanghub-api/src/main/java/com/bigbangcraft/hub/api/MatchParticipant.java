package com.bigbangcraft.hub.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record MatchParticipant(
        UUID playerId,
        MatchId matchId,
        ParticipantRole role,
        ParticipantState state,
        Instant joinedAt,
        Optional<PartyId> partyId) {

    public MatchParticipant(
            UUID playerId,
            MatchId matchId,
            ParticipantRole role,
            ParticipantState state,
            Instant joinedAt) {
        this(playerId, matchId, role, state, joinedAt, Optional.empty());
    }

    public MatchParticipant {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(joinedAt, "joinedAt");
        Objects.requireNonNull(partyId, "partyId");
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
