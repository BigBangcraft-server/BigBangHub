package com.bigbangcraft.hub.paper;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Objects;

final class PaperInstanceListener implements Listener {
    private final BigBangHubPaperPlugin plugin;
    private final PaperInstanceAgent agent;

    PaperInstanceListener(BigBangHubPaperPlugin plugin, PaperInstanceAgent agent) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.agent = Objects.requireNonNull(agent, "agent");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        org.bukkit.entity.Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            if (!agent.isRegistered()) {
                agent.sendRegister();
            } else {
                agent.sendHeartbeat();
            }
            if (plugin.matchManager() != null) {
                plugin.matchManager().handlePlayerJoin(player);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (plugin.matchManager() != null) {
            plugin.matchManager().handlePlayerQuit(event.getPlayer());
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (agent.isRegistered()) {
                agent.sendHeartbeat();
            }
        });
    }
}
