package com.bigbangcraft.hub.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PartyLeaderChangedEvent(
        PartyId partyId,
        UUID previousLeader,
        UUID newLeader,
        long revision,
        Instant timestamp) implements PartyEvent {

    public PartyLeaderChangedEvent {
        Objects.requireNonNull(partyId, "partyId");
        Objects.requireNonNull(previousLeader, "previousLeader");
        Objects.requireNonNull(newLeader, "newLeader");
        Objects.requireNonNull(timestamp, "timestamp");
    }
}
