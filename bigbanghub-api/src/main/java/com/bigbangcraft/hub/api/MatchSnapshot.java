package com.bigbangcraft.hub.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record MatchSnapshot(
        MatchId matchId,
        GameId gameId,
        ServerId instanceId,
        UUID instanceSessionId,
        MatchState state,
        int minPlayers,
        int maxPlayers,
        int participantCount,
        int spectatorCount,
        int pendingAdmissions,
        long revision,
        Optional<String> arenaId,
        Instant createdAt,
        Optional<Instant> startedAt,
        Optional<Instant> endedAt,
        Optional<MatchResult> result) {

    public MatchSnapshot {
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(gameId, "gameId");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(instanceSessionId, "instanceSessionId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(arenaId, "arenaId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(endedAt, "endedAt");
        Objects.requireNonNull(result, "result");
        if (minPlayers < 1) throw new IllegalArgumentException("minPlayers must be >= 1");
        if (maxPlayers < minPlayers) throw new IllegalArgumentException("maxPlayers must be >= minPlayers");
        if (participantCount < 0) throw new IllegalArgumentException("participantCount cannot be negative");
        if (spectatorCount < 0) throw new IllegalArgumentException("spectatorCount cannot be negative");
        if (pendingAdmissions < 0) throw new IllegalArgumentException("pendingAdmissions cannot be negative");
        if (revision < 0) throw new IllegalArgumentException("revision cannot be negative");
    }

    public int effectiveCapacity() {
        return Math.max(0, maxPlayers - (participantCount + pendingAdmissions));
    }

    public boolean canAcceptParticipants() {
        return state.canAcceptAdmissions(false) && effectiveCapacity() > 0;
    }
}
