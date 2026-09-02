package com.bigbangcraft.hub.velocity;

import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;

import java.time.Duration;

public class VelocityExperienceService {
    private final boolean soundEnabled;
    private final boolean titleEnabled;
    private final boolean actionbarEnabled;

    public VelocityExperienceService(boolean soundEnabled, boolean titleEnabled, boolean actionbarEnabled) {
        this.soundEnabled = soundEnabled;
        this.titleEnabled = titleEnabled;
        this.actionbarEnabled = actionbarEnabled;
    }

    public VelocityExperienceService() {
        this(true, true, true);
    }

    public boolean soundEnabled() {
        return soundEnabled;
    }

    public boolean titleEnabled() {
        return titleEnabled;
    }

    public boolean actionbarEnabled() {
        return actionbarEnabled;
    }

    public void playSound(Player player, String soundKey, float volume, float pitch) {
        if (!soundEnabled || player == null || !player.isActive()) return;
        try {
            Sound sound = Sound.sound(Key.key(soundKey), Sound.Source.MASTER, volume, pitch);
            player.playSound(sound);
        } catch (Exception ignored) { }
    }

    public void showTitle(Player player, Component title, Component subtitle, Duration fadeIn, Duration stay, Duration fadeOut) {
        if (!titleEnabled || player == null || !player.isActive()) return;
        try {
            Title.Times times = Title.Times.times(fadeIn, stay, fadeOut);
            Title t = Title.title(title, subtitle, times);
            player.showTitle(t);
        } catch (Exception ignored) { }
    }

    public void sendActionBar(Player player, Component actionbar) {
        if (!actionbarEnabled || player == null || !player.isActive()) return;
        try {
            player.sendActionBar(actionbar);
        } catch (Exception ignored) { }
    }

    public void notifyMatchFound(Player player) {
        showTitle(player,
                Component.text("§a§lPARTIDA ENCONTRADA!"),
                Component.text("§fConectando ao servidor em instantes..."),
                Duration.ofMillis(200), Duration.ofMillis(2000), Duration.ofMillis(500));
        playSound(player, "entity.player.levelup", 1.0f, 1.0f);
    }

    public void notifyReconnectAvailable(Player player) {
        showTitle(player,
                Component.text("§e§lPARTIDA EM ANDAMENTO"),
                Component.text("§7Clique no chat ou use §a/reconnect"),
                Duration.ofMillis(200), Duration.ofMillis(2500), Duration.ofMillis(500));
        playSound(player, "block.note_block.pling", 1.0f, 1.2f);
    }

    public void notifyRematchVote(Player player, String voter, int current, int required) {
        showTitle(player,
                Component.text("§b§lVOTO DE REVANCHE"),
                Component.text("§7" + voter + " votou por revanche (" + current + "/" + required + ")"),
                Duration.ofMillis(200), Duration.ofMillis(1500), Duration.ofMillis(300));
        playSound(player, "block.note_block.bell", 1.0f, 1.0f);
    }

    public void notifyRematchConsensus(Player player) {
        showTitle(player,
                Component.text("§a§lREVANCHE ACEITA!"),
                Component.text("§fPreparando nova rodada..."),
                Duration.ofMillis(200), Duration.ofMillis(2000), Duration.ofMillis(500));
        playSound(player, "entity.player.levelup", 1.0f, 1.5f);
    }

    public void notifyPartyInvite(Player player, String inviter) {
        showTitle(player,
                Component.text("§b§lCONVITE DE PARTY"),
                Component.text("§f" + inviter + " convidou você para o grupo!"),
                Duration.ofMillis(200), Duration.ofMillis(2000), Duration.ofMillis(500));
        playSound(player, "entity.experience_orb.pickup", 1.0f, 1.0f);
    }

    public void notifyPartyDisband(Player player) {
        showTitle(player,
                Component.text("§c§lPARTY DISSOLVIDA"),
                Component.text("§7O líder desfez o grupo."),
                Duration.ofMillis(200), Duration.ofMillis(2000), Duration.ofMillis(500));
        playSound(player, "entity.villager.no", 1.0f, 0.8f);
    }

    public void notifyPartyKick(Player player) {
        showTitle(player,
                Component.text("§c§lVOCÊ FOI REMOVIDO"),
                Component.text("§7Você foi expulso da party."),
                Duration.ofMillis(200), Duration.ofMillis(2000), Duration.ofMillis(500));
        playSound(player, "entity.villager.no", 1.0f, 0.8f);
    }
}
