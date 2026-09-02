package com.bigbangcraft.hub.api;

import java.time.Instant;
import java.util.Objects;

public record MatchFinishedEvent(
        MatchSnapshot match,
        MatchResult result,
        Instant timestamp) implements MatchEvent {

    public MatchFinishedEvent(MatchSnapshot match, MatchResult result) {
        this(match, result, Instant.now());
    }

    public MatchFinishedEvent {
        Objects.requireNonNull(match, "match");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(timestamp, "timestamp");
    }

    @Override
    public MatchId matchId() {
        return match.matchId();
    }
}
