package com.bigbangcraft.hub.common;

import java.util.List;
import java.util.Objects;

public record CompassMenu(String title, int rows, List<CompassEntry> entries, LobbyItemSettings item) {
    public CompassMenu {
        title = Objects.requireNonNull(title, "title");
        if (rows < 1 || rows > 6) throw new IllegalArgumentException("rows must be between 1 and 6");
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        item = Objects.requireNonNull(item, "item");
    }
}
