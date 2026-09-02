package com.bigbangcraft.hub.api;

import java.time.Instant;
import java.util.Objects;

public record MatchCreatedEvent(MatchSnapshot match, Instant timestamp) implements MatchEvent {
    public MatchCreatedEvent(MatchSnapshot match) {
        this(match, Instant.now());
    }

    public MatchCreatedEvent {
        Objects.requireNonNull(match, "match");
        Objects.requireNonNull(timestamp, "timestamp");
    }

    @Override
    public MatchId matchId() {
        return match.matchId();
    }
}
