package com.bigbangcraft.hub.api;

import java.util.Objects;

public record ReservationExpiredEvent(Reservation reservation) {
    public ReservationExpiredEvent {
        Objects.requireNonNull(reservation, "reservation");
    }
}
