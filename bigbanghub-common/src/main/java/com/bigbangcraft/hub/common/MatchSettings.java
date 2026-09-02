package com.bigbangcraft.hub.common;

import java.time.Duration;
import java.util.Objects;

public record MatchSettings(
        Duration admissionTimeout,
        Duration returnTimeout,
        Duration finishedRetention,
        boolean autoCreateMatch) {

    public MatchSettings {
        Objects.requireNonNull(admissionTimeout, "admissionTimeout");
        Objects.requireNonNull(returnTimeout, "returnTimeout");
        Objects.requireNonNull(finishedRetention, "finishedRetention");
        if (admissionTimeout.isNegative() || admissionTimeout.isZero()) {
            throw new IllegalArgumentException("admissionTimeout must be positive");
        }
        if (returnTimeout.isNegative() || returnTimeout.isZero()) {
            throw new IllegalArgumentException("returnTimeout must be positive");
        }
        if (finishedRetention.isNegative() || finishedRetention.isZero()) {
            throw new IllegalArgumentException("finishedRetention must be positive");
        }
    }

    public static MatchSettings defaults() {
        return new MatchSettings(
                Duration.ofSeconds(10),
                Duration.ofSeconds(10),
                Duration.ofSeconds(60),
                true);
    }
}
