package com.bigbangcraft.hub.api;

import java.time.Instant;
import java.util.Objects;

public record MatchStateChangedEvent(
        MatchId matchId,
        MatchState oldState,
        MatchState newState,
        long revision,
        Instant timestamp) implements MatchEvent {

    public MatchStateChangedEvent(MatchId matchId, MatchState oldState, MatchState newState, long revision) {
        this(matchId, oldState, newState, revision, Instant.now());
    }

    public MatchStateChangedEvent {
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(oldState, "oldState");
        Objects.requireNonNull(newState, "newState");
        Objects.requireNonNull(timestamp, "timestamp");
    }
}
