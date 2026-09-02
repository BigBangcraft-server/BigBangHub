package com.bigbangcraft.hub.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PartyInvite(
        PartyId partyId,
        UUID inviter,
        UUID target,
        Instant createdAt,
        Instant expiresAt) {

    public PartyInvite {
        Objects.requireNonNull(partyId, "partyId");
        Objects.requireNonNull(inviter, "inviter");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (inviter.equals(target)) {
            throw new IllegalArgumentException("Player cannot invite themselves");
        }
        if (expiresAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("expiresAt cannot be before createdAt");
        }
    }

    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }
}
