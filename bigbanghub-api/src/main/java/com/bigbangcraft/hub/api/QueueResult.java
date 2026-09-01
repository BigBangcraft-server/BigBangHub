package com.bigbangcraft.hub.api;

import java.util.Objects;
import java.util.Optional;

public record QueueResult(Code code, Optional<GameId> game, int position, int size, String message) {
    public enum Code {
        JOINED,
        ALREADY_QUEUED,
        LEFT,
        NOT_QUEUED,
        UNAVAILABLE,
        INVALID,
        ASSIGNED,
        ERROR
    }

    public QueueResult {
        code = Objects.requireNonNull(code, "code");
        game = Objects.requireNonNull(game, "game");
        if (position < 0 || size < 0) throw new IllegalArgumentException("Invalid queue counters");
        message = Objects.requireNonNull(message, "message");
    }

    public static QueueResult of(Code code, GameId game, int position, int size, String message) {
        return new QueueResult(code, Optional.ofNullable(game), position, size, message);
    }
}
