package com.bigbangcraft.hub.paper;

import com.bigbangcraft.hub.api.GameId;
import com.bigbangcraft.hub.api.GameState;
import com.bigbangcraft.hub.api.InstanceHealth;
import com.bigbangcraft.hub.api.InstanceService;
import com.bigbangcraft.hub.api.ServerId;
import com.bigbangcraft.hub.common.InstanceAgentSettings;
import com.bigbangcraft.hub.common.MessagePayloads;
import com.bigbangcraft.hub.common.MessageType;
import com.bigbangcraft.hub.common.ProtocolValidationException;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class PaperInstanceAgent implements InstanceService {
    private final BigBangHubPaperPlugin plugin;
    private final VelocityBridge bridge;
    private final InstanceAgentSettings settings;
    private final UUID sessionId = UUID.randomUUID();
    private final AtomicReference<GameState> state = new AtomicReference<>(GameState.WAITING);
    private final AtomicBoolean acceptingPlayers = new AtomicBoolean(true);
    private final AtomicInteger minPlayers = new AtomicInteger(2);
    private final AtomicInteger maxPlayers = new AtomicInteger(10);
    private final AtomicInteger customPlayerCount = new AtomicInteger(-1);
    private final AtomicBoolean registered = new AtomicBoolean(false);
    private BukkitTask heartbeatTask;

    public PaperInstanceAgent(BigBangHubPaperPlugin plugin, VelocityBridge bridge, InstanceAgentSettings settings) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.minPlayers.set(settings.minPlayers());
        this.maxPlayers.set(settings.maxPlayers());
        this.acceptingPlayers.set(settings.acceptingPlayers());
    }

    public void start() {
        long ticks = Math.max(20L, settings.heartbeatInterval().toMillis() / 50L);
        heartbeatTask = Bukkit.getScheduler().runTaskTimer(plugin, this::pulse, ticks, ticks);
        Bukkit.getScheduler().runTaskLater(plugin, this::sendRegister, 10L);
    }

    public void stop() {
        if (heartbeatTask != null) heartbeatTask.cancel();
        sendUnregister("Server shutting down");
    }

    public void pulse() {
        if (!registered.get()) {
            sendRegister();
        } else {
            sendHeartbeat();
        }
    }

    public void sendRegister() {
        int players = playerCount();
        MessagePayloads.InstanceRegister payload = new MessagePayloads.InstanceRegister(
                settings.instanceId(),
                settings.gameId(),
                settings.serverName(),
                sessionId,
                toWire(state.get()),
                players,
                minPlayers.get(),
                maxPlayers.get(),
                acceptingPlayers.get());
        bridge.sendAny(MessageType.INSTANCE_REGISTER, MessagePayloads.instanceRegister(payload));
    }

    public void sendHeartbeat() {
        int players = playerCount();
        MessagePayloads.InstanceHeartbeat payload = new MessagePayloads.InstanceHeartbeat(
                settings.instanceId(),
                sessionId,
                toWire(state.get()),
                players,
                maxPlayers.get(),
                acceptingPlayers.get());
        bridge.sendAny(MessageType.INSTANCE_HEARTBEAT, MessagePayloads.instanceHeartbeat(payload));
    }

    public void sendStateChange() {
        int players = playerCount();
        MessagePayloads.InstanceStateChange payload = new MessagePayloads.InstanceStateChange(
                settings.instanceId(),
                sessionId,
                toWire(state.get()),
                acceptingPlayers.get(),
                players,
                maxPlayers.get());
        bridge.sendAny(MessageType.INSTANCE_STATE_CHANGE, MessagePayloads.instanceStateChange(payload));
    }

    public void sendUnregister(String reason) {
        MessagePayloads.InstanceUnregister payload = new MessagePayloads.InstanceUnregister(
                settings.instanceId(),
                sessionId,
                reason);
        bridge.sendAny(MessageType.INSTANCE_UNREGISTER, MessagePayloads.instanceUnregister(payload));
    }

    public void onRegisterAck(byte[] payloadBytes) {
        try {
            MessagePayloads.InstanceRegisterAck ack = MessagePayloads.instanceRegisterAck(payloadBytes);
            if (ack.instanceId().equals(settings.instanceId()) && ack.sessionId().equals(sessionId)) {
                if (ack.success()) {
                    registered.set(true);
                    plugin.getLogger().info("Successfully registered runtime instance with Velocity ("
                            + settings.instanceId() + ")");
                    if (plugin.matchManager() != null && plugin.configSnapshot().match().autoCreateMatch()) {
                        plugin.matchManager().autoCreateAndOpen();
                    }
                } else {
                    plugin.getLogger().warning("Registration rejected by Velocity: " + ack.message());
                }
            }
        } catch (ProtocolValidationException e) {
            plugin.getLogger().warning("Invalid register ack: " + e.getMessage());
        }
    }

    @Override
    public ServerId instanceId() {
        return settings.instanceId();
    }

    @Override
    public GameId gameId() {
        return settings.gameId();
    }

    @Override
    public UUID sessionId() {
        return sessionId;
    }

    public boolean isRegistered() {
        return registered.get();
    }

    @Override
    public GameState state() {
        return state.get();
    }

    @Override
    public InstanceHealth health() {
        return InstanceHealth.HEALTHY;
    }

    @Override
    public int playerCount() {
        int custom = customPlayerCount.get();
        if (custom >= 0) return custom;
        return Bukkit.getOnlinePlayers().size();
    }

    @Override
    public int minPlayers() {
        return minPlayers.get();
    }

    @Override
    public int maxPlayers() {
        return maxPlayers.get();
    }

    @Override
    public boolean isAcceptingPlayers() {
        return acceptingPlayers.get();
    }

    @Override
    public void setState(GameState newState) {
        Objects.requireNonNull(newState, "newState");
        GameState old = this.state.getAndSet(newState);
        if (old != newState && registered.get()) {
            sendStateChange();
        }
    }

    @Override
    public void setAcceptingPlayers(boolean accepting) {
        boolean old = this.acceptingPlayers.getAndSet(accepting);
        if (old != accepting && registered.get()) {
            sendStateChange();
        }
    }

    @Override
    public void setPlayerCount(int playerCount) {
        if (playerCount < 0) throw new IllegalArgumentException("Player count cannot be negative");
        this.customPlayerCount.set(playerCount);
        if (registered.get()) {
            sendHeartbeat();
        }
    }

    @Override
    public void setMaxPlayers(int maxPlayers) {
        if (maxPlayers < 1) throw new IllegalArgumentException("Max players must be at least 1");
        this.maxPlayers.set(maxPlayers);
        if (registered.get()) {
            sendStateChange();
        }
    }

    @Override
    public void updateState(GameState state, boolean acceptingPlayers) {
        Objects.requireNonNull(state, "state");
        this.state.set(state);
        this.acceptingPlayers.set(acceptingPlayers);
        if (registered.get()) {
            sendStateChange();
        }
    }

    @Override
    public void updateCapacity(int playerCount, int maxPlayers) {
        if (playerCount < 0 || maxPlayers < 1) {
            throw new IllegalArgumentException("Invalid capacity: players=" + playerCount + ", max=" + maxPlayers);
        }
        this.customPlayerCount.set(playerCount);
        this.maxPlayers.set(maxPlayers);
        if (registered.get()) {
            sendStateChange();
        }
    }

    private static MessagePayloads.GameStateWire toWire(GameState state) {
        return switch (state) {
            case STARTING -> MessagePayloads.GameStateWire.STARTING;
            case WAITING -> MessagePayloads.GameStateWire.WAITING;
            case STARTING_GAME -> MessagePayloads.GameStateWire.STARTING_GAME;
            case IN_GAME -> MessagePayloads.GameStateWire.IN_GAME;
            case ENDING -> MessagePayloads.GameStateWire.ENDING;
            case FULL -> MessagePayloads.GameStateWire.FULL;
            case MAINTENANCE -> MessagePayloads.GameStateWire.MAINTENANCE;
            case OFFLINE -> MessagePayloads.GameStateWire.OFFLINE;
        };
    }
}
