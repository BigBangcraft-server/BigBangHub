package com.bigbangcraft.hub.paper;

import com.bigbangcraft.hub.common.MessageType;
import com.bigbangcraft.hub.common.ProtocolCodec;
import com.bigbangcraft.hub.common.ProtocolEnvelope;
import com.bigbangcraft.hub.common.ProtocolValidationException;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

final class VelocityBridge implements PluginMessageListener {
    private static final long REQUEST_TIMEOUT_TICKS = 100L;
    private final BigBangHubPaperPlugin plugin;
    private final String channel;
    private final ProtocolCodec codec;
    private final Map<UUID, CompletableFuture<ProtocolEnvelope>> pending = new ConcurrentHashMap<>();

    VelocityBridge(BigBangHubPaperPlugin plugin, String channel, ProtocolCodec codec) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.codec = Objects.requireNonNull(codec, "codec");
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, channel);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, channel, this);
    }

    CompletionStage<ProtocolEnvelope> request(UUID playerId, MessageType type, byte[] payload) {
        Objects.requireNonNull(playerId, "playerId");
        UUID correlation = UUID.randomUUID();
        CompletableFuture<ProtocolEnvelope> future = new CompletableFuture<>();
        pending.put(correlation, future);
        Runnable send = () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                fail(correlation, new IllegalStateException("Player is no longer online"));
                return;
            }
            try {
                plugin.getLogger().info("VelocityBridge: Sending request " + type + " correlation " + correlation + " for player " + player.getName());
                player.sendPluginMessage(plugin, channel,
                        codec.encode(new ProtocolEnvelope(ProtocolCodec.PROTOCOL_VERSION, type, correlation, payload)));
            } catch (RuntimeException exception) {
                fail(correlation, exception);
            }
        };
        if (Bukkit.isPrimaryThread()) send.run();
        else Bukkit.getScheduler().runTask(plugin, send);
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> fail(correlation, new IllegalStateException("Velocity request timed out")), REQUEST_TIMEOUT_TICKS);
        return future;
    }

    CompletionStage<ProtocolEnvelope> requestAny(MessageType type, byte[] payload) {
        UUID correlation = UUID.randomUUID();
        CompletableFuture<ProtocolEnvelope> future = new CompletableFuture<>();
        pending.put(correlation, future);
        Runnable send = () -> {
            Player player = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
            if (player == null || !player.isOnline()) {
                fail(correlation, new IllegalStateException("No players online to send plugin message"));
                return;
            }
            try {
                player.sendPluginMessage(plugin, channel,
                        codec.encode(new ProtocolEnvelope(ProtocolCodec.PROTOCOL_VERSION, type, correlation, payload)));
            } catch (RuntimeException exception) {
                fail(correlation, exception);
            }
        };
        if (Bukkit.isPrimaryThread()) send.run();
        else Bukkit.getScheduler().runTask(plugin, send);
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> fail(correlation, new IllegalStateException("Velocity request timed out")), REQUEST_TIMEOUT_TICKS);
        return future;
    }

    void sendAny(MessageType type, byte[] payload) {
        Runnable send = () -> {
            Player player = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
            if (player == null || !player.isOnline()) return;
            try {
                player.sendPluginMessage(plugin, channel,
                        codec.encode(new ProtocolEnvelope(ProtocolCodec.PROTOCOL_VERSION, type, UUID.randomUUID(), payload)));
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Failed to send plugin message: " + exception.getMessage());
            }
        };
        if (Bukkit.isPrimaryThread()) send.run();
        else Bukkit.getScheduler().runTask(plugin, send);
    }

    void send(UUID playerId, MessageType type, byte[] payload) {
        Runnable send = () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) return;
            try {
                player.sendPluginMessage(plugin, channel,
                        codec.encode(new ProtocolEnvelope(ProtocolCodec.PROTOCOL_VERSION, type, UUID.randomUUID(), payload)));
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Failed to send plugin message: " + exception.getMessage());
            }
        };
        if (Bukkit.isPrimaryThread()) send.run();
        else Bukkit.getScheduler().runTask(plugin, send);
    }

    @Override
    public void onPluginMessageReceived(String incomingChannel, Player player, byte[] message) {
        if (!channel.equals(incomingChannel)) return;
        try {
            ProtocolEnvelope envelope = codec.decode(message);
            plugin.getLogger().info("VelocityBridge: Received " + envelope.messageType() + " correlation " + envelope.correlationId());
            if (envelope.messageType() == MessageType.INSTANCE_REGISTER_ACK) {
                if (plugin.instanceAgent() != null) {
                    plugin.instanceAgent().onRegisterAck(envelope.payload());
                }
                return;
            }

            CompletableFuture<ProtocolEnvelope> future = pending.remove(envelope.correlationId());
            if (future != null) {
                future.complete(envelope);
                return;
            }

            // Uncorrelated or broadcast messages
            if (envelope.messageType() == MessageType.PLAYER_RETURN) {
                // Handled directly if needed
            }
        } catch (ProtocolValidationException exception) {
            plugin.getLogger().warning("Rejected invalid proxy message: " + exception.getMessage());
        }
    }

    void close() {
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, channel, this);
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, channel);
        pending.values().forEach(future -> future.completeExceptionally(new IllegalStateException("Plugin disabled")));
        pending.clear();
    }

    private void fail(UUID correlation, Throwable error) {
        plugin.getLogger().warning("VelocityBridge: Request failed " + correlation + ": " + error.getMessage());
        CompletableFuture<ProtocolEnvelope> future = pending.remove(correlation);
        if (future != null) future.completeExceptionally(error);
    }
}
