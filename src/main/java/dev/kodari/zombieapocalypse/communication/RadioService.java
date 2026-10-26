package dev.kodari.zombieapocalypse.communication;

import dev.kodari.zombieapocalypse.config.ConfigManager;
import dev.kodari.zombieapocalypse.item.CustomItemService;
import dev.kodari.zombieapocalypse.noise.NoiseService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class RadioService {
    private final ConfigManager config;
    private final NoiseService noise;
    private final CustomItemService items;
    private TeamResolver teamResolver;

    public RadioService(ConfigManager config, NoiseService noise, CustomItemService items) {
        this.config = config;
        this.noise = noise;
        this.items = items;
    }

    public void setTeamResolver(TeamResolver teamResolver) {
        this.teamResolver = teamResolver;
    }

    public int broadcast(Player sender, String alertId) {
        if (!config.getBoolean("radio.enabled", true)) {
            return 0;
        }
        String message = config.getString("radio.alerts." + alertId,
                        "&e[Radio] %player%: %alert%")
                .replace("%player%", sender.getName())
                .replace("%alert%", alertId.replace('_', ' '));
        double range = config.getDouble("radio.range-blocks", 0);
        boolean teamOnly = config.getBoolean("radio.team-only", false);
        boolean requireRadio = config.getBoolean("radio.require-radio", false);
        int delivered = 0;
        for (Player recipient : Bukkit.getOnlinePlayers()) {
            if (range > 0 && (!recipient.getWorld().equals(sender.getWorld())
                    || recipient.getLocation().distanceSquared(sender.getLocation()) > range * range)) {
                continue;
            }
            if (requireRadio && !hasRadio(recipient)) {
                continue;
            }
            if (teamOnly && !recipient.equals(sender)
                    && (teamResolver == null || !teamResolver.areTeammates(sender, recipient))) {
                continue;
            }
            recipient.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
            delivered++;
        }
        noise.emit(sender, config.getDouble("radio.noise-radius", 24));
        return delivered;
    }

    private boolean hasRadio(Player player) {
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if ("handheld_radio".equals(items.getItemId(item))) {
                return true;
            }
        }
        return "handheld_radio".equals(items.getItemId(player.getInventory().getItemInOffHand()));
    }
}