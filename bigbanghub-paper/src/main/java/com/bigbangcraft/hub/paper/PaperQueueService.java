package com.bigbangcraft.hub.paper;

import com.bigbangcraft.hub.api.GameId;
import com.bigbangcraft.hub.api.QueueEvent;
import com.bigbangcraft.hub.api.QueueJoinedEvent;
import com.bigbangcraft.hub.api.QueueLeftEvent;
import com.bigbangcraft.hub.api.QueueResult;
import com.bigbangcraft.hub.api.QueueService;
import com.bigbangcraft.hub.api.QueueStatus;
import com.bigbangcraft.hub.common.MessagePayloads;
import com.bigbangcraft.hub.common.MessageType;
import com.bigbangcraft.hub.common.ProtocolEnvelope;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

final class PaperQueueService implements QueueService {
    private final VelocityBridge bridge;
    private final Map<UUID, GameId> membership = new ConcurrentHashMap<>();
    private final Map<GameId, Integer> sizes = new ConcurrentHashMap<>();
    private final java.util.concurrent.CopyOnWriteArrayList<Consumer<QueueEvent>> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    PaperQueueService(VelocityBridge bridge) {
        this.bridge = Objects.requireNonNull(bridge, "bridge");
    }

    @Override
    public CompletionStage<QueueResult> join(UUID playerId, GameId gameId) {
        return bridge.request(playerId, MessageType.QUEUE_JOIN, MessagePayloads.queueJoin(playerId, gameId))
                .thenApply(envelope -> queueResult(envelope, playerId));
    }

    @Override
    public CompletionStage<QueueResult> leave(UUID playerId) {
        return bridge.request(playerId, MessageType.QUEUE_LEAVE, MessagePayloads.playerRequest(playerId))
                .thenApply(envelope -> queueResult(envelope, playerId));
    }

    @Override
    public CompletionStage<QueueStatus> status(UUID playerId) {
        return bridge.request(playerId, MessageType.QUEUE_STATUS, MessagePayloads.playerRequest(playerId))
                .thenApply(envelope -> {
                    QueueResult result = queueResult(envelope, playerId);
                    return new QueueStatus(result.game(), result.position(), result.size());
                });
    }

    @Override
    public boolean contains(UUID playerId) {
        return membership.containsKey(playerId);
    }

    @Override
    public int size(GameId gameId) {
        return sizes.getOrDefault(gameId, 0);
    }

    @Override
    public void addListener(Consumer<QueueEvent> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    @Override
    public void removeListener(Consumer<QueueEvent> listener) {
        listeners.remove(listener);
    }

    private QueueResult queueResult(ProtocolEnvelope envelope, UUID expectedPlayer) {
        try {
            MessagePayloads.QueueResponse response = MessagePayloads.queueResponse(envelope.payload());
            if (!expectedPlayer.equals(response.playerId())) {
                return QueueResult.of(QueueResult.Code.ERROR, null, 0, 0, "Resposta inválida do proxy.");
            }
            response.game().ifPresent(game -> sizes.put(game, response.size()));
            if (response.code() == QueueResult.Code.JOINED || response.code() == QueueResult.Code.ALREADY_QUEUED) {
                response.game().ifPresent(game -> {
                    membership.put(expectedPlayer, game);
                    publish(new QueueJoinedEvent(expectedPlayer, game, response.position()));
                });
            } else if (response.code() == QueueResult.Code.LEFT || response.code() == QueueResult.Code.NOT_QUEUED) {
                GameId game = membership.remove(expectedPlayer);
                if (game != null) publish(new QueueLeftEvent(expectedPlayer, game));
            } else if (response.code() == QueueResult.Code.ASSIGNED) {
                membership.remove(expectedPlayer);
            }
            return QueueResult.of(response.code(), response.game().orElse(null), response.position(), response.size(), response.message());
        } catch (Exception exception) {
            return QueueResult.of(QueueResult.Code.ERROR, null, 0, 0, "Resposta inválida do proxy.");
        }
    }

    private void publish(QueueEvent event) {
        listeners.forEach(listener -> listener.accept(event));
    }
}
