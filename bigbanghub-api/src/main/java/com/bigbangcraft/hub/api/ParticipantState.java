package com.bigbangcraft.hub.api;

public enum ParticipantState {
    RESERVED,
    ADMITTED,
    ACTIVE,
    ELIMINATED,
    SPECTATING,
    LEAVING,
    LEFT,
    DISCONNECTED;

    public boolean isParticipating() {
        return this == ACTIVE;
    }

    public boolean isPresent() {
        return this == ADMITTED || this == ACTIVE || this == ELIMINATED || this == SPECTATING;
    }
}
