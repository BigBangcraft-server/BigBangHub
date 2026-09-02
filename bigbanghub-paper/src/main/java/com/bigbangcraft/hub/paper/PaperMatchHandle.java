package com.bigbangcraft.hub.paper;

import com.bigbangcraft.hub.api.MatchDefinition;
import com.bigbangcraft.hub.api.MatchException;
import com.bigbangcraft.hub.api.MatchFinishedEvent;
import com.bigbangcraft.hub.api.MatchHandle;
import com.bigbangcraft.hub.api.MatchId;
import com.bigbangcraft.hub.api.MatchParticipant;
import com.bigbangcraft.hub.api.MatchResult;
import com.bigbangcraft.hub.api.MatchSnapshot;
import com.bigbangcraft.hub.api.MatchState;
import com.bigbangcraft.hub.api.MatchStateChangedEvent;
import com.bigbangcraft.hub.api.ParticipantRole;
import com.bigbangcraft.hub.api.ParticipantState;
import com.bigbangcraft.hub.api.PlayerEliminatedEvent;
import com.bigbangcraft.hub.api.ReturnReason;
import com.bigbangcraft.hub.common.MatchStateMachine;
import com.bigbangcraft.hub.common.MessagePayloads;
import com.bigbangcraft.hub.common.MessageType;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public final class PaperMatchHandle implements MatchHandle {
    private final PaperMatchManager manager;
    private final MatchId matchId;
    private final MatchDefinition definition;
    private final MatchStateMachine stateMachine;
    private final Instant createdAt;
    private final Map<UUID, MatchParticipant> participants = new ConcurrentHashMap<>();
    private final AtomicReference<MatchResult> result = new AtomicReference<>(null);

    public PaperMatchHandle(PaperMatchManager manager, MatchId matchId, MatchDefinition definition, Instant createdAt) {
        this.manager = Objects.requireNonNull(manager, "manager");
        this.matchId = Objects.requireNonNull(matchId, "matchId");
        this.definition = Objects.requireNonNull(definition, "definition");
        this.stateMachine = new MatchStateMachine(matchId);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    @Override
    public MatchId matchId() {
        return matchId;
    }

    @Override
    public MatchSnapshot snapshot() {
        int normalCount = 0;
        int spectatorCount = 0;
        for (MatchParticipant p : participants.values()) {
            if (p.isSpectator()) spectatorCount++;
            else if (p.isActive()) normalCount++;
        }
        return new MatchSnapshot(
                matchId,
                definition.gameId(),
                manager.instanceAgent().instanceId(),
                manager.instanceAgent().sessionId(),
                stateMachine.state(),
                definition.minPlayers(),
                definition.maxPlayers(),
                normalCount,
                spectatorCount,
                0,
                stateMachine.revision(),
                definition.arenaId(),
                createdAt,
                stateMachine.startedAt(),
                stateMachine.endedAt(),
                Optional.ofNullable(result.get()));
    }

    @Override
    public MatchState state() {
        return stateMachine.state();
    }

    @Override
    public long revision() {
        return stateMachine.revision();
    }

    @Override
    public Collection<MatchParticipant> participants() {
        return Collections.unmodifiableCollection(participants.values());
    }

    @Override
    public Optional<MatchParticipant> participant(UUID playerId) {
        return Optional.ofNullable(participants.get(playerId));
    }

    public void addParticipant(MatchParticipant participant) {
        participants.put(participant.playerId(), participant);
    }

    public void removeParticipant(UUID playerId) {
        participants.remove(playerId);
    }

    @Override
    public CompletionStage<Void> open() {
        MatchState current = stateMachine.state();
        if (!stateMachine.transition(MatchState.CREATED, MatchState.WAITING, Instant.now())) {
            return CompletableFuture.failedFuture(new MatchException(
                    MatchException.ErrorCode.INVALID_TRANSITION, "Cannot transition from " + current + " to WAITING"));
        }
        manager.instanceAgent().updateState(com.bigbangcraft.hub.api.GameState.WAITING, true);
        sendStateChange(MatchState.WAITING);
        manager.eventBus().publish(new MatchStateChangedEvent(matchId, MatchState.CREATED, MatchState.WAITING, stateMachine.revision()));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> startCountdown(Duration duration) {
        MatchState current = stateMachine.state();
        if (!stateMachine.transition(MatchState.WAITING, MatchState.COUNTDOWN, Instant.now())) {
            return CompletableFuture.failedFuture(new MatchException(
                    MatchException.ErrorCode.INVALID_TRANSITION, "Cannot transition from " + current + " to COUNTDOWN"));
        }
        sendStateChange(MatchState.COUNTDOWN);
        manager.eventBus().publish(new MatchStateChangedEvent(matchId, MatchState.WAITING, MatchState.COUNTDOWN, stateMachine.revision()));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> cancelCountdown() {
        MatchState current = stateMachine.state();
        if (!stateMachine.transition(MatchState.COUNTDOWN, MatchState.WAITING, Instant.now())) {
            return CompletableFuture.failedFuture(new MatchException(
                    MatchException.ErrorCode.INVALID_TRANSITION, "Cannot transition from " + current + " to WAITING"));
        }
        sendStateChange(MatchState.WAITING);
        manager.eventBus().publish(new MatchStateChangedEvent(matchId, MatchState.COUNTDOWN, MatchState.WAITING, stateMachine.revision()));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> lock() {
        MatchState current = stateMachine.state();
        if (!stateMachine.transition(MatchState.COUNTDOWN, MatchState.LOCKED, Instant.now())) {
            return CompletableFuture.failedFuture(new MatchException(
                    MatchException.ErrorCode.INVALID_TRANSITION, "Cannot transition from " + current + " to LOCKED"));
        }
        manager.instanceAgent().setAcceptingPlayers(false);
        sendStateChange(MatchState.LOCKED);
        manager.eventBus().publish(new MatchStateChangedEvent(matchId, MatchState.COUNTDOWN, MatchState.LOCKED, stateMachine.revision()));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> start() {
        MatchState current = stateMachine.state();
        if (!stateMachine.transition(MatchState.LOCKED, MatchState.IN_GAME, Instant.now())) {
            return CompletableFuture.failedFuture(new MatchException(
                    MatchException.ErrorCode.INVALID_TRANSITION, "Cannot transition from " + current + " to IN_GAME"));
        }
        manager.instanceAgent().updateState(com.bigbangcraft.hub.api.GameState.IN_GAME, false);
        sendStateChange(MatchState.IN_GAME);
        manager.eventBus().publish(new MatchStateChangedEvent(matchId, MatchState.LOCKED, MatchState.IN_GAME, stateMachine.revision()));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> eliminate(UUID playerId) {
        MatchParticipant current = participants.get(playerId);
        if (current == null) return CompletableFuture.completedFuture(null);

        MatchParticipant updated = new MatchParticipant(
                playerId, matchId, current.role(), ParticipantState.ELIMINATED, current.joinedAt());
        participants.put(playerId, updated);
        manager.bridge().sendAny(MessageType.PARTICIPANT_STATE_CHANGE,
                MessagePayloads.participantStateChange(new MessagePayloads.ParticipantStateChange(
                        matchId, playerId, MessagePayloads.ParticipantRoleWire.PLAYER, MessagePayloads.ParticipantStateWire.ELIMINATED)));
        manager.eventBus().publish(new PlayerEliminatedEvent(matchId, playerId));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> setSpectator(UUID playerId) {
        MatchParticipant current = participants.get(playerId);
        if (current == null) return CompletableFuture.completedFuture(null);

        MatchParticipant updated = new MatchParticipant(
                playerId, matchId, ParticipantRole.SPECTATOR, ParticipantState.SPECTATING, current.joinedAt());
        participants.put(playerId, updated);
        manager.bridge().sendAny(MessageType.PARTICIPANT_STATE_CHANGE,
                MessagePayloads.participantStateChange(new MessagePayloads.ParticipantStateChange(
                        matchId, playerId, MessagePayloads.ParticipantRoleWire.SPECTATOR, MessagePayloads.ParticipantStateWire.SPECTATING)));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> finish(MatchResult matchResult) {
        Objects.requireNonNull(matchResult, "matchResult");
        this.result.set(matchResult);
        MatchState current = stateMachine.state();
        stateMachine.transition(current, MatchState.ENDING, Instant.now());
        manager.instanceAgent().updateState(com.bigbangcraft.hub.api.GameState.ENDING, false);
        manager.eventBus().publish(new MatchStateChangedEvent(matchId, current, MatchState.ENDING, stateMachine.revision()));

        MessagePayloads.MatchResultOutcomeWire outcomeWire = switch (matchResult.outcome()) {
            case WIN -> MessagePayloads.MatchResultOutcomeWire.WIN;
            case DRAW -> MessagePayloads.MatchResultOutcomeWire.DRAW;
            case ABORTED -> MessagePayloads.MatchResultOutcomeWire.ABORTED;
        };

        manager.bridge().sendAny(MessageType.MATCH_FINISH, MessagePayloads.matchFinish(new MessagePayloads.MatchFinish(
                manager.instanceAgent().instanceId(),
                manager.instanceAgent().sessionId(),
                matchId,
                stateMachine.revision(),
                outcomeWire,
                matchResult.duration().toMillis(),
                matchResult.winnerIds().stream().toList(),
                "")));

        stateMachine.transition(MatchState.ENDING, MatchState.FINISHED, Instant.now());
        manager.eventBus().publish(new MatchFinishedEvent(snapshot(), matchResult));

        safeReturnAllPlayers(ReturnReason.MATCH_FINISHED, "Partida finalizada.");
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> abort(String reason) {
        MatchState current = stateMachine.state();
        stateMachine.forceAbort(Instant.now());
        manager.instanceAgent().updateState(com.bigbangcraft.hub.api.GameState.ENDING, false);
        manager.eventBus().publish(new MatchStateChangedEvent(matchId, current, MatchState.ABORTED, stateMachine.revision()));

        manager.bridge().sendAny(MessageType.MATCH_ABORT, MessagePayloads.matchAbort(new MessagePayloads.MatchAbort(
                manager.instanceAgent().instanceId(),
                manager.instanceAgent().sessionId(),
                matchId,
                stateMachine.revision(),
                reason != null ? reason : "Aborted")));

        safeReturnAllPlayers(ReturnReason.MATCH_ABORTED, reason != null ? reason : "Partida abortada.");
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> markReady() {
        manager.bridge().sendAny(MessageType.INSTANCE_READY, MessagePayloads.instanceReady(new MessagePayloads.InstanceReady(
                manager.instanceAgent().instanceId(),
                manager.instanceAgent().sessionId(),
                matchId)));

        manager.onMatchReady(this);
        return CompletableFuture.completedFuture(null);
    }

    private void sendStateChange(MatchState nextState) {
        MessagePayloads.MatchStateWire wire = switch (nextState) {
            case CREATED -> MessagePayloads.MatchStateWire.CREATED;
            case WAITING -> MessagePayloads.MatchStateWire.WAITING;
            case COUNTDOWN -> MessagePayloads.MatchStateWire.COUNTDOWN;
            case LOCKED -> MessagePayloads.MatchStateWire.LOCKED;
            case IN_GAME -> MessagePayloads.MatchStateWire.IN_GAME;
            case ENDING -> MessagePayloads.MatchStateWire.ENDING;
            case FINISHED -> MessagePayloads.MatchStateWire.FINISHED;
            case ABORTED -> MessagePayloads.MatchStateWire.ABORTED;
        };
        manager.bridge().sendAny(MessageType.MATCH_STATE_CHANGE,
                MessagePayloads.matchStateChange(new MessagePayloads.MatchStateChange(
                        manager.instanceAgent().instanceId(),
                        manager.instanceAgent().sessionId(),
                        matchId,
                        stateMachine.revision(),
                        wire)));
    }

    private void safeReturnAllPlayers(ReturnReason reason, String message) {
        Bukkit.getScheduler().runTaskLater(manager.plugin(), () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                manager.transfers().returnToHub(player.getUniqueId(), reason, message);
            }
        }, 40L);
    }
}
