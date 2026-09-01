package com.bigbangcraft.hub.api;

import java.util.Objects;
import java.util.Optional;

public record QueueStatus(Optional<GameId> game, int position, int size) {
    public QueueStatus {
        game = Objects.requireNonNull(game, "game");
        if (position < 0 || size < 0) throw new IllegalArgumentException("Invalid queue counters");
    }

    public static QueueStatus empty() {
        return new QueueStatus(Optional.empty(), 0, 0);
    }
}
