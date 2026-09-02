package com.bigbangcraft.hub.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record AdmissionTicket(
        UUID ticketId,
        UUID playerId,
        MatchId matchId,
        ServerId instanceId,
        ParticipantRole role,
        Instant issuedAt,
        Instant expiresAt,
        String token,
        Optional<PartyId> partyId) {

    public AdmissionTicket(
            UUID ticketId,
            UUID playerId,
            MatchId matchId,
            ServerId instanceId,
            ParticipantRole role,
            Instant issuedAt,
            Instant expiresAt,
            String token) {
        this(ticketId, playerId, matchId, instanceId, role, issuedAt, expiresAt, token, Optional.empty());
    }

    public AdmissionTicket {
        Objects.requireNonNull(ticketId, "ticketId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(partyId, "partyId");
        if (expiresAt.isBefore(issuedAt)) {
            throw new IllegalArgumentException("expiresAt cannot be before issuedAt");
        }
        if (token.length() > 64) {
            throw new IllegalArgumentException("token cannot exceed 64 chars");
        }
    }

    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt) || now.equals(expiresAt);
    }
}
