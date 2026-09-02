package com.bigbangcraft.hub.api;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Immutable, validated match identifier.
 * Unique per match lifecycle session, independent of physical server instances.
 */
public final class MatchId implements Comparable<MatchId> {
    private static final Pattern VALID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");
    private final String value;

    private MatchId(String value) {
        this.value = Objects.requireNonNull(value, "value");
        if (!VALID_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid MatchId format (must be 1-64 chars [a-zA-Z0-9_-]): " + value);
        }
    }

    public static MatchId of(String value) {
        return new MatchId(value);
    }

    public static MatchId random() {
        return new MatchId(UUID.randomUUID().toString());
    }

    public String value() {
        return value;
    }

    @Override
    public int compareTo(MatchId other) {
        return this.value.compareTo(other.value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MatchId matchId)) return false;
        return value.equals(matchId.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
