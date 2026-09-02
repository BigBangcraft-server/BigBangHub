package com.bigbangcraft.hub.velocity;

import com.bigbangcraft.hub.api.PlayerTransferService;
import com.bigbangcraft.hub.api.ServerDefinition;
import com.bigbangcraft.hub.api.ServerId;
import com.bigbangcraft.hub.api.ServerRegistry;
import com.bigbangcraft.hub.api.TransferResult;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

final class VelocityTransferService implements PlayerTransferService {
    private final ProxyServer proxy;
    private final com.bigbangcraft.hub.api.InstanceRegistry instances;
    private final ServerRegistry servers;

    VelocityTransferService(ProxyServer proxy, com.bigbangcraft.hub.api.InstanceRegistry instances, ServerRegistry servers) {
        this.proxy = proxy;
        this.instances = instances;
        this.servers = servers;
    }

    @Override
    public CompletionStage<TransferResult> transfer(UUID playerId, ServerId serverId) {
        Player player = proxy.getPlayer(playerId).orElse(null);
        if (player == null) return CompletableFuture.completedFuture(TransferResult.failure("Jogador não encontrado."));

        com.bigbangcraft.hub.api.InstanceSnapshot instance = instances.find(serverId).orElse(null);
        if (instance != null) {
            if (instance.health() != com.bigbangcraft.hub.api.InstanceHealth.HEALTHY) {
                return CompletableFuture.completedFuture(TransferResult.failure("Servidor indisponível."));
            }
            RegisteredServer target = proxy.getServer(instance.serverName())
                    .or(() -> proxy.getServer(serverId.value()))
                    .orElse(null);
            if (target == null) return CompletableFuture.completedFuture(TransferResult.failure("Servidor não registrado no proxy."));
            return player.createConnectionRequest(target).connect().thenApply(result -> {
                if (result.isSuccessful()) return TransferResult.success("Conectando ao servidor...");
                return TransferResult.failure(result.getReasonComponent().map(Object::toString).orElse("Conexão recusada."));
            });
        }

        ServerDefinition definition = servers.find(serverId).orElse(null);
        if (definition == null) return CompletableFuture.completedFuture(TransferResult.failure("Servidor não encontrado."));
        if (definition.state() == com.bigbangcraft.hub.api.GameState.OFFLINE
                || definition.state() == com.bigbangcraft.hub.api.GameState.MAINTENANCE
                || definition.state() == com.bigbangcraft.hub.api.GameState.FULL
                || definition.playerCount() >= definition.maxPlayers()) {
            return CompletableFuture.completedFuture(TransferResult.failure("Servidor indisponível."));
        }
        RegisteredServer target = proxy.getServer(serverId.value()).orElse(null);
        if (target == null) return CompletableFuture.completedFuture(TransferResult.failure("Servidor indisponível."));
        return player.createConnectionRequest(target).connect().thenApply(result -> {
            if (result.isSuccessful()) return TransferResult.success("Conectando ao servidor...");
            return TransferResult.failure(result.getReasonComponent().map(Object::toString).orElse("Conexão recusada."));
        });
    }
}
