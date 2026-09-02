package com.bigbangcraft.hub.common;

import com.bigbangcraft.hub.api.GameId;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

public record RegistrySettings(
        Duration heartbeatTimeout,
        Duration suspectThreshold,
        Duration reservationTtl,
        Map<String, GameId> allowedInstances,
        boolean fallbackToHub) {

    public RegistrySettings {
        Objects.requireNonNull(heartbeatTimeout, "heartbeatTimeout");
        Objects.requireNonNull(suspectThreshold, "suspectThreshold");
        Objects.requireNonNull(reservationTtl, "reservationTtl");
        allowedInstances = Map.copyOf(Objects.requireNonNull(allowedInstances, "allowedInstances"));
        if (heartbeatTimeout.isNegative() || heartbeatTimeout.isZero()) {
            throw new IllegalArgumentException("heartbeatTimeout must be positive");
        }
        if (suspectThreshold.isNegative() || suspectThreshold.isZero()) {
            throw new IllegalArgumentException("suspectThreshold must be positive");
        }
        if (reservationTtl.isNegative() || reservationTtl.isZero()) {
            throw new IllegalArgumentException("reservationTtl must be positive");
        }
    }

    public static RegistrySettings defaults() {
        return new RegistrySettings(
                Duration.ofSeconds(10),
                Duration.ofSeconds(5),
                Duration.ofSeconds(10),
                Map.of(),
                true);
    }
}
