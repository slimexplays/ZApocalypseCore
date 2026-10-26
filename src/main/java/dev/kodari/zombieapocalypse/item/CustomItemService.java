package dev.kodari.zombieapocalypse.item;

import com.cryptomorin.xseries.XMaterial;
import dev.kodari.zombieapocalypse.ZombieApocalypsePlugin;
import dev.kodari.zombieapocalypse.config.ConfigManager;
import dev.kodari.zombieapocalypse.zombie.ZombieVariant;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class CustomItemService {
    private final ZombieApocalypsePlugin plugin;
    private final ConfigManager config;
    private final NamespacedKey itemIdKey;
    private final NamespacedKey createdAtKey;
    private final NamespacedKey storageIdKey;

    public CustomItemService(ZombieApocalypsePlugin plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
        this.itemIdKey = new NamespacedKey("ultra_advanced_zombie_apocalypse", "item_id");
        this.createdAtKey = new NamespacedKey("ultra_advanced_zombie_apocalypse", "created_at");
        this.storageIdKey = new NamespacedKey("ultra_advanced_zombie_apocalypse", "storage_id");
    }

    public ItemStack create(String itemId) {
        String path = "items." + itemId + ".";
        String materialName = config.getString(path + "material", "PAPER");
        ItemStack item = XMaterial.matchXMaterial(materialName).map(XMaterial::parseItem).orElse(null);
        if (item == null) {
            plugin.getLogger().warning("Unknown material for custom item '" + itemId + "': " + materialName);
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        String name = config.getString(path + "name", itemId);
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
        meta.getPersistentDataContainer().set(itemIdKey, PersistentDataType.STRING, itemId);
        if (config.getSection("firearms.guns." + itemId) != null) {
            meta.setItemModel(new NamespacedKey(plugin, "firearms/" + itemId));
        }
        if (config.getBoolean("survival.food-spoilage.enabled", false)
                && config.getBoolean(path + "perishable", false)) {
            meta.getPersistentDataContainer().set(createdAtKey, PersistentDataType.LONG, System.currentTimeMillis());
        }
        if (config.getInt(path + "storage-size", 0) > 0) {
            meta.getPersistentDataContainer().set(storageIdKey, PersistentDataType.STRING, UUID.randomUUID().toString());
            item.setAmount(1);
        }
        item.setItemMeta(meta);
        if (config.getSection("firearms.guns." + itemId) != null) {
            item.setAmount(1);
        }
        return item;
    }

    public String getItemId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(itemIdKey, PersistentDataType.STRING);
    }

    public UUID getStorageId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        String id = item.getItemMeta().getPersistentDataContainer().get(storageIdKey, PersistentDataType.STRING);
        if (id == null) {
            return null;
        }
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public boolean isCureItem(String itemId) {
        return itemId != null && config.getBoolean("items." + itemId + ".cure", false);
    }

    public boolean isSpoiled(ItemStack item) {
        if (!config.getBoolean("survival.food-spoilage.enabled", false)) {
            return false;
        }
        String itemId = getItemId(item);
        if (itemId == null || !config.getBoolean("items." + itemId + ".perishable", false)) {
            return false;
        }
        Long createdAt = item.getItemMeta().getPersistentDataContainer().get(createdAtKey, PersistentDataType.LONG);
        long spoilAfter = config.getLong("items." + itemId + ".spoil-after-seconds",
                config.getLong("survival.food-spoilage.seconds", 1209600));
        return createdAt != null && spoilAfter > 0 && System.currentTimeMillis() - createdAt >= spoilAfter * 1000L;
    }

    public void rollZombieDrops(Zombie zombie, ZombieVariant variant) {
        ConfigurationSection drops = config.getSection("loot.zombie-drops");
        if (drops == null) {
            return;
        }
        for (String itemId : drops.getKeys(false)) {
            double chance = config.getDouble("variants." + variant.id() + ".drop-chance", 0);
            chance *= drops.getDouble(itemId + ".chance", 0);
            if (ThreadLocalRandom.current().nextDouble() >= chance) {
                continue;
            }
            ItemStack item = create(itemId);
            if (item == null) {
                continue;
            }
            int min = Math.max(1, drops.getInt(itemId + ".min", 1));
            int max = Math.max(min, drops.getInt(itemId + ".max", min));
            item.setAmount(ThreadLocalRandom.current().nextInt(min, max + 1));
            Location location = zombie.getLocation();
            location.getWorld().dropItemNaturally(location, item);
        }
    }
}