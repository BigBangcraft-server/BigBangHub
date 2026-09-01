package com.bigbangcraft.hub.api;

import java.util.UUID;

public record QueueJoinedEvent(UUID playerId, GameId gameId, int position) implements QueueEvent { }
