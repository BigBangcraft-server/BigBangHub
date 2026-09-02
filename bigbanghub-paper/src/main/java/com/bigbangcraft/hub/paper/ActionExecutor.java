package com.bigbangcraft.hub.paper;

import com.bigbangcraft.hub.api.GameId;
import com.bigbangcraft.hub.api.QueueResult;
import com.bigbangcraft.hub.api.TransferResult;
import com.bigbangcraft.hub.common.ActionDefinition;
import com.bigbangcraft.hub.common.ActionType;
import com.bigbangcraft.hub.common.HubConfigSnapshot;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Objects;

final class ActionExecutor {
    private final BigBangHubPaperPlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    ActionExecutor(BigBangHubPaperPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    void execute(Player player, ActionDefinition action) {
        HubConfigSnapshot config = plugin.configSnapshot();
        switch (action.type()) {
            case PLAYER_COMMAND -> player.performCommand(replacePlayerPlaceholders(action.value(), player));
            case CONSOLE_COMMAND -> consoleCommand(player, action.value(), config);
            case QUEUE -> joinQueue(player, GameId.of(action.value()));
            case SERVER -> plugin.transfers().transfer(player.getUniqueId(), com.bigbangcraft.hub.api.ServerId.of(action.value()))
                    .whenComplete((result, error) -> onTransferResult(player, result, error));
            case CLOSE -> player.closeInventory();
            case MESSAGE -> player.sendMessage(miniMessage.deserialize(action.value()));
            case SOUND -> playSound(player, action.value());
        }
    }

    private void consoleCommand(Player player, String configured, HubConfigSnapshot config) {
        if (!config.allowConsoleCommands()) {
            player.sendMessage(message("console-command-disabled", "<red>Esta ação administrativa está desabilitada.</red>"));
            return;
        }
        String command = replacePlayerPlaceholders(configured, player).replaceFirst("^/", "");
        String root = command.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (!config.consoleCommandAllowlist().contains(root)) {
            player.sendMessage(message("console-command-denied", "<red>Comando não permitido.</red>"));
            return;
        }
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }

    private void joinQueue(Player player, GameId gameId) {
        if (!plugin.games().find(gameId).map(game -> game.enabled() && game.queueEnabled()).orElse(false)) {
            player.sendMessage(message("game-unavailable", "<red>Este minigame está temporariamente indisponível.</red>"));
            return;
        }
        plugin.queues().join(player.getUniqueId(), gameId)
                .whenComplete((result, error) -> sendQueueResult(player, result, error));
    }

    void sendQueueResult(Player player, QueueResult result, Throwable error) {
        runOnMain(player, () -> {
            if (error != null) player.sendMessage(message("proxy-unavailable", "<red>Não foi possível localizar um servidor agora.</red>"));
            else player.sendMessage(miniMessage.deserialize(result.message()));
        });
    }

    private void onTransferResult(Player player, TransferResult result, Throwable error) {
        runOnMain(player, () -> player.sendMessage(error == null && result.success()
                ? message("transfer-started", "<green>Conectando...</green>")
                : message("proxy-unavailable", "<red>Não foi possível localizar um servidor agora.</red>")));
    }

    private void playSound(Player player, String value) {
        try {
            Sound sound = Registry.SOUNDS.get(NamespacedKey.minecraft(value.toLowerCase(Locale.ROOT)));
            if (sound == null) throw new IllegalArgumentException("Unknown sound");
            player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Ignoring unknown configured sound: " + value);
        }
    }

    private String replacePlayerPlaceholders(String value, Player player) {
        return value.replace("{player}", player.getName()).replace("{uuid}", player.getUniqueId().toString());
    }

    private net.kyori.adventure.text.Component message(String key, String fallback) {
        return miniMessage.deserialize(plugin.configSnapshot().messages().getOrDefault(key, fallback));
    }

    private void runOnMain(Player player, Runnable action) {
        if (Bukkit.isPrimaryThread()) action.run();
        else Bukkit.getScheduler().runTask(plugin, () -> { if (player.isOnline()) action.run(); });
    }
}
