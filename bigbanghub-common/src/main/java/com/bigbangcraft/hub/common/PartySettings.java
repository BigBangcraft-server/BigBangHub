package com.bigbangcraft.hub.common;

import java.time.Duration;
import java.util.Objects;

public record PartySettings(
        int maxSize,
        Duration inviteTtl,
        Duration leaderDisconnectGrace,
        Duration inviteCooldown) {

    public PartySettings {
        if (maxSize < 2) {
            throw new IllegalArgumentException("maxSize must be at least 2");
        }
        Objects.requireNonNull(inviteTtl, "inviteTtl");
        Objects.requireNonNull(leaderDisconnectGrace, "leaderDisconnectGrace");
        Objects.requireNonNull(inviteCooldown, "inviteCooldown");
    }

    public static PartySettings defaults() {
        return new PartySettings(8, Duration.ofSeconds(60), Duration.ofSeconds(30), Duration.ofSeconds(5));
    }
}
