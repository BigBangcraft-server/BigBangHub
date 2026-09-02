package com.bigbangcraft.hub.velocity;

import com.bigbangcraft.hub.api.GameId;
import com.bigbangcraft.hub.api.QueueStatus;
import com.bigbangcraft.hub.api.ServerDefinition;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;

import java.util.List;

final class VelocityCommands implements SimpleCommand {
    private final BigBangHubVelocityPlugin plugin;
    private final boolean queueCommand;

    VelocityCommands(BigBangHubVelocityPlugin plugin, boolean queueCommand) {
        this.plugin = plugin;
        this.queueCommand = queueCommand;
    }

    @Override
    public void execute(Invocation invocation) {
        if (queueCommand) executeQueue(invocation);
        else executeHub(invocation);
    }

    private void executeHub(Invocation invocation) {
        String[] args = invocation.arguments();
        String sub = args.length == 0 ? "version" : args[0].toLowerCase(java.util.Locale.ROOT);
        if (sub.equals("version")) {
            invocation.source().sendPlainMessage("BigBangHub 0.1.0 (Velocity 4.1.1)");
        } else if (sub.equals("reload")) {
            if (!admin(invocation.source(), "bigbanghub.reload")) return;
            plugin.reload(invocation.source());
        } else if (sub.equals("status")) {
            if (!admin(invocation.source(), "bigbanghub.admin")) return;
            status(invocation.source());
        } else {
            invocation.source().sendPlainMessage("Use: /bbhub <reload|version|status>");
        }
    }

    private void executeQueue(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendPlainMessage("Este comando só pode ser usado por jogadores.");
            return;
        }
        String[] args = invocation.arguments();
        if (args.length == 0) { player.sendPlainMessage("Use: /queue <join|leave|status> [game]"); return; }
        switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "join" -> {
                if (!player.hasPermission("bigbanghub.queue.join")) { deny(player); return; }
                if (args.length != 2) { player.sendPlainMessage("Use: /queue join <game>"); return; }
                try {
                    GameId game = GameId.of(args[1]);
                    if (!plugin.games().find(game).map(g -> g.enabled() && g.queueEnabled()).orElse(false)) {
                        player.sendPlainMessage("Este minigame está temporariamente indisponível.");
                        return;
                    }
                    plugin.join(player, game);
                } catch (IllegalArgumentException exception) { player.sendPlainMessage("Game ID inválido."); }
            }
            case "leave" -> {
                if (!player.hasPermission("bigbanghub.queue.leave")) { deny(player); return; }
                plugin.queues().leave(player.getUniqueId()).thenAccept(result -> player.sendPlainMessage(result.message()));
            }
            case "status" -> {
                if (!player.hasPermission("bigbanghub.queue.status")) { deny(player); return; }
                plugin.queues().status(player.getUniqueId()).thenAccept(status -> {
                    if (status.game().isEmpty()) player.sendPlainMessage("Você não está em uma fila.");
                    else player.sendPlainMessage("Fila: " + status.game().orElseThrow() + " | posição: "
                            + status.position() + "/" + status.size());
                });
            }
            default -> player.sendPlainMessage("Use: /queue <join|leave|status> [game]");
        }
    }

    private void status(CommandSource source) {
        source.sendPlainMessage("BigBangHub 0.1.0 | Protocol " + plugin.configSnapshot().proxy().protocolVersion());
        source.sendPlainMessage("Games: " + plugin.games().games().size() + " | Players online: " + plugin.proxy().getPlayerCount());
        for (ServerDefinition server : plugin.servers().servers()) {
            source.sendPlainMessage("- " + server.id() + " " + server.state() + " " + server.playerCount() + "/" + server.maxPlayers());
        }
        for (var game : plugin.games().games()) source.sendPlainMessage("Queue " + game.id() + ": " + plugin.queues().size(game.id()));
    }

    private boolean admin(CommandSource source, String permission) {
        if (source.hasPermission(permission)) return true;
        deny(source);
        return false;
    }

    private void deny(CommandSource source) { source.sendPlainMessage("Você não tem permissão."); }
}
