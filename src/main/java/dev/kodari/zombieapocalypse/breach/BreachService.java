package dev.kodari.zombieapocalypse.breach;

import com.cryptomorin.xseries.XMaterial;
import dev.kodari.zombieapocalypse.ZombieApocalypsePlugin;
import dev.kodari.zombieapocalypse.config.ConfigManager;
import dev.kodari.zombieapocalypse.item.CustomItemService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class BreachService implements org.bukkit.event.Listener, AutoCloseable {
    private final ZombieApocalypsePlugin plugin;
    private final ConfigManager config;
    private final CustomItemService items;
    private final Map<String, DamageProgress> progress = new HashMap<>();
    private final Map<String, Restoration> restorations = new HashMap<>();

    public BreachService(ZombieApocalypsePlugin plugin, ConfigManager config, CustomItemService items) {
        this.plugin = plugin;
        this.config = config;
        this.items = items;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreachAttempt(PlayerInteractEvent event) {
        if (!config.getBoolean("breach.enabled", false) || event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null
                || !config.isWorldEnabled(event.getPlayer().getWorld())) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack charge = event.getItem();
        if (!config.getString("breach.tool-item", "breaching_charge").equals(items.getItemId(charge))) {
            return;
        }
        Block block = event.getClickedBlock();
        String key = blockKey(block.getLocation());
        if (!isBreachable(block)) {
            player.sendMessage(color("&cThis block cannot be breached."));
            event.setCancelled(true);
            return;
        }
        event.setCancelled(true);
        long now = System.currentTimeMillis();
        long timeoutMillis = config.getInt("breach.progress-timeout-seconds", 30) * 1000L;
        progress.entrySet().removeIf(entry -> now - entry.getValue().updatedAt() > timeoutMillis);
        DamageProgress previous = progress.get(key);
        int hits = previous != null && now - previous.updatedAt() <= timeoutMillis ? previous.hits() : 0;
        int required = config.getInt("breach.damage-required", 4);
        if (hits + 1 < required) {
            consumeCharge(player, charge);
            progress.put(key, new DamageProgress(hits + 1, now));
            player.sendMessage(color("&eBreaching progress: &f" + (hits + 1) + "/" + required + "&e. Teammates can assist."));
            return;
        }

        BlockBreakEvent breakCheck = new BlockBreakEvent(block, player);
        Bukkit.getPluginManager().callEvent(breakCheck);
        if (breakCheck.isCancelled()) {
            player.sendMessage(color("&cBreaching is blocked by this region's protection."));
            return;
        }
        BlockState originalState = block.getState();
        if (!block.breakNaturally(charge)) {
            progress.remove(key);
            player.sendMessage(color("&cThe block could not be breached."));
            return;
        }
        consumeCharge(player, charge);
        progress.remove(key);
        if (breakCheck.getExpToDrop() > 0) {
            player.giveExp(breakCheck.getExpToDrop());
        }
        player.sendMessage(color("&aBreach complete."));
        if (config.getBoolean("breach.restoration-enabled", false)) {
            scheduleRestoration(key, originalState, block.getLocation());
        }
    }

    private boolean isBreachable(Block block) {
        if (!block.getType().isSolid() || block.getState() instanceof TileState) {
            return false;
        }
        String material = XMaterial.matchXMaterial(block.getType()).name();
        Set<String> whitelist = configuredMaterials("breach.whitelist");
        Set<String> blacklist = configuredMaterials("breach.blacklist");
        return whitelist.contains(material) && !blacklist.contains(material);
    }

    private Set<String> configuredMaterials(String path) {
        Set<String> materials = new HashSet<>();
        for (String raw : config.getStringList(path)) {
            XMaterial.matchXMaterial(raw).map(XMaterial::name).ifPresent(materials::add);
        }
        return materials;
    }

    private void consumeCharge(Player player, ItemStack charge) {
        if (charge.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            charge.setAmount(charge.getAmount() - 1);
        }
    }

    private void scheduleRestoration(String key, BlockState originalState, Location location) {
        Restoration previous = restorations.remove(key);
        if (previous != null) {
            previous.task().cancel();
        }
        long delayTicks = config.getInt("breach.restoration-delay-seconds", 300) * 20L;
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> restore(key, originalState), delayTicks);
        restorations.put(key, new Restoration(originalState, task));
    }

    private void restore(String key, BlockState originalState) {
        Restoration restoration = restorations.get(key);
        if (restoration == null || restoration.state() != originalState) {
            return;
        }
        restorations.remove(key);
        if (originalState.getBlock().getType().isAir()) {
            originalState.update(true, false);
        }
    }

    private String blockKey(Location location) {
        return location.getWorld().getName().toLowerCase(Locale.ROOT) + ":" + location.getBlockX() + ":"
                + location.getBlockY() + ":" + location.getBlockZ();
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    @Override
    public void close() {
        for (Map.Entry<String, Restoration> entry : List.copyOf(restorations.entrySet())) {
            entry.getValue().task().cancel();
            if (entry.getValue().state().getBlock().getType().isAir()) {
                entry.getValue().state().update(true, false);
            }
        }
        restorations.clear();
    }

    private record DamageProgress(int hits, long updatedAt) {
    }

    private record Restoration(BlockState state, BukkitTask task) {
    }
}