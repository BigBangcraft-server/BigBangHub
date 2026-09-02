package com.bigbangcraft.hub.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Reservation(
        UUID reservationId,
        UUID playerId,
        ServerId instanceId,
        GameId gameId,
        ReservationState state,
        Instant createdAt,
        Instant expiresAt) {

    public Reservation {
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(gameId, "gameId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (expiresAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("expiresAt cannot be before createdAt");
        }
    }

    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt) || now.equals(expiresAt);
    }
}
