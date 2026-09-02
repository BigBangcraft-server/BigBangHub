package com.bigbangcraft.hub.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PartyMemberLeftEvent(
        PartyId partyId,
        UUID playerId,
        String reason,
        long revision,
        Instant timestamp) implements PartyEvent {

    public PartyMemberLeftEvent {
        Objects.requireNonNull(partyId, "partyId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(timestamp, "timestamp");
    }
}
