package com.bigbangcraft.hub.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PartyMember(
        UUID playerId,
        PartyRole role,
        Instant joinedAt) {

    public PartyMember {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(joinedAt, "joinedAt");
    }

    public boolean isLeader() {
        return role == PartyRole.LEADER;
    }
}
