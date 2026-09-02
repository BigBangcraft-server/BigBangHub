package com.bigbangcraft.hub.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PartyCreatedEvent(
        PartySnapshot party,
        Instant timestamp) implements PartyEvent {

    public PartyCreatedEvent {
        Objects.requireNonNull(party, "party");
        Objects.requireNonNull(timestamp, "timestamp");
    }

    @Override
    public PartyId partyId() {
        return party.partyId();
    }

    public UUID leaderId() {
        return party.leader();
    }
}
