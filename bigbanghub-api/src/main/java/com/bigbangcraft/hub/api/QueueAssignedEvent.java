package com.bigbangcraft.hub.api;

import java.util.UUID;

public record QueueAssignedEvent(UUID playerId, GameId gameId, ServerId serverId) implements QueueEvent { }
