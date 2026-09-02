package com.bigbangcraft.hub.api;

import java.time.Instant;
import java.util.Objects;

public record MatchAbortedEvent(
        MatchSnapshot match,
        String reason,
        Instant timestamp) implements MatchEvent {

    public MatchAbortedEvent(MatchSnapshot match, String reason) {
        this(match, reason, Instant.now());
    }

    public MatchAbortedEvent {
        Objects.requireNonNull(match, "match");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(timestamp, "timestamp");
    }

    @Override
    public MatchId matchId() {
        return match.matchId();
    }
}
