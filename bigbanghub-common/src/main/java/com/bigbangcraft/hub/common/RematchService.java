package com.bigbangcraft.hub.common;

import com.bigbangcraft.hub.api.GameId;
import com.bigbangcraft.hub.api.MatchId;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class RematchService {

    public record RematchVoteResult(
            boolean consensusReached,
            int currentVotes,
            int requiredVotes,
            Collection<UUID> participants,
            GameId gameId) { }

    public static class RematchSession {
        private final MatchId matchId;
        private final GameId gameId;
        private final Set<UUID> eligiblePlayers;
        private final Set<UUID> votedPlayers = ConcurrentHashMap.newKeySet();
        private final Instant expiresAt;

        public RematchSession(MatchId matchId, GameId gameId, Collection<UUID> eligiblePlayers, Instant expiresAt) {
            this.matchId = Objects.requireNonNull(matchId, "matchId");
            this.gameId = Objects.requireNonNull(gameId, "gameId");
            this.eligiblePlayers = Set.copyOf(eligiblePlayers);
            this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        }

        public MatchId matchId() {
            return matchId;
        }

        public GameId gameId() {
            return gameId;
        }

        public Set<UUID> eligiblePlayers() {
            return eligiblePlayers;
        }

        public Set<UUID> votedPlayers() {
            return Collections.unmodifiableSet(votedPlayers);
        }

        public Instant expiresAt() {
            return expiresAt;
        }

        public synchronized boolean vote(UUID playerId) {
            if (!eligiblePlayers.contains(playerId)) {
                return false;
            }
            return votedPlayers.add(playerId);
        }

        public synchronized boolean hasConsensus() {
            return !eligiblePlayers.isEmpty() && votedPlayers.size() >= eligiblePlayers.size();
        }

        public boolean isExpired(Instant now) {
            return now.isAfter(expiresAt) || now.equals(expiresAt);
        }
    }

    private final Map<MatchId, RematchSession> sessionsByMatch = new ConcurrentHashMap<>();
    private final Map<UUID, MatchId> matchByPlayer = new ConcurrentHashMap<>();

    public synchronized RematchSession createSession(
            MatchId matchId, GameId gameId, Collection<UUID> eligiblePlayers, Duration timeout, Instant now) {
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(gameId, "gameId");
        Objects.requireNonNull(eligiblePlayers, "eligiblePlayers");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(now, "now");

        RematchSession session = new RematchSession(matchId, gameId, eligiblePlayers, now.plus(timeout));
        sessionsByMatch.put(matchId, session);
        for (UUID pid : eligiblePlayers) {
            matchByPlayer.put(pid, matchId);
        }
        return session;
    }

    public synchronized Optional<RematchVoteResult> vote(UUID playerId, Instant now) {
        MatchId matchId = matchByPlayer.get(playerId);
        if (matchId == null) {
            return Optional.empty();
        }

        RematchSession session = sessionsByMatch.get(matchId);
        if (session == null || session.isExpired(now)) {
            matchByPlayer.remove(playerId);
            return Optional.empty();
        }

        session.vote(playerId);
        boolean consensus = session.hasConsensus();
        RematchVoteResult result = new RematchVoteResult(
                consensus,
                session.votedPlayers().size(),
                session.eligiblePlayers().size(),
                session.eligiblePlayers(),
                session.gameId());

        if (consensus) {
            sessionsByMatch.remove(matchId);
            for (UUID pid : session.eligiblePlayers()) {
                matchByPlayer.remove(pid);
            }
        }
        return Optional.of(result);
    }

    public synchronized Optional<MatchId> activeSessionForPlayer(UUID playerId, Instant now) {
        MatchId matchId = matchByPlayer.get(playerId);
        if (matchId == null) {
            return Optional.empty();
        }

        RematchSession session = sessionsByMatch.get(matchId);
        if (session == null || session.isExpired(now)) {
            matchByPlayer.remove(playerId);
            return Optional.empty();
        }
        return Optional.of(matchId);
    }

    public synchronized Optional<RematchSession> findSession(MatchId matchId) {
        return Optional.ofNullable(sessionsByMatch.get(matchId));
    }

    public synchronized void removePlayer(UUID playerId) {
        matchByPlayer.remove(playerId);
    }

    public synchronized List<RematchSession> sweep(Instant now) {
        List<RematchSession> expired = new ArrayList<>();
        Iterator<Map.Entry<MatchId, RematchSession>> it = sessionsByMatch.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<MatchId, RematchSession> entry = it.next();
            if (entry.getValue().isExpired(now)) {
                expired.add(entry.getValue());
                for (UUID pid : entry.getValue().eligiblePlayers()) {
                    matchByPlayer.remove(pid);
                }
                it.remove();
            }
        }
        return expired;
    }
}
