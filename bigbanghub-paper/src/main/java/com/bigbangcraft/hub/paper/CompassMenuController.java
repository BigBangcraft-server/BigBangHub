package com.bigbangcraft.hub.paper;

import com.bigbangcraft.hub.common.CompassEntry;
import com.bigbangcraft.hub.common.CompassMenu;
import com.bigbangcraft.hub.common.HubConfigSnapshot;
import com.bigbangcraft.hub.common.LobbyItemSettings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class CompassMenuController {
    private final BigBangHubPaperPlugin plugin;
    private final Map<Integer, CompassEntry> entries;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final NamespacedKey lobbyItemKey;
    private final NamespacedKey menuItemKey;
    private final CompassMenu menu;
    private final Map<Integer, ItemStack> itemTemplates;
    private final ItemStack compass;

    CompassMenuController(BigBangHubPaperPlugin plugin, HubConfigSnapshot config) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.menu = config.compass();
        this.lobbyItemKey = new NamespacedKey(plugin, "lobby-item");
        this.menuItemKey = new NamespacedKey(plugin, "menu-action");
        this.entries = new HashMap<>();
        this.itemTemplates = new HashMap<>();
        for (CompassEntry entry : menu.entries()) {
            if (entries.put(entry.slot(), entry) != null) throw new IllegalArgumentException("Duplicate compass slot");
            itemTemplates.put(entry.slot(), compileMenuItem(entry));
        }
        this.compass = compileLobbyItem(menu.item());
    }

    void open(Player player) {
        MenuHolder holder = new MenuHolder();
        Inventory inventory = Bukkit.createInventory(holder, menu.rows() * 9, component(menu.title()));
        holder.inventory = inventory;
        for (Map.Entry<Integer, ItemStack> entry : itemTemplates.entrySet()) inventory.setItem(entry.getKey(), entry.getValue().clone());
        player.openInventory(inventory);
    }

    void giveCompass(Player player) {
        if (!menu.item().enabled()) return;
        player.getInventory().setItem(menu.item().slot(), compass.clone());
    }

    boolean isCompass(ItemStack item) {
        return hasMarker(item, lobbyItemKey);
    }

    boolean isLobbyItem(ItemStack item) {
        return isCompass(item);
    }

    boolean isMenu(Inventory inventory) {
        return inventory != null && inventory.getHolder(false) instanceof MenuHolder;
    }

    CompassEntry entryAt(int rawSlot) {
        return entries.get(rawSlot);
    }

    private ItemStack compileLobbyItem(LobbyItemSettings settings) {
        Material material = material(settings.material(), "menus.compass.item.material");
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        applyMeta(meta, settings.name(), settings.lore(), settings.glow(), settings.flags(), lobbyItemKey, "1");
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack compileMenuItem(CompassEntry entry) {
        Material material = material(entry.material(), "menus.compass.items slot " + entry.slot());
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        applyMeta(meta, entry.name(), entry.lore(), entry.glow(), entry.flags(), menuItemKey, Integer.toString(entry.slot()));
        item.setItemMeta(meta);
        return item;
    }

    private void applyMeta(ItemMeta meta, String name, List<String> lore, boolean glow, List<String> flags,
                           NamespacedKey marker, String markerValue) {
        meta.displayName(component(name));
        meta.lore(lore.stream().map(this::component).toList());
        if (glow) meta.setEnchantmentGlintOverride(true);
        for (String flag : flags) {
            try {
                meta.addItemFlags(ItemFlag.valueOf(flag.toUpperCase(java.util.Locale.ROOT)));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Unknown item flag: " + flag, exception);
            }
        }
        meta.getPersistentDataContainer().set(marker, PersistentDataType.STRING, markerValue);
    }

    private Material material(String value, String path) {
        Material material = Material.matchMaterial(value);
        if (material == null) throw new IllegalArgumentException("Unknown material " + value + " at " + path);
        return material;
    }

    private Component component(String value) {
        return miniMessage.deserialize(value);
    }

    private boolean hasMarker(ItemStack item, NamespacedKey key) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.STRING);
    }

    static final class MenuHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
