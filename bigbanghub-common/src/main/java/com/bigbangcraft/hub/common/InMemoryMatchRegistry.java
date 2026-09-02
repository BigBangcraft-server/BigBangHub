package com.bigbangcraft.hub.common;

import com.bigbangcraft.hub.api.AdmissionTicket;
import com.bigbangcraft.hub.api.GameId;
import com.bigbangcraft.hub.api.MatchAbortedEvent;
import com.bigbangcraft.hub.api.MatchCreatedEvent;
import com.bigbangcraft.hub.api.MatchDefinition;
import com.bigbangcraft.hub.api.MatchEvent;
import com.bigbangcraft.hub.api.MatchException;
import com.bigbangcraft.hub.api.MatchFinishedEvent;
import com.bigbangcraft.hub.api.MatchId;
import com.bigbangcraft.hub.api.MatchParticipant;
import com.bigbangcraft.hub.api.MatchParticipantJoinedEvent;
import com.bigbangcraft.hub.api.MatchParticipantLeftEvent;
import com.bigbangcraft.hub.api.MatchResult;
import com.bigbangcraft.hub.api.MatchSnapshot;
import com.bigbangcraft.hub.api.MatchState;
import com.bigbangcraft.hub.api.MatchStateChangedEvent;
import com.bigbangcraft.hub.api.ParticipantRole;
import com.bigbangcraft.hub.api.ParticipantState;
import com.bigbangcraft.hub.api.PlayerAdmissionAcceptedEvent;
import com.bigbangcraft.hub.api.PlayerEliminatedEvent;
import com.bigbangcraft.hub.api.PlayerReconnectedEvent;
import com.bigbangcraft.hub.api.PartyId;
import com.bigbangcraft.hub.api.ServerId;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe authoritative in-memory registry for match sessions on the proxy/cluster.
 * Enforces network invariants:
 * - One active match per instance
 * - One active match per player
 * - Effective capacity (participants + pending admissions <= maxPlayers)
 * - Atomic state machine transitions with monotonic revisions
 * - Cleanup handshake before new match on same instance
 * - Tombstones for replay protection
 */
public final class InMemoryMatchRegistry {
    private static final int MAX_TOMBSTONES = 2000;
    private static final int MAX_RETAINED_FINISHED = 50;

    public record Tombstone(MatchId matchId, long finalRevision, MatchState finalState, Instant expiresAt) {
        public boolean isExpired(Instant now) {
            return now.isAfter(expiresAt);
        }
    }

    public static final class MatchSessionState {
        private final MatchId matchId;
        private final MatchDefinition definition;
        private final ServerId instanceId;
        private final UUID instanceSessionId;
        private final MatchStateMachine stateMachine;
        private final Instant createdAt;
        private final Map<UUID, MatchParticipant> participants = new ConcurrentHashMap<>();
        private final AtomicInteger pendingAdmissions = new AtomicInteger(0);
        private final AtomicReference<MatchResult> result = new AtomicReference<>(null);
        private volatile boolean cleanupCompleted = false;

        public MatchSessionState(MatchId matchId, MatchDefinition definition, ServerId instanceId,
                                 UUID instanceSessionId, Instant createdAt) {
            this.matchId = Objects.requireNonNull(matchId, "matchId");
            this.definition = Objects.requireNonNull(definition, "definition");
            this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
            this.instanceSessionId = Objects.requireNonNull(instanceSessionId, "instanceSessionId");
            this.stateMachine = new MatchStateMachine(matchId);
            this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        }

        public MatchId matchId() { return matchId; }
        public MatchDefinition definition() { return definition; }
        public ServerId instanceId() { return instanceId; }
        public UUID instanceSessionId() { return instanceSessionId; }
        public MatchStateMachine stateMachine() { return stateMachine; }
        public Instant createdAt() { return createdAt; }
        public boolean isCleanupCompleted() { return cleanupCompleted; }
        public void setCleanupCompleted(boolean completed) { this.cleanupCompleted = completed; }
        public Optional<MatchResult> result() { return Optional.ofNullable(result.get()); }
        public void setResult(MatchResult r) { this.result.set(r); }

        public Collection<MatchParticipant> participants() {
            return Collections.unmodifiableCollection(participants.values());
        }

        public Optional<MatchParticipant> participant(UUID playerId) {
            return Optional.ofNullable(participants.get(playerId));
        }

        public int activePlayerCount() {
            int count = 0;
            for (MatchParticipant p : participants.values()) {
                if (p.role() == ParticipantRole.PLAYER &&
                        (p.state() == ParticipantState.ACTIVE || p.state() == ParticipantState.ADMITTED
                                || p.state() == ParticipantState.RESERVED || p.state() == ParticipantState.DISCONNECTED)) {
                    count++;
                }
            }
            return count;
        }

        public int spectatorCount() {
            int count = 0;
            for (MatchParticipant p : participants.values()) {
                if (p.role() == ParticipantRole.SPECTATOR || p.state() == ParticipantState.SPECTATING) {
                    count++;
                }
            }
            return count;
        }

        public int pendingAdmissionsCount() {
            return pendingAdmissions.get();
        }

        public synchronized boolean reserveAdmission() {
            if (!stateMachine.canAcceptAdmissions(definition.allowLateJoin())) {
                return false;
            }
            int effective = definition.maxPlayers() - (activePlayerCount() + pendingAdmissions.get());
            if (effective <= 0) {
                return false;
            }
            pendingAdmissions.incrementAndGet();
            return true;
        }

        public synchronized void releasePendingAdmission() {
            pendingAdmissions.updateAndGet(current -> Math.max(0, current - 1));
        }

        private final Map<UUID, Instant> disconnectedExpirations = new ConcurrentHashMap<>();

        public synchronized MatchParticipant admit(AdmissionTicket ticket, Instant now) {
            if (!ticket.isReconnect()) {
                pendingAdmissions.updateAndGet(current -> Math.max(0, current - 1));
            }
            MatchParticipant current = participants.get(ticket.playerId());
            Instant joinedAt = (current != null) ? current.joinedAt() : now;
            Optional<PartyId> partyId = ticket.partyId().isPresent() ? ticket.partyId() : (current != null ? current.partyId() : Optional.empty());
            ParticipantRole role = (current != null) ? current.role() : ticket.role();
            MatchParticipant participant = new MatchParticipant(
                    ticket.playerId(), matchId, role, ParticipantState.ACTIVE, joinedAt, partyId);
            participants.put(ticket.playerId(), participant);
            disconnectedExpirations.remove(ticket.playerId());
            return participant;
        }

        public synchronized Optional<MatchParticipant> setDisconnected(UUID playerId, Instant expiresAt) {
            MatchParticipant current = participants.get(playerId);
            if (current == null || current.state() == ParticipantState.LEFT) {
                return Optional.empty();
            }
            MatchParticipant updated = new MatchParticipant(
                    current.playerId(), matchId, current.role(), ParticipantState.DISCONNECTED, current.joinedAt(), current.partyId());
            participants.put(playerId, updated);
            disconnectedExpirations.put(playerId, expiresAt);
            return Optional.of(updated);
        }

        public synchronized List<UUID> sweepDisconnected(Instant now) {
            List<UUID> expired = new ArrayList<>();
            for (Map.Entry<UUID, Instant> entry : disconnectedExpirations.entrySet()) {
                if (now.isAfter(entry.getValue()) || now.equals(entry.getValue())) {
                    expired.add(entry.getKey());
                }
            }
            for (UUID playerId : expired) {
                disconnectedExpirations.remove(playerId);
                removeParticipant(playerId);
            }
            return expired;
        }

        public synchronized Optional<MatchParticipant> eliminate(UUID playerId) {
            MatchParticipant current = participants.get(playerId);
            if (current == null || current.state() == ParticipantState.ELIMINATED) {
                return Optional.empty();
            }
            MatchParticipant updated = new MatchParticipant(
                    current.playerId(), matchId, current.role(), ParticipantState.ELIMINATED, current.joinedAt(), current.partyId());
            participants.put(playerId, updated);
            return Optional.of(updated);
        }

        public synchronized Optional<MatchParticipant> setSpectator(UUID playerId) {
            MatchParticipant current = participants.get(playerId);
            if (current == null) {
                return Optional.empty();
            }
            MatchParticipant updated = new MatchParticipant(
                    current.playerId(), matchId, ParticipantRole.SPECTATOR, ParticipantState.SPECTATING, current.joinedAt(), current.partyId());
            participants.put(playerId, updated);
            return Optional.of(updated);
        }

        public synchronized Optional<MatchParticipant> removeParticipant(UUID playerId) {
            MatchParticipant current = participants.remove(playerId);
            if (current == null) {
                return Optional.empty();
            }
            MatchParticipant updated = new MatchParticipant(
                    current.playerId(), matchId, current.role(), ParticipantState.LEFT, current.joinedAt(), current.partyId());
            return Optional.of(updated);
        }

        public List<MatchParticipant> participantsOfParty(PartyId partyId) {
            if (partyId == null) return List.of();
            return participants.values().stream()
                    .filter(p -> p.partyId().filter(partyId::equals).isPresent())
                    .toList();
        }

        public MatchSnapshot snapshot() {
            return new MatchSnapshot(
                    matchId,
                    definition.gameId(),
                    instanceId,
                    instanceSessionId,
                    stateMachine.state(),
                    definition.minPlayers(),
                    definition.maxPlayers(),
                    activePlayerCount(),
                    spectatorCount(),
                    pendingAdmissions.get(),
                    stateMachine.revision(),
                    definition.arenaId(),
                    createdAt,
                    stateMachine.startedAt(),
                    stateMachine.endedAt(),
                    Optional.ofNullable(result.get()));
        }
    }

    private final MatchEventBus eventBus;
    private final Duration finishedRetention;
    private final Map<MatchId, MatchSessionState> matches = new ConcurrentHashMap<>();
    private final Map<ServerId, MatchId> activeByInstance = new ConcurrentHashMap<>();
    private final Map<UUID, MatchId> activeByPlayer = new ConcurrentHashMap<>();
    private final Map<MatchId, Tombstone> tombstones = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<MatchId, Tombstone> eldest) {
            return size() > MAX_TOMBSTONES;
        }
    });
    private final List<MatchSnapshot> finishedHistory = Collections.synchronizedList(new ArrayList<>());

    public InMemoryMatchRegistry(MatchEventBus eventBus, Duration finishedRetention) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.finishedRetention = Objects.requireNonNull(finishedRetention, "finishedRetention");
    }

    public InMemoryMatchRegistry(MatchEventBus eventBus) {
        this(eventBus, Duration.ofSeconds(60));
    }

    public synchronized MatchSessionState createMatch(
            MatchId matchId, MatchDefinition definition, ServerId instanceId, UUID instanceSessionId, Instant now) {
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(instanceSessionId, "instanceSessionId");
        Objects.requireNonNull(now, "now");

        Tombstone tombstone = tombstones.get(matchId);
        if (tombstone != null && !tombstone.isExpired(now)) {
            throw new MatchException(MatchException.ErrorCode.ALREADY_FINISHED, "MatchId was recently finished: " + matchId);
        }

        MatchId existingMatchId = activeByInstance.get(instanceId);
        if (existingMatchId != null) {
            MatchSessionState existing = matches.get(existingMatchId);
            if (existing != null && !existing.stateMachine().isTerminal()) {
                throw new MatchException(MatchException.ErrorCode.ACTIVE_MATCH_EXISTS,
                        "Instance " + instanceId + " already has active match " + existingMatchId);
            }
            if (existing != null && !existing.isCleanupCompleted()) {
                throw new MatchException(MatchException.ErrorCode.ACTIVE_MATCH_EXISTS,
                        "Instance " + instanceId + " previous match " + existingMatchId + " has not completed cleanup");
            }
        }

        if (matches.containsKey(matchId)) {
            throw new MatchException(MatchException.ErrorCode.ACTIVE_MATCH_EXISTS, "Match " + matchId + " already exists");
        }

        MatchSessionState state = new MatchSessionState(matchId, definition, instanceId, instanceSessionId, now);
        matches.put(matchId, state);
        activeByInstance.put(instanceId, matchId);

        eventBus.publish(new MatchCreatedEvent(state.snapshot(), now));
        return state;
    }

    public synchronized boolean transitionState(
            MatchId matchId, UUID instanceSessionId, long expectedRevision,
            MatchState expectedState, MatchState targetState, Instant now) {
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(instanceSessionId, "instanceSessionId");
        Objects.requireNonNull(expectedState, "expectedState");
        Objects.requireNonNull(targetState, "targetState");
        Objects.requireNonNull(now, "now");

        MatchSessionState session = matches.get(matchId);
        if (session == null) {
            return false;
        }

        if (!session.instanceSessionId().equals(instanceSessionId)) {
            throw new MatchException(MatchException.ErrorCode.STALE_REVISION, "Instance sessionId mismatch");
        }

        if (session.stateMachine().revision() != expectedRevision) {
            return false;
        }

        MatchState oldState = session.stateMachine().state();
        boolean transitioned = session.stateMachine().transition(expectedState, targetState, now);
        if (transitioned) {
            eventBus.publish(new MatchStateChangedEvent(matchId, oldState, targetState, session.stateMachine().revision(), now));
        }
        return transitioned;
    }

    public synchronized boolean reserveAdmission(MatchId matchId) {
        MatchSessionState session = matches.get(matchId);
        if (session == null) return false;
        return session.reserveAdmission();
    }

    public synchronized void releasePendingAdmission(MatchId matchId) {
        MatchSessionState session = matches.get(matchId);
        if (session != null) {
            session.releasePendingAdmission();
        }
    }

    public synchronized MatchParticipant admitPlayer(AdmissionTicket ticket, Instant now) {
        Objects.requireNonNull(ticket, "ticket");
        Objects.requireNonNull(now, "now");

        MatchSessionState session = matches.get(ticket.matchId());
        if (session == null) {
            MatchId activeMatchId = activeByInstance.get(ticket.instanceId());
            if (activeMatchId != null) {
                session = matches.get(activeMatchId);
            }
            if (session == null) {
                throw new MatchException(MatchException.ErrorCode.MATCH_NOT_FOUND, "Match not found: " + ticket.matchId());
            }
        }

        MatchId existingMatch = activeByPlayer.get(ticket.playerId());
        if (existingMatch != null && !existingMatch.equals(ticket.matchId())) {
            MatchSessionState other = matches.get(existingMatch);
            if (other != null && !other.stateMachine().isTerminal()) {
                throw new MatchException(MatchException.ErrorCode.PLAYER_ALREADY_ASSIGNED,
                        "Player " + ticket.playerId() + " is already in active match " + existingMatch);
            }
        }

        if (ticket.isReconnect()) {
            if (session.stateMachine().isTerminal()) {
                throw new MatchException(MatchException.ErrorCode.ALREADY_FINISHED,
                        "Match is already terminal: " + session.stateMachine().state());
            }
        } else {
            if (!session.stateMachine().canAcceptAdmissions(session.definition().allowLateJoin())) {
                throw new MatchException(MatchException.ErrorCode.MATCH_LOCKED, "Match is not accepting admissions: " + session.stateMachine().state());
            }
        }

        MatchParticipant participant = session.admit(ticket, now);
        activeByPlayer.put(ticket.playerId(), ticket.matchId());

        eventBus.publish(new PlayerAdmissionAcceptedEvent(ticket.matchId(), ticket.playerId(), ticket.role(), now));
        if (ticket.isReconnect()) {
            eventBus.publish(new PlayerReconnectedEvent(ticket.matchId(), ticket.playerId(), now));
        } else {
            eventBus.publish(new MatchParticipantJoinedEvent(participant, now));
        }
        return participant;
    }

    public synchronized Optional<MatchParticipant> setPlayerDisconnected(MatchId matchId, UUID playerId, Instant expiresAt, Instant now) {
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(now, "now");

        MatchSessionState session = matches.get(matchId);
        if (session == null) return Optional.empty();

        return session.setDisconnected(playerId, expiresAt);
    }

    public synchronized Optional<MatchParticipant> eliminatePlayer(MatchId matchId, UUID playerId, Instant now) {
        MatchSessionState session = matches.get(matchId);
        if (session == null) return Optional.empty();

        Optional<MatchParticipant> eliminated = session.eliminate(playerId);
        eliminated.ifPresent(p -> eventBus.publish(new PlayerEliminatedEvent(matchId, playerId, now)));
        return eliminated;
    }

    public synchronized Optional<MatchParticipant> setPlayerSpectator(MatchId matchId, UUID playerId, Instant now) {
        MatchSessionState session = matches.get(matchId);
        if (session == null) return Optional.empty();
        return session.setSpectator(playerId);
    }

    public synchronized Optional<MatchParticipant> removePlayer(MatchId matchId, UUID playerId, String reason, Instant now) {
        MatchSessionState session = matches.get(matchId);
        if (session == null) return Optional.empty();

        activeByPlayer.remove(playerId, matchId);
        Optional<MatchParticipant> removed = session.removeParticipant(playerId);
        removed.ifPresent(p -> {
            eventBus.publish(new MatchParticipantLeftEvent(matchId, playerId, reason, now));
            // Auto cancel countdown if active players drop below minPlayers
            if (session.stateMachine().state() == MatchState.COUNTDOWN &&
                    session.activePlayerCount() < session.definition().minPlayers()) {
                session.stateMachine().transition(MatchState.COUNTDOWN, MatchState.WAITING, now);
                eventBus.publish(new MatchStateChangedEvent(
                        matchId, MatchState.COUNTDOWN, MatchState.WAITING, session.stateMachine().revision(), now));
            }
        });
        return removed;
    }

    public synchronized boolean finishMatch(
            MatchId matchId, UUID instanceSessionId, long expectedRevision, MatchResult result, Instant now) {
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(now, "now");

        MatchSessionState session = matches.get(matchId);
        if (session == null) return false;

        if (instanceSessionId != null && !session.instanceSessionId().equals(instanceSessionId)) {
            throw new MatchException(MatchException.ErrorCode.STALE_REVISION, "Instance sessionId mismatch on finish");
        }

        MatchState current = session.stateMachine().state();
        if (current.isTerminal()) {
            return false;
        }

        // Move to ENDING first if in IN_GAME, then FINISHED
        if (current == MatchState.IN_GAME || current == MatchState.LOCKED) {
            session.stateMachine().transition(current, MatchState.ENDING, now);
            eventBus.publish(new MatchStateChangedEvent(
                    matchId, current, MatchState.ENDING, session.stateMachine().revision(), now));
        }

        boolean finished = session.stateMachine().transition(MatchState.ENDING, MatchState.FINISHED, now);
        if (!finished) {
            // If was directly in CREATED/WAITING/COUNTDOWN, allow transition to FINISHED via force or abort
            finished = session.stateMachine().transition(session.stateMachine().state(), MatchState.FINISHED, now);
        }

        if (finished) {
            session.setResult(result);
            // Release active players
            for (MatchParticipant p : session.participants()) {
                activeByPlayer.remove(p.playerId(), matchId);
            }
            MatchSnapshot snapshot = session.snapshot();
            retainFinished(snapshot);
            eventBus.publish(new MatchFinishedEvent(snapshot, result, now));
        }
        return finished;
    }

    public synchronized boolean abortMatch(MatchId matchId, String reason, Instant now) {
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(now, "now");

        MatchSessionState session = matches.get(matchId);
        if (session == null) return false;

        if (session.stateMachine().isTerminal()) {
            return false;
        }

        MatchState previous = session.stateMachine().state();
        boolean aborted = session.stateMachine().forceAbort(now);
        if (aborted) {
            session.setResult(MatchResult.aborted(reason));
            for (MatchParticipant p : session.participants()) {
                activeByPlayer.remove(p.playerId(), matchId);
            }
            MatchSnapshot snapshot = session.snapshot();
            retainFinished(snapshot);
            eventBus.publish(new MatchStateChangedEvent(
                    matchId, previous, MatchState.ABORTED, session.stateMachine().revision(), now));
            eventBus.publish(new MatchAbortedEvent(snapshot, reason, now));
        }
        return aborted;
    }

    public synchronized boolean markInstanceReady(ServerId instanceId, MatchId matchId, Instant now) {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(now, "now");

        MatchSessionState session = matches.get(matchId);
        if (session == null) return false;

        if (!session.stateMachine().isTerminal()) {
            return false; // Can only mark ready after match is terminal
        }

        session.setCleanupCompleted(true);
        activeByInstance.remove(instanceId, matchId);

        // Record tombstone
        tombstones.put(matchId, new Tombstone(
                matchId, session.stateMachine().revision(), session.stateMachine().state(), now.plus(finishedRetention)));
        return true;
    }

    public synchronized void reconcileInstanceCrashOrShutdown(ServerId instanceId, UUID oldSessionId, Instant now) {
        MatchId active = activeByInstance.get(instanceId);
        if (active != null) {
            MatchSessionState session = matches.get(active);
            if (session != null && (oldSessionId == null || session.instanceSessionId().equals(oldSessionId))) {
                abortMatch(active, "Instance process disconnected or crashed", now);
                markInstanceReady(instanceId, active, now);
            }
        }
    }

    private void retainFinished(MatchSnapshot snapshot) {
        synchronized (finishedHistory) {
            if (finishedHistory.size() >= MAX_RETAINED_FINISHED) {
                finishedHistory.removeFirst();
            }
            finishedHistory.add(snapshot);
        }
    }

    public Optional<MatchSnapshot> find(MatchId matchId) {
        MatchSessionState session = matches.get(matchId);
        if (session != null) return Optional.of(session.snapshot());
        synchronized (finishedHistory) {
            for (MatchSnapshot s : finishedHistory) {
                if (s.matchId().equals(matchId)) return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    public Optional<MatchSessionState> findSession(MatchId matchId) {
        return Optional.ofNullable(matches.get(matchId));
    }

    public Optional<MatchSnapshot> findActiveForInstance(ServerId instanceId) {
        MatchId matchId = activeByInstance.get(instanceId);
        if (matchId == null) return Optional.empty();
        MatchSessionState session = matches.get(matchId);
        return session != null ? Optional.of(session.snapshot()) : Optional.empty();
    }

    public Optional<MatchParticipant> participant(MatchId matchId, UUID playerId) {
        if (matchId == null || playerId == null) return Optional.empty();
        MatchSessionState session = matches.get(matchId);
        if (session == null) return Optional.empty();
        return session.participant(playerId);
    }

    public Optional<MatchParticipant> participantOfPlayer(UUID playerId) {
        if (playerId == null) return Optional.empty();
        MatchId matchId = activeByPlayer.get(playerId);
        if (matchId == null) return Optional.empty();
        return participant(matchId, playerId);
    }

    public Optional<MatchSnapshot> findActiveForPlayer(UUID playerId) {
        MatchId matchId = activeByPlayer.get(playerId);
        if (matchId == null) return Optional.empty();
        MatchSessionState session = matches.get(matchId);
        return session != null ? Optional.of(session.snapshot()) : Optional.empty();
    }

    public Collection<MatchSnapshot> activeMatches() {
        List<MatchSnapshot> list = new ArrayList<>();
        for (MatchSessionState session : matches.values()) {
            if (!session.stateMachine().isTerminal()) {
                list.add(session.snapshot());
            }
        }
        return Collections.unmodifiableCollection(list);
    }

    public Collection<MatchSnapshot> activeMatchesForGame(GameId gameId) {
        Objects.requireNonNull(gameId, "gameId");
        List<MatchSnapshot> list = new ArrayList<>();
        for (MatchSessionState session : matches.values()) {
            if (!session.stateMachine().isTerminal() && session.definition().gameId().equals(gameId)) {
                list.add(session.snapshot());
            }
        }
        return Collections.unmodifiableCollection(list);
    }

    public synchronized void sweepTombstones(Instant now) {
        tombstones.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
        for (MatchSessionState s : matches.values()) {
            if (!s.stateMachine().isTerminal()) {
                List<UUID> expiredPlayers = s.sweepDisconnected(now);
                for (UUID pid : expiredPlayers) {
                    activeByPlayer.remove(pid, s.matchId());
                    eventBus.publish(new MatchParticipantLeftEvent(s.matchId(), pid, "reconnect expired", now));
                }
            }
        }
        // Remove terminal matches older than retention from active memory map
        matches.entrySet().removeIf(entry -> {
            MatchSessionState s = entry.getValue();
            if (s.stateMachine().isTerminal() && s.isCleanupCompleted()) {
                Instant ended = s.stateMachine().endedAt().orElse(s.createdAt());
                return now.isAfter(ended.plus(finishedRetention));
            }
            return false;
        });
    }

    public synchronized void clear() {
        matches.clear();
        activeByInstance.clear();
        activeByPlayer.clear();
        tombstones.clear();
        finishedHistory.clear();
    }
}
