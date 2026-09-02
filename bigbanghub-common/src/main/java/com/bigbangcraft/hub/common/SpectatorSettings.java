package com.bigbangcraft.hub.common;

public record SpectatorSettings(boolean enabled) {
    public static SpectatorSettings defaults() {
        return new SpectatorSettings(true);
    }
}
