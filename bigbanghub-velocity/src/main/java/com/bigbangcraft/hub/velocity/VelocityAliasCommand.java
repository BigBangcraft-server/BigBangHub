package com.bigbangcraft.hub.velocity;

import com.bigbangcraft.hub.api.GameId;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;

import java.util.List;
import java.util.Objects;

final class VelocityAliasCommand implements SimpleCommand {
    private final BigBangHubVelocityPlugin plugin;
    private final GameId gameId;

    VelocityAliasCommand(BigBangHubVelocityPlugin plugin, GameId gameId) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.gameId = Objects.requireNonNull(gameId, "gameId");
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendPlainMessage("Apenas jogadores podem usar este comando.");
            return;
        }
        if (!player.hasPermission("bigbanghub.queue.join")) {
            player.sendPlainMessage("Você não tem permissão.");
            return;
        }
        plugin.join(player, gameId);
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        return List.of();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("bigbanghub.queue.join");
    }
}
