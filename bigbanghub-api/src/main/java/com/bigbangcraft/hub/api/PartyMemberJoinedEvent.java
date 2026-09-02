package com.bigbangcraft.hub.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PartyMemberJoinedEvent(
        PartyId partyId,
        UUID playerId,
        PartyRole role,
        long revision,
        Instant timestamp) implements PartyEvent {

    public PartyMemberJoinedEvent {
        Objects.requireNonNull(partyId, "partyId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(timestamp, "timestamp");
    }
}
