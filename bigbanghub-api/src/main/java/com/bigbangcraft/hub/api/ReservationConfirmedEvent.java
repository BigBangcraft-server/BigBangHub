package com.bigbangcraft.hub.api;

import java.util.Objects;

public record ReservationConfirmedEvent(Reservation reservation) {
    public ReservationConfirmedEvent {
        Objects.requireNonNull(reservation, "reservation");
    }
}
