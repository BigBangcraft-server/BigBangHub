package com.bigbangcraft.hub.velocity;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;

import java.util.List;
import java.util.Objects;

final class VelocityRematchCommand implements SimpleCommand {
    private final BigBangHubVelocityPlugin plugin;

    VelocityRematchCommand(BigBangHubVelocityPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendPlainMessage("Apenas jogadores podem usar /rematch.");
            return;
        }
        plugin.handleRematchVote(player);
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        return List.of();
    }
}
