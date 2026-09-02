package com.bigbangcraft.hub.api;

public enum PartyState {
    IDLE,
    QUEUED,
    ASSIGNED,
    IN_MATCH,
    DISBANDING;

    public boolean isLocked() {
        return this == QUEUED || this == ASSIGNED || this == IN_MATCH;
    }

    public boolean isTerminal() {
        return this == DISBANDING;
    }
}
