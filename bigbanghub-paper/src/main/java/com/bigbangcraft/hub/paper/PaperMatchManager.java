package com.bigbangcraft.hub.paper;

import com.bigbangcraft.hub.api.GameId;
import com.bigbangcraft.hub.api.MatchCreatedEvent;
import com.bigbangcraft.hub.api.MatchDefinition;
import com.bigbangcraft.hub.api.MatchException;
import com.bigbangcraft.hub.api.MatchHandle;
import com.bigbangcraft.hub.api.MatchId;
import com.bigbangcraft.hub.api.MatchManager;
import com.bigbangcraft.hub.api.MatchParticipant;
import com.bigbangcraft.hub.api.MatchParticipantJoinedEvent;
import com.bigbangcraft.hub.api.MatchParticipantLeftEvent;
import com.bigbangcraft.hub.api.MatchSnapshot;
import com.bigbangcraft.hub.api.MatchState;
import com.bigbangcraft.hub.api.ParticipantRole;
import com.bigbangcraft.hub.api.ParticipantState;
import com.bigbangcraft.hub.api.PlayerAdmissionAcceptedEvent;
import com.bigbangcraft.hub.api.PlayerAdmissionRejectedEvent;
import com.bigbangcraft.hub.api.PlayerReconnectedEvent;
import com.bigbangcraft.hub.api.ReturnReason;
import com.bigbangcraft.hub.api.ServerId;
import com.bigbangcraft.hub.common.MatchEventBus;
import com.bigbangcraft.hub.common.MessagePayloads;
import com.bigbangcraft.hub.common.MessageType;
import com.bigbangcraft.hub.common.ProtocolValidationException;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

public final class PaperMatchManager implements MatchManager {
    private final BigBangHubPaperPlugin plugin;
    private final VelocityBridge bridge;
    private final PaperTransferService transfers;
    private final PaperInstanceAgent instanceAgent;
    private final MatchEventBus eventBus = new MatchEventBus();
    private final AtomicReference<PaperMatchHandle> currentMatch = new AtomicReference<>(null);
    private final boolean autoCreateMatch;

    public PaperMatchManager(BigBangHubPaperPlugin plugin, VelocityBridge bridge,
                             PaperTransferService transfers, PaperInstanceAgent instanceAgent,
                             boolean autoCreateMatch) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.transfers = Objects.requireNonNull(transfers, "transfers");
        this.instanceAgent = Objects.requireNonNull(instanceAgent, "instanceAgent");
        this.autoCreateMatch = autoCreateMatch;
    }

    public BigBangHubPaperPlugin plugin() { return plugin; }
    public VelocityBridge bridge() { return bridge; }
    public PaperTransferService transfers() { return transfers; }
    public PaperInstanceAgent instanceAgent() { return instanceAgent; }
    public MatchEventBus eventBus() { return eventBus; }

    @Override
    public MatchHandle create(MatchDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        PaperMatchHandle existing = currentMatch.get();
        if (existing != null && !existing.state().isTerminal()) {
            throw new MatchException(MatchException.ErrorCode.ACTIVE_MATCH_EXISTS,
                    "An active match already exists on this server: " + existing.matchId());
        }

        MatchId matchId = MatchId.random();
        PaperMatchHandle handle = new PaperMatchHandle(this, matchId, definition, Instant.now());
        currentMatch.set(handle);

        bridge.sendAny(MessageType.MATCH_CREATE, MessagePayloads.matchCreate(new MessagePayloads.MatchCreate(
                instanceAgent.instanceId(),
                instanceAgent.sessionId(),
                matchId,
                definition.gameId(),
                definition.minPlayers(),
                definition.maxPlayers(),
                definition.allowLateJoin(),
                definition.arenaId().orElse(""))));

        eventBus.publish(new MatchCreatedEvent(handle.snapshot()));
        return handle;
    }

    @Override
    public Optional<MatchHandle> currentMatch() {
        return Optional.ofNullable(currentMatch.get());
    }

    @Override
    public Optional<MatchSnapshot> activeMatch(ServerId instanceId) {
        PaperMatchHandle handle = currentMatch.get();
        return (handle != null && handle.state() != MatchState.FINISHED && handle.state() != MatchState.ABORTED)
                ? Optional.of(handle.snapshot()) : Optional.empty();
    }

    @Override
    public Optional<MatchSnapshot> match(MatchId matchId) {
        PaperMatchHandle handle = currentMatch.get();
        return (handle != null && handle.matchId().equals(matchId)) ? Optional.of(handle.snapshot()) : Optional.empty();
    }

    @Override
    public Collection<MatchSnapshot> activeMatches() {
        PaperMatchHandle handle = currentMatch.get();
        return (handle != null && !handle.state().isTerminal()) ? List.of(handle.snapshot()) : Collections.emptyList();
    }

    @Override
    public Collection<MatchSnapshot> activeMatchesForGame(GameId gameId) {
        PaperMatchHandle handle = currentMatch.get();
        return (handle != null && !handle.state().isTerminal() && handle.snapshot().gameId().equals(gameId))
                ? List.of(handle.snapshot()) : Collections.emptyList();
    }

    @Override
    public Optional<MatchSnapshot> matchForPlayer(UUID playerId) {
        PaperMatchHandle handle = currentMatch.get();
        return (handle != null && handle.participant(playerId).isPresent())
                ? Optional.of(handle.snapshot()) : Optional.empty();
    }

    @Override
    public CompletionStage<Void> abortMatch(MatchId matchId, String reason) {
        PaperMatchHandle handle = currentMatch.get();
        if (handle != null && handle.matchId().equals(matchId)) {
            return handle.abort(reason);
        }
        return CompletableFuture.completedFuture(null);
    }

    public void onMatchReady(PaperMatchHandle handle) {
        if (currentMatch.compareAndSet(handle, null)) {
            plugin.getLogger().info("Match " + handle.matchId() + " marked ready and cleaned up.");
            if (autoCreateMatch) {
                Bukkit.getScheduler().runTaskLater(plugin, this::autoCreateAndOpen, 20L);
            }
        }
    }

    public void autoCreateAndOpen() {
        if (currentMatch.get() != null) return;
        MatchDefinition def = MatchDefinition.builder()
                .gameId(instanceAgent.gameId())
                .minPlayers(instanceAgent.minPlayers())
                .maxPlayers(instanceAgent.maxPlayers())
                .build();
        MatchHandle match = create(def);
        match.open();
        plugin.getLogger().info("Auto-created and opened new match: " + match.matchId());
    }

    public void handlePlayerJoin(Player player) {
        PaperMatchHandle handle = currentMatch.get();
        if (handle == null) {
            player.sendMessage("§cNenhuma partida aberta no momento. Retornando ao Hub...");
            transfers.returnToHub(player.getUniqueId(), ReturnReason.DIRECT_JOIN_REJECTED, "No active match on server")
                    .thenAccept(res -> {
                        if (!res.success()) {
                            Bukkit.getScheduler().runTask(plugin, () ->
                                    player.kick(net.kyori.adventure.text.Component.text("§cEntrada direta não autorizada: Nenhuma partida aberta.")));
                        }
                    });
            return;
        }

        if (!handle.state().canAcceptAdmissions(handle.snapshot().effectiveCapacity() > 0)) {
            player.sendMessage("§cA partida não está aceitando novos jogadores. Retornando ao Hub...");
            transfers.returnToHub(player.getUniqueId(), ReturnReason.DIRECT_JOIN_REJECTED, "Match not joinable")
                    .thenAccept(res -> {
                        if (!res.success()) {
                            Bukkit.getScheduler().runTask(plugin, () ->
                                    player.kick(net.kyori.adventure.text.Component.text("§cEntrada direta não autorizada: Partida fechada.")));
                        }
                    });
            return;
        }

        // Send admission validation request to Velocity
        MessagePayloads.AdmissionRequest req = new MessagePayloads.AdmissionRequest(
                UUID.randomUUID(), player.getUniqueId(), handle.matchId(), instanceAgent.instanceId(), "");
        bridge.request(player.getUniqueId(), MessageType.ADMISSION_REQUEST, MessagePayloads.admissionRequest(req))
                .thenAccept(envelope -> {
                    try {
                        MessagePayloads.AdmissionResponse response = MessagePayloads.admissionResponse(envelope.payload());
                        if (response.accepted()) {
                            ParticipantRole role = (response.role() == MessagePayloads.ParticipantRoleWire.SPECTATOR)
                                    ? ParticipantRole.SPECTATOR : ParticipantRole.PLAYER;
                            MatchParticipant participant = new MatchParticipant(
                                    player.getUniqueId(), handle.matchId(), role, ParticipantState.ACTIVE, Instant.now(), response.partyId());
                            handle.addParticipant(participant);
                            if (response.isReconnect()) {
                                eventBus.publish(new PlayerReconnectedEvent(handle.matchId(), player.getUniqueId(), Instant.now()));
                                player.sendMessage("§aReconexão confirmada na partida " + handle.matchId().value().substring(0, 8) + "!");
                            } else {
                                eventBus.publish(new PlayerAdmissionAcceptedEvent(handle.matchId(), player.getUniqueId(), role));
                                eventBus.publish(new MatchParticipantJoinedEvent(participant));
                                player.sendMessage("§aEntrada confirmada na partida " + handle.matchId().value().substring(0, 8) + "!");
                            }
                        } else {
                            eventBus.publish(new PlayerAdmissionRejectedEvent(handle.matchId(), player.getUniqueId(), response.reason()));
                            player.sendMessage("§cEntrada direta não autorizada: " + response.reason() + ". Retornando ao Hub...");
                            transfers.returnToHub(player.getUniqueId(), ReturnReason.DIRECT_JOIN_REJECTED, response.reason())
                                    .thenAccept(res -> {
                                        if (!res.success()) {
                                            Bukkit.getScheduler().runTask(plugin, () ->
                                                    player.kick(net.kyori.adventure.text.Component.text("§cEntrada direta não autorizada: " + response.reason())));
                                        }
                                    });
                        }
                    } catch (ProtocolValidationException e) {
                        player.sendMessage("§cErro na validação de entrada. Retornando ao Hub...");
                        transfers.returnToHub(player.getUniqueId(), ReturnReason.DIRECT_JOIN_REJECTED, "Validation protocol error")
                                .thenAccept(res -> {
                                    if (!res.success()) {
                                        Bukkit.getScheduler().runTask(plugin, () ->
                                                player.kick(net.kyori.adventure.text.Component.text("§cErro na validação de entrada.")));
                                    }
                                });
                    }
                })
                .exceptionally(err -> {
                    player.sendMessage("§cTimeout ao validar entrada com proxy. Retornando ao Hub...");
                    transfers.returnToHub(player.getUniqueId(), ReturnReason.DIRECT_JOIN_REJECTED, "Admission timeout")
                            .thenAccept(res -> {
                                if (!res.success()) {
                                    Bukkit.getScheduler().runTask(plugin, () ->
                                            player.kick(net.kyori.adventure.text.Component.text("§cTimeout ao validar entrada com proxy.")));
                                }
                            });
                    return null;
                });
    }

    public void handlePlayerQuit(Player player) {
        PaperMatchHandle handle = currentMatch.get();
        if (handle != null) {
            if (!handle.state().isTerminal()) {
                handle.setDisconnected(player.getUniqueId());
                bridge.sendAny(MessageType.PARTICIPANT_STATE_CHANGE,
                        MessagePayloads.participantStateChange(new MessagePayloads.ParticipantStateChange(
                                handle.matchId(), player.getUniqueId(),
                                MessagePayloads.ParticipantRoleWire.PLAYER, MessagePayloads.ParticipantStateWire.DISCONNECTED)));
            } else {
                handle.removeParticipant(player.getUniqueId());
                bridge.sendAny(MessageType.PARTICIPANT_STATE_CHANGE,
                        MessagePayloads.participantStateChange(new MessagePayloads.ParticipantStateChange(
                                handle.matchId(), player.getUniqueId(),
                                MessagePayloads.ParticipantRoleWire.PLAYER, MessagePayloads.ParticipantStateWire.LEFT)));
                eventBus.publish(new MatchParticipantLeftEvent(handle.matchId(), player.getUniqueId(), "disconnected"));
            }
        }
    }
}
