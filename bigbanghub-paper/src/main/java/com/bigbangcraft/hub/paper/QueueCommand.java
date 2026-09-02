package com.bigbangcraft.hub.paper;

import com.bigbangcraft.hub.api.GameId;
import com.bigbangcraft.hub.api.QueueResult;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

final class QueueCommand implements CommandExecutor, TabCompleter {
    private final BigBangHubPaperPlugin plugin;
    private final ActionExecutor actions;

    QueueCommand(BigBangHubPaperPlugin plugin) {
        this.plugin = plugin;
        this.actions = new ActionExecutor(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cEste comando só pode ser usado por jogadores.");
            return true;
        }
        if (args.length == 0) {
            player.sendMessage("§7Use: /queue <join|leave|status> [game]");
            return true;
        }
        String sub = args[0].toLowerCase(java.util.Locale.ROOT);
        if (sub.equals("join")) {
            if (!player.hasPermission("bigbanghub.queue.join")) return deny(player);
            if (args.length != 2) { player.sendMessage("§cUse: /queue join <game>"); return true; }
            try {
                GameId game = GameId.of(args[1]);
                if (!plugin.games().find(game).map(g -> g.enabled() && g.queueEnabled()).orElse(false)) {
                    player.sendMessage("§cEste minigame está temporariamente indisponível.");
                    return true;
                }
                plugin.queues().join(player.getUniqueId(), game)
                        .whenComplete((result, error) -> actions.sendQueueResult(player, result, error));
            } catch (IllegalArgumentException exception) {
                player.sendMessage("§cGame ID inválido.");
            }
            return true;
        }
        if (sub.equals("leave")) {
            if (!player.hasPermission("bigbanghub.queue.leave")) return deny(player);
            plugin.queues().leave(player.getUniqueId()).whenComplete((result, error) -> actions.sendQueueResult(player, result, error));
            return true;
        }
        if (sub.equals("status")) {
            if (!player.hasPermission("bigbanghub.queue.status")) return deny(player);
            plugin.queues().status(player.getUniqueId()).whenComplete((status, error) -> {
                if (error != null) player.sendMessage("§cNão foi possível consultar a fila agora.");
                else if (status.game().isEmpty()) player.sendMessage("§7Você não está em uma fila.");
                else player.sendMessage("§7Fila: §e" + status.game().orElseThrow() + " §7| posição: §f"
                        + status.position() + "/" + status.size());
            });
            return true;
        }
        player.sendMessage("§7Use: /queue <join|leave|status> [game]");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("join", "leave", "status");
        if (args.length == 2 && args[0].equalsIgnoreCase("join")) {
            List<String> values = new ArrayList<>();
            plugin.games().games().forEach(game -> { if (game.enabled() && game.queueEnabled()) values.add(game.id().value()); });
            return values;
        }
        return List.of();
    }

    private boolean deny(Player player) {
        player.sendMessage("§cVocê não tem permissão.");
        return true;
    }
}
