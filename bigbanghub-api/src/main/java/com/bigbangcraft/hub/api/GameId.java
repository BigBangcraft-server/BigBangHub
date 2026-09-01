package com.bigbangcraft.hub.api;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Validated logical game identifier, never a server address. */
public record GameId(String value) {
    private static final Pattern VALID = Pattern.compile("[a-z0-9](?:[a-z0-9_-]{0,30}[a-z0-9])?");

    public GameId {
        value = normalize(value);
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid game id: " + value);
        }
    }

    public static GameId of(String value) {
        return new GameId(value);
    }

    private static String normalize(String value) {
        return Objects.requireNonNull(value, "value").trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return value;
    }
}
