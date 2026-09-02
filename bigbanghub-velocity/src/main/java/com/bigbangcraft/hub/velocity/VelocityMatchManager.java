package com.bigbangcraft.hub.velocity;

import com.bigbangcraft.hub.api.GameId;
import com.bigbangcraft.hub.api.InstanceSnapshot;
import com.bigbangcraft.hub.api.MatchDefinition;
import com.bigbangcraft.hub.api.MatchException;
import com.bigbangcraft.hub.api.MatchHandle;
import com.bigbangcraft.hub.api.MatchId;
import com.bigbangcraft.hub.api.MatchManager;
import com.bigbangcraft.hub.api.MatchParticipant;
import com.bigbangcraft.hub.api.MatchResult;
import com.bigbangcraft.hub.api.MatchSnapshot;
import com.bigbangcraft.hub.api.MatchState;
import com.bigbangcraft.hub.api.ReturnReason;
import com.bigbangcraft.hub.api.ServerId;
import com.bigbangcraft.hub.common.InMemoryMatchRegistry;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class VelocityMatchManager implements MatchManager {
    private final BigBangHubVelocityPlugin plugin;
    private final InMemoryMatchRegistry registry;

    public VelocityMatchManager(BigBangHubVelocityPlugin plugin, InMemoryMatchRegistry registry) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public MatchHandle create(MatchDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        // Find a healthy instance for this game with no active match
        InstanceSnapshot targetInstance = plugin.instances().instancesForGame(definition.gameId()).stream()
                .filter(inst -> inst.canAcceptPlayers() && registry.findActiveForInstance(inst.instanceId()).isEmpty())
                .findFirst()
                .orElseThrow(() -> new MatchException(MatchException.ErrorCode.INSTANCE_UNAVAILABLE,
                        "No available healthy instance found for game " + definition.gameId()));

        MatchId matchId = MatchId.random();
        InMemoryMatchRegistry.MatchSessionState state = registry.createMatch(
                matchId, definition, targetInstance.instanceId(), targetInstance.sessionId(), Instant.now());

        return new VelocityMatchHandle(state);
    }

    @Override
    public Optional<MatchHandle> currentMatch() {
        return Optional.empty(); // Velocity does not host a local minigame instance
    }

    @Override
    public Optional<MatchSnapshot> activeMatch(ServerId instanceId) {
        return registry.findActiveForInstance(instanceId);
    }

    @Override
    public Optional<MatchSnapshot> match(MatchId matchId) {
        return registry.find(matchId);
    }

    @Override
    public Collection<MatchSnapshot> activeMatches() {
        return registry.activeMatches();
    }

    @Override
    public Collection<MatchSnapshot> activeMatchesForGame(GameId gameId) {
        return registry.activeMatchesForGame(gameId);
    }

    @Override
    public Optional<MatchSnapshot> matchForPlayer(UUID playerId) {
        return registry.findActiveForPlayer(playerId);
    }

    @Override
    public CompletionStage<Void> abortMatch(MatchId matchId, String reason) {
        Objects.requireNonNull(matchId, "matchId");
        registry.abortMatch(matchId, reason, Instant.now());
        registry.find(matchId).ifPresent(snapshot -> {
            // Return players
            plugin.safeReturnPlayersToHub(
                    registry.findSession(matchId).map(s -> s.participants().stream().map(MatchParticipant::playerId).toList())
                            .orElse(java.util.List.of()),
                    ReturnReason.MATCH_ABORTED, reason);
        });
        return CompletableFuture.completedFuture(null);
    }

    private final class VelocityMatchHandle implements MatchHandle {
        private final InMemoryMatchRegistry.MatchSessionState state;

        VelocityMatchHandle(InMemoryMatchRegistry.MatchSessionState state) {
            this.state = state;
        }

        @Override public MatchId matchId() { return state.matchId(); }
        @Override public MatchSnapshot snapshot() { return state.snapshot(); }
        @Override public MatchState state() { return state.stateMachine().state(); }
        @Override public long revision() { return state.stateMachine().revision(); }
        @Override public Collection<MatchParticipant> participants() { return state.participants(); }
        @Override public Optional<MatchParticipant> participant(UUID playerId) { return state.participant(playerId); }

        @Override
        public CompletionStage<Void> open() {
            registry.transitionState(state.matchId(), state.instanceSessionId(), state.stateMachine().revision(),
                    MatchState.CREATED, MatchState.WAITING, Instant.now());
            plugin.dispatchQueue(state.definition().gameId());
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> startCountdown(Duration duration) {
            registry.transitionState(state.matchId(), state.instanceSessionId(), state.stateMachine().revision(),
                    MatchState.WAITING, MatchState.COUNTDOWN, Instant.now());
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> cancelCountdown() {
            registry.transitionState(state.matchId(), state.instanceSessionId(), state.stateMachine().revision(),
                    MatchState.COUNTDOWN, MatchState.WAITING, Instant.now());
            plugin.dispatchQueue(state.definition().gameId());
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> lock() {
            registry.transitionState(state.matchId(), state.instanceSessionId(), state.stateMachine().revision(),
                    MatchState.COUNTDOWN, MatchState.LOCKED, Instant.now());
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> start() {
            registry.transitionState(state.matchId(), state.instanceSessionId(), state.stateMachine().revision(),
                    MatchState.LOCKED, MatchState.IN_GAME, Instant.now());
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> eliminate(UUID playerId) {
            registry.eliminatePlayer(state.matchId(), playerId, Instant.now());
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> setSpectator(UUID playerId) {
            registry.setPlayerSpectator(state.matchId(), playerId, Instant.now());
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> finish(MatchResult result) {
            registry.finishMatch(state.matchId(), state.instanceSessionId(), state.stateMachine().revision(), result, Instant.now());
            plugin.safeReturnPlayersToHub(
                    state.participants().stream().map(MatchParticipant::playerId).toList(),
                    ReturnReason.MATCH_FINISHED, "Match finished");
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> abort(String reason) {
            return abortMatch(state.matchId(), reason);
        }

        @Override
        public CompletionStage<Void> markReady() {
            registry.markInstanceReady(state.instanceId(), state.matchId(), Instant.now());
            return CompletableFuture.completedFuture(null);
        }
    }
}
