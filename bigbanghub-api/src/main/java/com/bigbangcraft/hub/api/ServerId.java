package com.bigbangcraft.hub.api;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Validated logical Velocity server identifier. */
public record ServerId(String value) {
    private static final Pattern VALID = Pattern.compile("[a-z0-9](?:[a-z0-9_-]{0,30}[a-z0-9])?");

    public ServerId {
        value = Objects.requireNonNull(value, "value").trim().toLowerCase(Locale.ROOT);
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid server id: " + value);
        }
    }

    public static ServerId of(String value) {
        return new ServerId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
