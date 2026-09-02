package com.bigbangcraft.hub.common;

import java.util.List;
import java.util.Objects;

public record CompassEntry(int slot, String material, String name, List<String> lore, boolean glow,
                           List<String> flags, ActionDefinition action) {
    public CompassEntry {
        if (slot < 0) throw new IllegalArgumentException("slot must be non-negative");
        material = Objects.requireNonNull(material, "material").trim().toUpperCase(java.util.Locale.ROOT);
        name = Objects.requireNonNull(name, "name");
        lore = List.copyOf(Objects.requireNonNull(lore, "lore"));
        flags = List.copyOf(Objects.requireNonNull(flags, "flags"));
        action = Objects.requireNonNull(action, "action");
    }
}
