package dev.kodari.zombieapocalypse.protection;

import dev.kodari.zombieapocalypse.config.ConfigManager;
import dev.kodari.zombieapocalypse.item.CustomItemService;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class ProtectionService {
    private final ConfigManager config;
    private final CustomItemService items;

    public ProtectionService(ConfigManager config, CustomItemService items) {
        this.config = config;
        this.items = items;
    }

    public double infectionChance(Player player, double baseChance) {
        double resistance = 0;
        for (ItemStack armor : player.getInventory().getArmorContents()) {
            String itemId = items.getItemId(armor);
            if (itemId != null) {
                resistance = Math.max(resistance, config.getDouble("items." + itemId + ".infection-resistance", 0));
            }
        }
        return baseChance * (1 - Math.max(0, Math.min(1, resistance)));
    }

    public boolean protects(Player player, String hazard) {
        String path = "protection." + hazard + ".";
        for (ItemStack armor : player.getInventory().getArmorContents()) {
            String itemId = items.getItemId(armor);
            if (itemId != null && config.getBoolean("items." + itemId + "." + path + "enabled", false)) {
                return true;
            }
        }
        return false;
    }
}