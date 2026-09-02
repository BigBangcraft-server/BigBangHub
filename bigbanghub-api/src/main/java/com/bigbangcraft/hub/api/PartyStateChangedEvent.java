package com.bigbangcraft.hub.api;

import java.time.Instant;
import java.util.Objects;

public record PartyStateChangedEvent(
        PartyId partyId,
        PartyState previousState,
        PartyState newState,
        long revision,
        Instant timestamp) implements PartyEvent {

    public PartyStateChangedEvent {
        Objects.requireNonNull(partyId, "partyId");
        Objects.requireNonNull(previousState, "previousState");
        Objects.requireNonNull(newState, "newState");
        Objects.requireNonNull(timestamp, "timestamp");
    }
}
