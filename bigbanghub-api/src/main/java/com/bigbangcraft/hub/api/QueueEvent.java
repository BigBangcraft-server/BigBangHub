package com.bigbangcraft.hub.api;

import java.util.UUID;

public sealed interface QueueEvent permits QueueJoinedEvent, QueueLeftEvent, QueueAssignedEvent {
    UUID playerId();

    GameId gameId();
}
