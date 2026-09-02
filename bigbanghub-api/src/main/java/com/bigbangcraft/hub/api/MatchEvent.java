package com.bigbangcraft.hub.api;

import java.time.Instant;

public interface MatchEvent {
    MatchId matchId();
    Instant timestamp();
}
