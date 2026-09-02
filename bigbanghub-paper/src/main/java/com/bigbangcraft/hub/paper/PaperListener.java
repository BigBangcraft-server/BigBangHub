package com.bigbangcraft.hub.paper;

import com.bigbangcraft.hub.api.GameId;
import com.bigbangcraft.hub.common.ActionDefinition;
import com.bigbangcraft.hub.common.ActionType;
import com.bigbangcraft.hub.common.HubConfigSnapshot;
import com.bigbangcraft.hub.common.ProtectionSettings;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.attribute.Attribute;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;

final class PaperListener implements Listener {
    private final BigBangHubPaperPlugin plugin;
    private final ActionExecutor actions;

    PaperListener(BigBangHubPaperPlugin plugin) {
        this.plugin = plugin;
        this.actions = new ActionExecutor(plugin);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        HubConfigSnapshot config = plugin.configSnapshot();
        if (config.inventory().clearOnJoin() && !bypassInventory(player)) player.getInventory().clear();
        player.setGameMode(GameMode.ADVENTURE);
        player.setHealth(player.getAttribute(Attribute.MAX_HEALTH).getValue());
        player.setFoodLevel(20);
        player.setFireTicks(0);
        player.setFallDistance(0);
        player.teleport(spawn(player));
        plugin.menu().giveCompass(player);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        if (plugin.configSnapshot().protection().voidSafety()) event.setRespawnLocation(spawn(event.getPlayer()));
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (!plugin.configSnapshot().protection().voidSafety() || event.getTo() == null) return;
        if (event.getTo().getY() < 0 && event.getFrom().getY() >= 0) {
            event.getPlayer().teleport(spawn(event.getPlayer()));
            event.getPlayer().setFallDistance(0);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAlias(PlayerCommandPreprocessEvent event) {
        String[] tokens = event.getMessage().substring(1).trim().split("\\s+");
        if (tokens.length != 1) return;
        String alias = tokens[0].toLowerCase(Locale.ROOT);
        String game = plugin.configSnapshot().aliases().get(alias);
        if (game == null || !event.getPlayer().hasPermission("bigbanghub.queue.join")) return;
        event.setCancelled(true);
        actions.execute(event.getPlayer(), new ActionDefinition(ActionType.QUEUE, game));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCompass(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (plugin.menu().isCompass(event.getItem()) && (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK)) {
            event.setCancelled(true);
            plugin.menu().open(event.getPlayer());
            return;
        }
        if (action == Action.PHYSICAL && plugin.configSnapshot().protection().farmlandTrampling()
                && event.getClickedBlock() != null && event.getClickedBlock().getType() == Material.FARMLAND
                && !bypassProtection(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) { if (blocked(event.getPlayer(), plugin.configSnapshot().protection().blockBreak())) event.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) { if (blocked(event.getPlayer(), plugin.configSnapshot().protection().blockPlace())) event.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!bypassProtection(event.getPlayer()) && (plugin.configSnapshot().protection().itemDrop()
                || (plugin.configSnapshot().inventory().preventDrop() && plugin.menu().isLobbyItem(event.getItemDrop().getItemStack())))) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && !bypassProtection(player) && plugin.configSnapshot().protection().itemPickup()) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        ProtectionSettings protection = plugin.configSnapshot().protection();
        if (!protection.damage() && !(protection.pvp() && isPlayerAttack(event))) return;
        if (event.getEntity() instanceof Player player && bypassProtection(player)) return;
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && plugin.configSnapshot().protection().hunger() && !bypassProtection(player)) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) { if (plugin.configSnapshot().protection().mobInteractions()) event.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onWeather(WeatherChangeEvent event) { if (plugin.configSnapshot().protection().weather()) event.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onFire(BlockBurnEvent event) { if (plugin.configSnapshot().protection().fire()) event.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) { if (plugin.configSnapshot().protection().fire() && (event.getPlayer() == null || !bypassProtection(event.getPlayer()))) event.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) { if (plugin.configSnapshot().protection().explosions()) event.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) { if (plugin.configSnapshot().protection().explosions()) event.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        if (plugin.configSnapshot().protection().fluidPlacement()
                && (event.getBlock().getType() == Material.WATER || event.getBlock().getType() == Material.LAVA)) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) { if (blocked(event.getPlayer(), plugin.configSnapshot().protection().bucketUse())) event.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) { if (blocked(event.getPlayer(), plugin.configSnapshot().protection().bucketUse())) event.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent event) { if (blocked(event.getPlayer(), plugin.configSnapshot().protection().entityInteraction())) event.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) { if (blocked(event.getPlayer(), plugin.configSnapshot().protection().armorStandInteraction())) event.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent event) { if (plugin.configSnapshot().protection().mobInteractions()) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (plugin.menu().isMenu(event.getView().getTopInventory())) {
            event.setCancelled(true);
            if (event.getRawSlot() < event.getView().getTopInventory().getSize()) {
                var entry = plugin.menu().entryAt(event.getRawSlot());
                if (entry != null) actions.execute(player, entry.action());
            }
            return;
        }
        if (!bypassInventory(player) && (plugin.configSnapshot().protection().inventoryManipulation()
                || (plugin.configSnapshot().inventory().lockLobbyItems()
                && (plugin.menu().isLobbyItem(event.getCurrentItem()) || plugin.menu().isLobbyItem(event.getCursor()))))) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (plugin.menu().isMenu(event.getView().getTopInventory())
                || (!bypassInventory(player) && plugin.configSnapshot().protection().inventoryManipulation())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) { if (event.getWhoClicked() instanceof Player player && blocked(player, plugin.configSnapshot().protection().crafting())) event.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) { if (!bypassInventory(event.getPlayer()) && plugin.configSnapshot().inventory().preventMove()) event.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent event) { if (!bypassInventory(event.getPlayer()) && plugin.menu().isLobbyItem(event.getPlayer().getInventory().getItem(event.getNewSlot())) && plugin.configSnapshot().inventory().preventMove()) event.setCancelled(true); }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) { plugin.queues().leave(event.getPlayer().getUniqueId()); }

    private boolean blocked(Player player, boolean enabled) { return enabled && !bypassProtection(player); }
    private boolean bypassProtection(Player player) { return player.hasPermission("bigbanghub.bypass.protection"); }
    private boolean bypassInventory(Player player) { return player.hasPermission("bigbanghub.bypass.inventory"); }

    private boolean isPlayerAttack(EntityDamageEvent event) {
        if (!(event instanceof EntityDamageByEntityEvent byEntity)) return false;
        Entity damager = byEntity.getDamager();
        if (damager instanceof Player) return true;
        return damager instanceof Projectile projectile && projectile.getShooter() instanceof Player;
    }

    private Location spawn(Player player) {
        org.bukkit.World world = org.bukkit.Bukkit.getWorld("world");
        if (world == null) world = player.getWorld();
        Location location = world.getSpawnLocation().clone().add(0.5, 0, 0.5);
        location.setYaw(0);
        location.setPitch(0);
        return location;
    }
}
