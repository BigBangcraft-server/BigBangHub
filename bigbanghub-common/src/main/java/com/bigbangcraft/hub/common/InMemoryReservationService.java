package com.bigbangcraft.hub.common;

import com.bigbangcraft.hub.api.GameId;
import com.bigbangcraft.hub.api.Reservation;
import com.bigbangcraft.hub.api.ReservationCancelledEvent;
import com.bigbangcraft.hub.api.ReservationConfirmedEvent;
import com.bigbangcraft.hub.api.ReservationExpiredEvent;
import com.bigbangcraft.hub.api.ReservationState;
import com.bigbangcraft.hub.api.ServerId;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class InMemoryReservationService {
    private final InMemoryInstanceRegistry registry;
    private final Duration ttl;
    private final Map<UUID, Reservation> activeByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, Reservation> byReservationId = new ConcurrentHashMap<>();
    private final List<Consumer<ReservationExpiredEvent>> expiredListeners = new ArrayList<>();
    private final List<Consumer<ReservationConfirmedEvent>> confirmedListeners = new ArrayList<>();
    private final List<Consumer<ReservationCancelledEvent>> cancelledListeners = new ArrayList<>();

    public InMemoryReservationService(InMemoryInstanceRegistry registry, Duration ttl) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("TTL must be positive: " + ttl);
        }
    }

    public synchronized Optional<Reservation> reserve(ServerId instanceId, UUID playerId, GameId gameId, Instant now) {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(gameId, "gameId");
        Objects.requireNonNull(now, "now");

        Reservation existing = activeByPlayer.get(playerId);
        if (existing != null && existing.state() == ReservationState.RESERVED) {
            if (!existing.isExpired(now)) {
                return Optional.empty(); // Player already has an active reservation
            }
            // Expire stale reservation first
            expireInternal(existing);
        }

        UUID reservationId = UUID.randomUUID();
        if (!registry.reserveSlot(instanceId, reservationId)) {
            return Optional.empty();
        }

        Reservation reservation = new Reservation(
                reservationId, playerId, instanceId, gameId,
                ReservationState.RESERVED, now, now.plus(ttl));
        activeByPlayer.put(playerId, reservation);
        byReservationId.put(reservationId, reservation);
        return Optional.of(reservation);
    }

    public synchronized boolean confirm(UUID playerId, ServerId instanceId) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(instanceId, "instanceId");

        Reservation current = activeByPlayer.get(playerId);
        if (current == null || current.state() != ReservationState.RESERVED) return false;
        if (!current.instanceId().equals(instanceId)) return false;

        Reservation confirmed = new Reservation(
                current.reservationId(), current.playerId(), current.instanceId(),
                current.gameId(), ReservationState.CONFIRMED, current.createdAt(), current.expiresAt());
        activeByPlayer.remove(playerId);
        byReservationId.put(confirmed.reservationId(), confirmed);
        registry.confirmSlot(instanceId, current.reservationId());

        ReservationConfirmedEvent event = new ReservationConfirmedEvent(confirmed);
        for (Consumer<ReservationConfirmedEvent> listener : confirmedListeners) {
            listener.accept(event);
        }
        return true;
    }

    public synchronized boolean cancel(UUID playerId, String reason) {
        Objects.requireNonNull(playerId, "playerId");
        Reservation current = activeByPlayer.remove(playerId);
        if (current == null || current.state() != ReservationState.RESERVED) return false;

        Reservation cancelled = new Reservation(
                current.reservationId(), current.playerId(), current.instanceId(),
                current.gameId(), ReservationState.CANCELLED, current.createdAt(), current.expiresAt());
        byReservationId.put(cancelled.reservationId(), cancelled);
        registry.releaseSlot(current.instanceId(), current.reservationId());

        ReservationCancelledEvent event = new ReservationCancelledEvent(cancelled);
        for (Consumer<ReservationCancelledEvent> listener : cancelledListeners) {
            listener.accept(event);
        }
        return true;
    }

    public synchronized List<Reservation> sweepExpired(Instant now) {
        Objects.requireNonNull(now, "now");
        List<Reservation> expiredList = new ArrayList<>();
        for (Reservation res : List.copyOf(activeByPlayer.values())) {
            if (res.state() == ReservationState.RESERVED && res.isExpired(now)) {
                Reservation expired = expireInternal(res);
                expiredList.add(expired);
            }
        }
        return expiredList;
    }

    private Reservation expireInternal(Reservation current) {
        activeByPlayer.remove(current.playerId(), current);
        Reservation expired = new Reservation(
                current.reservationId(), current.playerId(), current.instanceId(),
                current.gameId(), ReservationState.EXPIRED, current.createdAt(), current.expiresAt());
        byReservationId.put(expired.reservationId(), expired);
        registry.releaseSlot(current.instanceId(), current.reservationId());

        ReservationExpiredEvent event = new ReservationExpiredEvent(expired);
        for (Consumer<ReservationExpiredEvent> listener : expiredListeners) {
            listener.accept(event);
        }
        return expired;
    }

    public Optional<Reservation> getActive(UUID playerId) {
        Reservation res = activeByPlayer.get(Objects.requireNonNull(playerId, "playerId"));
        return (res != null && res.state() == ReservationState.RESERVED) ? Optional.of(res) : Optional.empty();
    }

    public Optional<Reservation> find(UUID reservationId) {
        return Optional.ofNullable(byReservationId.get(Objects.requireNonNull(reservationId, "reservationId")));
    }

    public int activeCount() {
        return activeByPlayer.size();
    }

    public void addExpiredListener(Consumer<ReservationExpiredEvent> listener) {
        expiredListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void addConfirmedListener(Consumer<ReservationConfirmedEvent> listener) {
        confirmedListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void addCancelledListener(Consumer<ReservationCancelledEvent> listener) {
        cancelledListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public synchronized void clear() {
        for (Reservation res : activeByPlayer.values()) {
            if (res.state() == ReservationState.RESERVED) {
                registry.releaseSlot(res.instanceId(), res.reservationId());
            }
        }
        activeByPlayer.clear();
        byReservationId.clear();
    }
}
