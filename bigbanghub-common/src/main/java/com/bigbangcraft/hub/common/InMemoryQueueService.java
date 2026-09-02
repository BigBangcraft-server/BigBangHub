package com.bigbangcraft.hub.common;

import com.bigbangcraft.hub.api.GameId;
import com.bigbangcraft.hub.api.QueueAssignedEvent;
import com.bigbangcraft.hub.api.QueueEvent;
import com.bigbangcraft.hub.api.QueueJoinedEvent;
import com.bigbangcraft.hub.api.QueueLeftEvent;
import com.bigbangcraft.hub.api.QueueResult;
import com.bigbangcraft.hub.api.QueueService;
import com.bigbangcraft.hub.api.QueueStatus;
import com.bigbangcraft.hub.api.ServerId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.locks.ReentrantLock;

/** Bounded by connected players and intentionally free of disk/network I/O. */
public final class InMemoryQueueService implements QueueService {
    // ponytail: one short lock keeps cross-queue membership atomic; shard only if proxy throughput demands it.
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<GameId, LinkedHashSet<UUID>> queues = new LinkedHashMap<>();
    private final Map<UUID, GameId> memberships = new LinkedHashMap<>();
    private final QueueEventBus events;

    public InMemoryQueueService(QueueEventBus events) {
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override
    public CompletionStage<QueueResult> join(UUID playerId, GameId gameId) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(gameId, "gameId");
        List<QueueEvent> emitted = new ArrayList<>(2);
        QueueResult result;
        lock.lock();
        try {
            GameId current = memberships.get(playerId);
            if (gameId.equals(current)) {
                result = result(QueueResult.Code.ALREADY_QUEUED, playerId, gameId, "Você já está nesta fila.");
            } else {
                if (current != null) removeLocked(playerId, current, emitted);
                LinkedHashSet<UUID> queue = queues.computeIfAbsent(gameId, ignored -> new LinkedHashSet<>());
                queue.add(playerId);
                memberships.put(playerId, gameId);
                int position = positionLocked(playerId, gameId);
                emitted.add(new QueueJoinedEvent(playerId, gameId, position));
                result = QueueResult.of(QueueResult.Code.JOINED, gameId, position, queue.size(),
                        "Você entrou na fila.");
            }
        } finally {
            lock.unlock();
        }
        publish(emitted);
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public CompletionStage<QueueResult> leave(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        List<QueueEvent> emitted = new ArrayList<>(1);
        QueueResult result;
        lock.lock();
        try {
            GameId current = memberships.get(playerId);
            if (current == null) {
                result = QueueResult.of(QueueResult.Code.NOT_QUEUED, null, 0, 0,
                        "Você não está em uma fila.");
            } else {
                removeLocked(playerId, current, emitted);
                result = QueueResult.of(QueueResult.Code.LEFT, current, 0, 0,
                        "Você saiu da fila.");
            }
        } finally {
            lock.unlock();
        }
        publish(emitted);
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public CompletionStage<QueueStatus> status(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        lock.lock();
        try {
            GameId game = memberships.get(playerId);
            if (game == null) return CompletableFuture.completedFuture(QueueStatus.empty());
            return CompletableFuture.completedFuture(new QueueStatus(
                    java.util.Optional.of(game), positionLocked(playerId, game), queues.get(game).size()));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean contains(UUID playerId) {
        return memberships.containsKey(Objects.requireNonNull(playerId, "playerId"));
    }

    @Override
    public int size(GameId gameId) {
        lock.lock();
        try {
            LinkedHashSet<UUID> queue = queues.get(Objects.requireNonNull(gameId, "gameId"));
            return queue == null ? 0 : queue.size();
        } finally {
            lock.unlock();
        }
    }

    public QueueResult assign(UUID playerId, GameId gameId, ServerId serverId) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(gameId, "gameId");
        Objects.requireNonNull(serverId, "serverId");
        List<QueueEvent> emitted = new ArrayList<>(1);
        lock.lock();
        try {
            if (!gameId.equals(memberships.get(playerId))) {
                return QueueResult.of(QueueResult.Code.NOT_QUEUED, gameId, 0, 0,
                        "O jogador não está nesta fila.");
            }
            removeLocked(playerId, gameId, emitted);
            emitted.add(new QueueAssignedEvent(playerId, gameId, serverId));
        } finally {
            lock.unlock();
        }
        publish(emitted);
        return QueueResult.of(QueueResult.Code.ASSIGNED, gameId, 0, 0,
                "Jogador encaminhado para " + serverId + ".");
    }

    public void removePlayer(UUID playerId) {
        leave(playerId);
    }

    public void clear() {
        lock.lock();
        try {
            queues.clear();
            memberships.clear();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void addListener(java.util.function.Consumer<QueueEvent> listener) {
        events.add(listener);
    }

    @Override
    public void removeListener(java.util.function.Consumer<QueueEvent> listener) {
        events.remove(listener);
    }

    private QueueResult result(QueueResult.Code code, UUID playerId, GameId gameId, String message) {
        LinkedHashSet<UUID> queue = queues.get(gameId);
        return QueueResult.of(code, gameId, positionLocked(playerId, gameId), queue.size(), message);
    }

    private int positionLocked(UUID playerId, GameId gameId) {
        LinkedHashSet<UUID> queue = queues.get(gameId);
        if (queue == null) return 0;
        int position = 1;
        for (UUID queued : queue) {
            if (queued.equals(playerId)) return position;
            position++;
        }
        return 0;
    }

    private void removeLocked(UUID playerId, GameId gameId, List<QueueEvent> emitted) {
        LinkedHashSet<UUID> queue = queues.get(gameId);
        if (queue != null) {
            queue.remove(playerId);
            if (queue.isEmpty()) queues.remove(gameId);
        }
        memberships.remove(playerId);
        emitted.add(new QueueLeftEvent(playerId, gameId));
    }

    private void publish(List<QueueEvent> emitted) {
        for (QueueEvent event : emitted) events.publish(event);
    }
}
