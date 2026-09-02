package com.bigbangcraft.hub.common;

import java.util.List;
import java.util.Objects;

public record LobbyItemSettings(boolean enabled, int slot, String material, String name, List<String> lore,
                                boolean glow, List<String> flags) {
    public LobbyItemSettings {
        if (slot < 0 || slot > 35) throw new IllegalArgumentException("slot must be between 0 and 35");
        material = Objects.requireNonNull(material, "material").trim().toUpperCase(java.util.Locale.ROOT);
        name = Objects.requireNonNull(name, "name");
        lore = List.copyOf(Objects.requireNonNull(lore, "lore"));
        flags = List.copyOf(Objects.requireNonNull(flags, "flags"));
    }
}
