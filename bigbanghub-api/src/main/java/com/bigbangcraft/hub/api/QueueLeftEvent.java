package com.bigbangcraft.hub.api;

import java.util.UUID;

public record QueueLeftEvent(UUID playerId, GameId gameId) implements QueueEvent { }
