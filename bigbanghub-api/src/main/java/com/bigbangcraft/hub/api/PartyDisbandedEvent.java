package com.bigbangcraft.hub.api;

import java.time.Instant;
import java.util.Objects;

public record PartyDisbandedEvent(
        PartySnapshot party,
        String reason,
        Instant timestamp) implements PartyEvent {

    public PartyDisbandedEvent {
        Objects.requireNonNull(party, "party");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(timestamp, "timestamp");
    }

    @Override
    public PartyId partyId() {
        return party.partyId();
    }
}
