package com.bigbangcraft.hub.api;

import java.util.Objects;
import java.util.UUID;

/** Unique identifier for a network party, independent of player UUIDs. */
public record PartyId(UUID value) {
    public PartyId {
        Objects.requireNonNull(value, "value");
    }

    public static PartyId random() {
        return new PartyId(UUID.randomUUID());
    }

    public static PartyId of(UUID value) {
        return new PartyId(value);
    }

    public static PartyId fromString(String string) {
        return new PartyId(UUID.fromString(string));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
