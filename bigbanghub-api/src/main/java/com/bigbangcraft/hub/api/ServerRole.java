package com.bigbangcraft.hub.api;

import java.util.Locale;

public enum ServerRole {
    HUB,
    MINIGAME,
    GENERIC;

    public static ServerRole parse(String raw) {
        if (raw == null || raw.isBlank()) return GENERIC;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown server role: " + raw);
        }
    }
}
