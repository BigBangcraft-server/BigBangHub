package com.bigbangcraft.hub.api;

import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * Queue operations are safe for concurrent callers. Implementations must not block a platform
 * event loop or a Paper main thread on network or disk I/O.
 */
public interface QueueService {
    CompletionStage<QueueResult> join(UUID playerId, GameId gameId);

    CompletionStage<QueueResult> leave(UUID playerId);

    CompletionStage<QueueStatus> status(UUID playerId);

    boolean contains(UUID playerId);

    int size(GameId gameId);

    void addListener(Consumer<QueueEvent> listener);

    void removeListener(Consumer<QueueEvent> listener);
}
