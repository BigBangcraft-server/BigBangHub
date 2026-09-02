package com.bigbangcraft.hub.api;

import java.util.Objects;

public record ReservationCancelledEvent(Reservation reservation) {
    public ReservationCancelledEvent {
        Objects.requireNonNull(reservation, "reservation");
    }
}
