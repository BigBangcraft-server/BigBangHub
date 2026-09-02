package com.bigbangcraft.hub.common;

import com.bigbangcraft.hub.api.GameId;
import com.bigbangcraft.hub.api.GameState;
import com.bigbangcraft.hub.api.InstanceHealth;
import com.bigbangcraft.hub.api.InstanceHealthChangedEvent;
import com.bigbangcraft.hub.api.InstanceRegisteredEvent;
import com.bigbangcraft.hub.api.InstanceRegistry;
import com.bigbangcraft.hub.api.InstanceSnapshot;
import com.bigbangcraft.hub.api.InstanceStateChangedEvent;
import com.bigbangcraft.hub.api.ServerId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryInstanceRegistry implements InstanceRegistry {

    public enum RegisterOutcome {
        CREATED,
        UPDATED,
        REPLACED
    }

    public enum HeartbeatOutcome {
        ACCEPTED,
        ACCEPTED_RECOVERED,
        REJECTED_UNKNOWN_INSTANCE,
        REJECTED_STALE_SESSION
    }

    public enum StateChangeOutcome {
        SUCCESS,
        REJECTED_UNKNOWN_INSTANCE,
        REJECTED_STALE_SESSION
    }

    public enum UnregisterOutcome {
        SUCCESS,
        NOT_FOUND,
        STALE_SESSION
    }

    public record LivenessTransition(
            ServerId instanceId,
            InstanceHealth oldHealth,
            InstanceHealth newHealth,
            Set<UUID> orphanedReservations) {
    }

    public static final class Entry {
        private final ServerId instanceId;
        private final GameId gameId;
        private final String serverName;
        private volatile UUID sessionId;
        private volatile GameState state;
        private volatile InstanceHealth health;
        private volatile int playerCount;
        private volatile int minPlayers;
        private volatile int maxPlayers;
        private volatile boolean acceptingPlayers;
        private volatile long lastHeartbeatNanos;
        private volatile Instant lastHeartbeatInstant;
        private final Set<UUID> activeReservations = ConcurrentHashMap.newKeySet();
        private final Map<String, String> metadata = new ConcurrentHashMap<>();

        public Entry(ServerId instanceId, GameId gameId, String serverName, UUID sessionId,
                     GameState state, InstanceHealth health, int playerCount, int minPlayers,
                     int maxPlayers, boolean acceptingPlayers, long lastHeartbeatNanos, Instant lastHeartbeatInstant) {
            this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
            this.gameId = Objects.requireNonNull(gameId, "gameId");
            this.serverName = Objects.requireNonNull(serverName, "serverName");
            this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
            this.state = Objects.requireNonNull(state, "state");
            this.health = Objects.requireNonNull(health, "health");
            this.playerCount = playerCount;
            this.minPlayers = minPlayers;
            this.maxPlayers = maxPlayers;
            this.acceptingPlayers = acceptingPlayers;
            this.lastHeartbeatNanos = lastHeartbeatNanos;
            this.lastHeartbeatInstant = Objects.requireNonNull(lastHeartbeatInstant, "lastHeartbeatInstant");
        }

        public ServerId instanceId() { return instanceId; }
        public GameId gameId() { return gameId; }
        public String serverName() { return serverName; }
        public UUID sessionId() { return sessionId; }
        public GameState state() { return state; }
        public InstanceHealth health() { return health; }
        public int playerCount() { return playerCount; }
        public int minPlayers() { return minPlayers; }
        public int maxPlayers() { return maxPlayers; }
        public boolean acceptingPlayers() { return acceptingPlayers; }
        public long lastHeartbeatNanos() { return lastHeartbeatNanos; }
        public Instant lastHeartbeatInstant() { return lastHeartbeatInstant; }
        public int activeReservationsCount() { return activeReservations.size(); }
        public Set<UUID> activeReservations() { return Collections.unmodifiableSet(activeReservations); }

        public synchronized boolean reserveSlot(UUID reservationId) {
            if (health != InstanceHealth.HEALTHY || !acceptingPlayers) return false;
            int effectiveCapacity = maxPlayers - (playerCount + activeReservations.size());
            if (effectiveCapacity <= 0) return false;
            return activeReservations.add(reservationId);
        }

        public synchronized boolean releaseSlot(UUID reservationId) {
            return activeReservations.remove(reservationId);
        }

        public synchronized boolean confirmSlot(UUID reservationId) {
            return activeReservations.remove(reservationId);
        }

        public synchronized Set<UUID> clearReservations() {
            Set<UUID> cleared = Set.copyOf(activeReservations);
            activeReservations.clear();
            return cleared;
        }

        public synchronized void updateLiveness(long nowNanos, Instant now) {
            this.lastHeartbeatNanos = nowNanos;
            this.lastHeartbeatInstant = now;
        }

        public synchronized void setHealth(InstanceHealth newHealth) {
            this.health = newHealth;
        }

        public synchronized void setAcceptingPlayers(boolean accepting) {
            this.acceptingPlayers = accepting;
        }

        public synchronized void setPlayerCount(int count) {
            this.playerCount = count;
        }

        public synchronized void updateState(GameState newState, boolean accepting, int players, int max) {
            this.state = newState;
            this.acceptingPlayers = accepting;
            this.playerCount = players;
            this.maxPlayers = max;
        }

        public synchronized Set<UUID> replaceSession(UUID newSessionId, GameState newState, int players,
                                                    int min, int max, boolean accepting, long nowNanos, Instant now) {
            Set<UUID> orphaned = clearReservations();
            this.sessionId = newSessionId;
            this.state = newState;
            this.health = InstanceHealth.HEALTHY;
            this.playerCount = players;
            this.minPlayers = min;
            this.maxPlayers = max;
            this.acceptingPlayers = accepting;
            this.lastHeartbeatNanos = nowNanos;
            this.lastHeartbeatInstant = now;
            return orphaned;
        }

        public InstanceSnapshot snapshot() {
            return new InstanceSnapshot(
                    instanceId, gameId, serverName, sessionId, state, health,
                    playerCount, minPlayers, maxPlayers, activeReservations.size(),
                    acceptingPlayers, lastHeartbeatInstant);
        }
    }

    private final Map<ServerId, Entry> instances = new ConcurrentHashMap<>();
    private final InstanceEventBus events;

    public InMemoryInstanceRegistry(InstanceEventBus events) {
        this.events = Objects.requireNonNull(events, "events");
    }

    public RegisterOutcome register(MessagePayloads.InstanceRegister register, long nowNanos, Instant now) {
        Objects.requireNonNull(register, "register");
        Objects.requireNonNull(now, "now");
        GameState state = mapState(register.state());
        Entry entry = instances.get(register.instanceId());

        if (entry != null) {
            if (entry.sessionId().equals(register.sessionId())) {
                entry.updateState(state, register.acceptingPlayers(), register.playerCount(), register.maxPlayers());
                entry.updateLiveness(nowNanos, now);
                entry.setHealth(InstanceHealth.HEALTHY);
                return RegisterOutcome.UPDATED;
            } else {
                // New session replaces old session
                entry.replaceSession(register.sessionId(), state, register.playerCount(),
                        register.minPlayers(), register.maxPlayers(), register.acceptingPlayers(),
                        nowNanos, now);
                events.publish(new InstanceRegisteredEvent(entry.snapshot()));
                return RegisterOutcome.REPLACED;
            }
        }

        Entry newEntry = new Entry(
                register.instanceId(), register.gameId(), register.serverName(), register.sessionId(),
                state, InstanceHealth.HEALTHY, register.playerCount(), register.minPlayers(),
                register.maxPlayers(), register.acceptingPlayers(), nowNanos, now);
        instances.put(register.instanceId(), newEntry);
        events.publish(new InstanceRegisteredEvent(newEntry.snapshot()));
        return RegisterOutcome.CREATED;
    }

    public HeartbeatOutcome heartbeat(MessagePayloads.InstanceHeartbeat heartbeat, long nowNanos, Instant now) {
        Objects.requireNonNull(heartbeat, "heartbeat");
        Objects.requireNonNull(now, "now");
        Entry entry = instances.get(heartbeat.instanceId());
        if (entry == null) return HeartbeatOutcome.REJECTED_UNKNOWN_INSTANCE;

        if (!entry.sessionId().equals(heartbeat.sessionId())) {
            return HeartbeatOutcome.REJECTED_STALE_SESSION;
        }

        GameState newState = mapState(heartbeat.state());
        InstanceHealth oldHealth = entry.health();
        entry.updateState(newState, heartbeat.acceptingPlayers(), heartbeat.playerCount(), heartbeat.maxPlayers());
        entry.updateLiveness(nowNanos, now);

        if (oldHealth != InstanceHealth.HEALTHY) {
            entry.setHealth(InstanceHealth.HEALTHY);
            events.publish(new InstanceHealthChangedEvent(entry.instanceId(), oldHealth, InstanceHealth.HEALTHY));
            return HeartbeatOutcome.ACCEPTED_RECOVERED;
        }
        return HeartbeatOutcome.ACCEPTED;
    }

    public boolean updateLiveness(ServerId instanceId, long nowNanos, Instant now) {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(now, "now");
        Entry entry = instances.get(instanceId);
        if (entry == null) return false;
        InstanceHealth oldHealth = entry.health();
        entry.updateLiveness(nowNanos, now);
        if (oldHealth != InstanceHealth.HEALTHY) {
            entry.setHealth(InstanceHealth.HEALTHY);
            entry.setAcceptingPlayers(true);
            events.publish(new InstanceHealthChangedEvent(instanceId, oldHealth, InstanceHealth.HEALTHY));
        }
        return true;
    }

    public boolean updatePingLiveness(ServerId instanceId, int playerCount, long nowNanos, Instant now) {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(now, "now");
        Entry entry = instances.get(instanceId);
        if (entry == null) return false;
        InstanceHealth oldHealth = entry.health();
        entry.setPlayerCount(playerCount);
        entry.updateLiveness(nowNanos, now);
        if (oldHealth != InstanceHealth.HEALTHY) {
            entry.setHealth(InstanceHealth.HEALTHY);
            entry.setAcceptingPlayers(true);
            events.publish(new InstanceHealthChangedEvent(instanceId, oldHealth, InstanceHealth.HEALTHY));
        }
        return true;
    }

    public StateChangeOutcome updateState(MessagePayloads.InstanceStateChange change, long nowNanos, Instant now) {
        Objects.requireNonNull(change, "change");
        Objects.requireNonNull(now, "now");
        Entry entry = instances.get(change.instanceId());
        if (entry == null) return StateChangeOutcome.REJECTED_UNKNOWN_INSTANCE;

        if (!entry.sessionId().equals(change.sessionId())) {
            return StateChangeOutcome.REJECTED_STALE_SESSION;
        }

        GameState oldState = entry.state();
        GameState newState = mapState(change.state());
        entry.updateState(newState, change.acceptingPlayers(), change.playerCount(), change.maxPlayers());
        entry.updateLiveness(nowNanos, now);

        if (oldState != newState) {
            events.publish(new InstanceStateChangedEvent(entry.instanceId(), oldState, newState));
        }
        return StateChangeOutcome.SUCCESS;
    }

    public UnregisterOutcome unregister(ServerId instanceId, UUID sessionId, String reason) {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(sessionId, "sessionId");
        Entry entry = instances.get(instanceId);
        if (entry == null) return UnregisterOutcome.NOT_FOUND;

        if (!entry.sessionId().equals(sessionId)) {
            return UnregisterOutcome.STALE_SESSION;
        }

        InstanceHealth oldHealth = entry.health();
        entry.setHealth(InstanceHealth.UNAVAILABLE);
        entry.setAcceptingPlayers(false);
        entry.clearReservations();

        if (oldHealth != InstanceHealth.UNAVAILABLE) {
            events.publish(new InstanceHealthChangedEvent(instanceId, oldHealth, InstanceHealth.UNAVAILABLE));
        }
        return UnregisterOutcome.SUCCESS;
    }

    public List<LivenessTransition> sweepLiveness(long nowNanos, long suspectThresholdNanos, long timeoutNanos) {
        List<LivenessTransition> transitions = new ArrayList<>();
        for (Entry entry : instances.values()) {
            long elapsed = nowNanos - entry.lastHeartbeatNanos();
            InstanceHealth current = entry.health();

            if (elapsed > timeoutNanos) {
                if (current != InstanceHealth.UNAVAILABLE) {
                    entry.setHealth(InstanceHealth.UNAVAILABLE);
                    entry.setAcceptingPlayers(false);
                    Set<UUID> orphaned = entry.clearReservations();
                    transitions.add(new LivenessTransition(entry.instanceId(), current, InstanceHealth.UNAVAILABLE, orphaned));
                    events.publish(new InstanceHealthChangedEvent(entry.instanceId(), current, InstanceHealth.UNAVAILABLE));
                }
            } else if (elapsed > suspectThresholdNanos) {
                if (current == InstanceHealth.HEALTHY) {
                    entry.setHealth(InstanceHealth.SUSPECT);
                    transitions.add(new LivenessTransition(entry.instanceId(), current, InstanceHealth.SUSPECT, Collections.emptySet()));
                    events.publish(new InstanceHealthChangedEvent(entry.instanceId(), current, InstanceHealth.SUSPECT));
                }
            }
        }
        return transitions;
    }

    public boolean reserveSlot(ServerId instanceId, UUID reservationId) {
        Entry entry = instances.get(Objects.requireNonNull(instanceId, "instanceId"));
        if (entry == null) return false;
        return entry.reserveSlot(Objects.requireNonNull(reservationId, "reservationId"));
    }

    public boolean releaseSlot(ServerId instanceId, UUID reservationId) {
        Entry entry = instances.get(Objects.requireNonNull(instanceId, "instanceId"));
        if (entry == null) return false;
        return entry.releaseSlot(Objects.requireNonNull(reservationId, "reservationId"));
    }

    public boolean confirmSlot(ServerId instanceId, UUID reservationId) {
        Entry entry = instances.get(Objects.requireNonNull(instanceId, "instanceId"));
        if (entry == null) return false;
        return entry.confirmSlot(Objects.requireNonNull(reservationId, "reservationId"));
    }

    @Override
    public Collection<InstanceSnapshot> instances() {
        return instances.values().stream().map(Entry::snapshot).toList();
    }

    @Override
    public Collection<InstanceSnapshot> instancesForGame(GameId gameId) {
        Objects.requireNonNull(gameId, "gameId");
        return instances.values().stream()
                .filter(entry -> entry.gameId().equals(gameId))
                .map(Entry::snapshot)
                .toList();
    }

    @Override
    public Optional<InstanceSnapshot> find(ServerId instanceId) {
        Entry entry = instances.get(Objects.requireNonNull(instanceId, "instanceId"));
        return entry != null ? Optional.of(entry.snapshot()) : Optional.empty();
    }

    public Optional<Entry> findEntry(ServerId instanceId) {
        return Optional.ofNullable(instances.get(Objects.requireNonNull(instanceId, "instanceId")));
    }

    public void clear() {
        instances.clear();
    }

    private static GameState mapState(MessagePayloads.GameStateWire wire) {
        return switch (wire) {
            case STARTING -> GameState.STARTING;
            case WAITING -> GameState.WAITING;
            case STARTING_GAME -> GameState.STARTING_GAME;
            case IN_GAME -> GameState.IN_GAME;
            case ENDING -> GameState.ENDING;
            case FULL -> GameState.FULL;
            case MAINTENANCE -> GameState.MAINTENANCE;
            case OFFLINE -> GameState.OFFLINE;
        };
    }
}
