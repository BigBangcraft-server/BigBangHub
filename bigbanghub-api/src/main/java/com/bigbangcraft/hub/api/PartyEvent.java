package com.bigbangcraft.hub.api;

import java.time.Instant;

/** Common interface for all party domain events. */
public interface PartyEvent {
    PartyId partyId();

    Instant timestamp();
}
