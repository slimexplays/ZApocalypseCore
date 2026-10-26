package dev.kodari.zombieapocalypse.item;

import com.cryptomorin.xseries.XMaterial;
import dev.kodari.zombieapocalypse.ZombieApocalypsePlugin;
import dev.kodari.zombieapocalypse.config.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class LootSearchService implements Listener {
    private final ZombieApocalypsePlugin plugin;
    private final ConfigManager config;
    private final CustomItemService items;
    private final NamespacedKey cooldownKey;
    private final Map<UUID, ActiveSearch> searches = new java.util.HashMap<>();
    private final boolean worldGuardPresent;

    public LootSearchService(ZombieApocalypsePlugin plugin, ConfigManager config, CustomItemService items) {
        this.plugin = plugin;
        this.config = config;
        this.items = items;
        this.cooldownKey = new NamespacedKey("ultra_advanced_zombie_apocalypse", "loot_search_next_at");
        this.worldGuardPresent = Bukkit.getPluginManager().getPlugin("WorldGuard") != null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onContainerInteract(PlayerInteractEvent event) {
        if (!config.getBoolean("loot-search.enabled", false) || worldGuardPresent
                || event.getHand() == null || !event.getHand().name().equals("HAND")
                || !event.getAction().toString().startsWith("RIGHT_CLICK") || event.getClickedBlock() == null) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPermission("zombie.use") || !config.isWorldEnabled(player.getWorld())) {
            return;
        }
        Block block = event.getClickedBlock();
        if (!isSearchable(block) || !(block.getState() instanceof TileState)) {
            return;
        }
        event.setCancelled(true);
        startSearch(player, block);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        ActiveSearch search = searches.get(event.getPlayer().getUniqueId());
        if (search == null || event.getTo() == null) {
            return;
        }
        World world = Bukkit.getWorld(search.worldId());
        double maxDistance = config.getDouble("loot-search.max-distance-blocks", 4.5);
        if (world == null || !world.getUID().equals(event.getTo().getWorld().getUID())
                || event.getTo().distanceSquared(new org.bukkit.Location(world, search.x() + 0.5, search.y() + 0.5,
                search.z() + 0.5)) > maxDistance * maxDistance) {
            cancelSearch(event.getPlayer(), true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancelSearch(event.getPlayer(), false);
    }

    public void close() {
        for (ActiveSearch search : searches.values()) {
            search.task().cancel();
        }
        searches.clear();
    }

    private void startSearch(Player player, Block block) {
        UUID playerId = player.getUniqueId();
        if (searches.containsKey(playerId)) {
            message(player, "&eFinish your current search first.");
            return;
        }
        TileState tile = (TileState) block.getState();
        Long nextSearch = tile.getPersistentDataContainer().get(cooldownKey, PersistentDataType.LONG);
        long now = System.currentTimeMillis();
        if (nextSearch != null && nextSearch > now) {
            long remaining = Math.max(1, (nextSearch - now + 999) / 1000);
            message(player, "&cThis container was recently searched. Try again in " + remaining + "s.");
            return;
        }
        long delay = Math.max(1, config.getInt("loot-search.duration-seconds", 5)) * 20L;
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> finishSearch(player), delay);
        searches.put(playerId, new ActiveSearch(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ(),
                XMaterial.matchXMaterial(block.getType()).name(), task));
        message(player, "&eSearching... stay nearby.");
    }

    private void finishSearch(Player player) {
        ActiveSearch search = searches.remove(player.getUniqueId());
        if (search == null || !player.isOnline() || !config.getBoolean("loot-search.enabled", false)
                || worldGuardPresent || !config.isWorldEnabled(player.getWorld())) {
            return;
        }
        World world = Bukkit.getWorld(search.worldId());
        if (world == null || !world.isChunkLoaded(search.x() >> 4, search.z() >> 4)
                || player.getWorld() != world
                || player.getLocation().distanceSquared(new org.bukkit.Location(world, search.x() + 0.5,
                search.y() + 0.5, search.z() + 0.5)) > Math.pow(config.getDouble("loot-search.max-distance-blocks", 4.5), 2)) {
            message(player, "&cYour search was interrupted.");
            return;
        }
        Block block = world.getBlockAt(search.x(), search.y(), search.z());
        if (!isSearchable(block) || !XMaterial.matchXMaterial(block.getType()).name().equals(search.material())
                || !(block.getState() instanceof TileState tile)) {
            message(player, "&cThat container is no longer available.");
            return;
        }
        Long nextSearch = tile.getPersistentDataContainer().get(cooldownKey, PersistentDataType.LONG);
        long now = System.currentTimeMillis();
        if (nextSearch != null && nextSearch > now) {
            message(player, "&cSomeone else searched this container first.");
            return;
        }
        ItemStack reward = rollReward();
        if (reward == null) {
            message(player, "&cNo loot was available in this search.");
            return;
        }
        long cooldown = config.getLong("loot-search.cooldown-seconds", 3600);
        tile.getPersistentDataContainer().set(cooldownKey, PersistentDataType.LONG, now + cooldown * 1000L);
        if (!tile.update(true, false)) {
            message(player, "&cThe container changed before your search could finish.");
            return;
        }
        for (ItemStack overflow : player.getInventory().addItem(reward).values()) {
            world.dropItemNaturally(player.getLocation(), overflow);
        }
        message(player, "&aYou found supplies.");
    }

    private ItemStack rollReward() {
        ConfigurationSection weights = config.getSection("loot-search.rarity-weights");
        ConfigurationSection rewards = config.getSection("loot-search.rewards");
        if (weights == null || rewards == null) {
            return null;
        }
        double totalWeight = weights.getKeys(false).stream().mapToDouble(tier -> weights.getDouble(tier, 0)).sum();
        if (totalWeight <= 0) {
            return null;
        }
        double roll = ThreadLocalRandom.current().nextDouble(totalWeight);
        String rarity = null;
        for (String tier : weights.getKeys(false)) {
            roll -= weights.getDouble(tier, 0);
            if (roll < 0) {
                rarity = tier;
                break;
            }
        }
        if (rarity == null) {
            return null;
        }
        List<String> candidates = new ArrayList<>();
        double totalRewardWeight = 0;
        for (String itemId : rewards.getKeys(false)) {
            ConfigurationSection reward = rewards.getConfigurationSection(itemId);
            if (reward != null && reward.getString("rarity", "").equalsIgnoreCase(rarity)
                    && reward.getDouble("weight", 0) > 0) {
                candidates.add(itemId);
                totalRewardWeight += reward.getDouble("weight", 0);
            }
        }
        if (candidates.isEmpty() || totalRewardWeight <= 0) {
            return null;
        }
        roll = ThreadLocalRandom.current().nextDouble(totalRewardWeight);
        String selected = candidates.get(candidates.size() - 1);
        for (String itemId : candidates) {
            roll -= rewards.getDouble(itemId + ".weight", 0);
            if (roll < 0) {
                selected = itemId;
                break;
            }
        }
        ConfigurationSection definition = rewards.getConfigurationSection(selected);
        ItemStack result = items.create(selected);
        if (definition == null || result == null) {
            return null;
        }
        int min = definition.getInt("min", 1);
        int max = Math.min(definition.getInt("max", min), result.getMaxStackSize());
        result.setAmount(ThreadLocalRandom.current().nextInt(min, Math.max(min, max) + 1));
        return result;
    }

    private boolean isSearchable(Block block) {
        String material = XMaterial.matchXMaterial(block.getType()).name();
        return config.getStringList("loot-search.containers").stream()
                .map(value -> value.toUpperCase(Locale.ROOT)).anyMatch(material::equals);
    }

    private void cancelSearch(Player player, boolean notify) {
        ActiveSearch search = searches.remove(player.getUniqueId());
        if (search != null) {
            search.task().cancel();
            if (notify) {
                message(player, "&cYour search was interrupted.");
            }
        }
    }

    private void message(Player player, String message) {
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&6[Zombie] " + message));
    }

    private record ActiveSearch(UUID worldId, int x, int y, int z, String material, BukkitTask task) {
    }
}