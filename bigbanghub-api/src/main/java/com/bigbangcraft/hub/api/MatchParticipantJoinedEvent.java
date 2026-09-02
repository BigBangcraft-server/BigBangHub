package com.bigbangcraft.hub.api;

import java.time.Instant;
import java.util.Objects;

public record MatchParticipantJoinedEvent(
        MatchParticipant participant,
        Instant timestamp) implements MatchEvent {

    public MatchParticipantJoinedEvent(MatchParticipant participant) {
        this(participant, Instant.now());
    }

    public MatchParticipantJoinedEvent {
        Objects.requireNonNull(participant, "participant");
        Objects.requireNonNull(timestamp, "timestamp");
    }

    @Override
    public MatchId matchId() {
        return participant.matchId();
    }
}
