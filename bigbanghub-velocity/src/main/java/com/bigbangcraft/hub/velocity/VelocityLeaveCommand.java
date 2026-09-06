package com.bigbangcraft.hub.velocity;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;

import java.util.List;
import java.util.Objects;

final class VelocityLeaveCommand implements SimpleCommand {
    private final BigBangHubVelocityPlugin plugin;

    VelocityLeaveCommand(BigBangHubVelocityPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendPlainMessage("Apenas jogadores podem usar este comando.");
            return;
        }
        if (!player.hasPermission("bigbanghub.match.leave")) {
            player.sendPlainMessage("Você não tem permissão.");
            return;
        }
        plugin.leaveMatch(player);
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        return List.of();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("bigbanghub.match.leave");
    }
}
