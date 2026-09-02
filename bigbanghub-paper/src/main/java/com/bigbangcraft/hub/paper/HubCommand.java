package com.bigbangcraft.hub.paper;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;

final class HubCommand implements CommandExecutor, TabCompleter {
    private final BigBangHubPaperPlugin plugin;

    HubCommand(BigBangHubPaperPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "version" : args[0].toLowerCase(java.util.Locale.ROOT);
        switch (sub) {
            case "version" -> sender.sendMessage("§bBigBangHub §f0.2.0 §7(Paper 26.2)");
            case "compass" -> {
                if (!sender.hasPermission("bigbanghub.compass")) return deny(sender);
                if (!(sender instanceof org.bukkit.entity.Player player)) {
                    sender.sendMessage("§cEste comando só pode ser usado por jogadores.");
                    return true;
                }
                plugin.menu().open(player);
            }
            case "reload" -> {
                if (!sender.hasPermission("bigbanghub.reload")) {
                    sender.sendMessage("§cVocê não tem permissão para isto.");
                    return true;
                }
                plugin.reload(sender);
            }
            case "status" -> {
                if (!sender.hasPermission("bigbanghub.admin")) return deny(sender);
                sender.sendMessage("§bBigBangHub §f0.2.0 §7| Role: §e" + plugin.role());
                sender.sendMessage("§7Paper bridge: §aONLINE §7| Protocol: §f" + plugin.configSnapshot().proxy().protocolVersion());
                if (plugin.instanceAgent() != null) {
                    PaperInstanceAgent agent = plugin.instanceAgent();
                    sender.sendMessage("§7Instance: §f" + agent.instanceId().value() + " §7(Game: §f" + agent.gameId().value() + "§7)");
                    sender.sendMessage("§7State: §f" + agent.state() + " §7| Accepting: §f" + agent.isAcceptingPlayers()
                            + " §7| Registered: §f" + (agent.isRegistered() ? "§aYES" : "§cNO"));
                    sender.sendMessage("§7Session: §f" + agent.sessionId());
                } else {
                    sender.sendMessage("§7Games: §f" + plugin.games().games().size() + " §7| Queued players: §funknown (proxy-owned)");
                }
            }
            default -> sender.sendMessage("§7Use: /bbhub <reload|compass|version|status>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return args.length == 1 ? List.of("reload", "compass", "version", "status") : List.of();
    }

    private boolean deny(CommandSender sender) {
        sender.sendMessage("§cVocê não tem permissão.");
        return true;
    }
}
