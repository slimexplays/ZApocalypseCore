package dev.kodari.zombieapocalypse.regional;

import dev.kodari.zombieapocalypse.ZombieApocalypsePlugin;
import dev.kodari.zombieapocalypse.config.ConfigManager;
import dev.kodari.zombieapocalypse.storage.RegionalInfectionStore;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

public final class RegionalInfectionService {
    private final ZombieApocalypsePlugin plugin;
    private final ConfigManager config;
    private final RegionalInfectionStore store;
    private final Map<String, RegionDefinition> definitions = new LinkedHashMap<>();
    private final Map<String, Double> levels = new LinkedHashMap<>();
    private volatile boolean loaded;
    private boolean pendingSave;
    private int cursor;
    private BukkitTask task;

    public RegionalInfectionService(ZombieApocalypsePlugin plugin, ConfigManager config, RegionalInfectionStore store) {
        this.plugin = plugin;
        this.config = config;
        this.store = store;
        reloadDefinitions();
        store.load().whenComplete((storedLevels, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                plugin.getLogger().severe("Regional infection data load failed: " + error.getMessage());
                return;
            }
            storedLevels.forEach((id, level) -> {
                if (definitions.containsKey(id)) {
                    levels.put(id, level);
                }
            });
            loaded = true;
            if (pendingSave) {
                pendingSave = false;
                store.save(levels);
            }
            schedule();
        }));
    }

    public void reload() {
        reloadDefinitions();
        if (loaded) {
            store.save(levels);
            schedule();
        }
    }

    private void reloadDefinitions() {
        Map<String, Double> previousLevels = new HashMap<>(levels);
        definitions.clear();
        levels.clear();
        ConfigurationSection regions = config.getSection("regional-infection.regions");
        if (regions == null) {
            return;
        }
        for (String id : new TreeSet<>(regions.getKeys(false))) {
            ConfigurationSection section = regions.getConfigurationSection(id);
            if (section == null) {
                continue;
            }
            RegionDefinition definition = new RegionDefinition(id, section.getString("world", ""),
                    section.getInt("min-x"), section.getInt("max-x"),
                    section.getInt("min-y"), section.getInt("max-y"),
                    section.getInt("min-z"), section.getInt("max-z"));
            definitions.put(id, definition);
            levels.put(id, previousLevels.getOrDefault(id, section.getDouble("starting-level", 0)));
        }
        cursor = 0;
    }

    private void schedule() {
        if (task != null) {
            task.cancel();
        }
        long interval = config.getInt("regional-infection.update-interval-ticks", 1200);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, interval, interval);
    }

    private void tick() {
        if (!loaded || !config.getBoolean("regional-infection.enabled", true) || definitions.isEmpty()) {
            return;
        }
        List<String> ids = new ArrayList<>(definitions.keySet());
        int budget = Math.min(ids.size(), config.getInt("regional-infection.regions-per-update", 16));
        boolean changed = false;
        for (int checked = 0; checked < budget; checked++) {
            if (cursor >= ids.size()) {
                cursor = 0;
            }
            String id = ids.get(cursor++);
            RegionDefinition target = definitions.get(id);
            double oldLevel = levels.getOrDefault(id, 0.0);
            double nextLevel;
            if (config.getBoolean("regional-infection.automatic-spread", false) && hasNearbySource(target, id)) {
                nextLevel = Math.min(100, oldLevel + config.getDouble("regional-infection.spread-per-update", 1));
            } else {
                nextLevel = Math.max(0, oldLevel - config.getDouble("regional-infection.decay-per-update", 0.25));
            }
            if (Math.abs(nextLevel - oldLevel) > 0.000001) {
                levels.put(id, nextLevel);
                changed = true;
                if (type(oldLevel) != type(nextLevel)) {
                    notifyRegionTransition(target, id, type(nextLevel));
                }
            }
        }
        if (changed) {
            save();
        }
    }

    private boolean hasNearbySource(RegionDefinition target, String targetId) {
        double threshold = config.getDouble("regional-infection.spread-threshold", 50);
        double radiusSquared = Math.pow(config.getDouble("regional-infection.spread-radius-blocks", 512), 2);
        for (Map.Entry<String, RegionDefinition> entry : definitions.entrySet()) {
            if (entry.getKey().equals(targetId)) {
                continue;
            }
            RegionDefinition source = entry.getValue();
            if (!source.world.equalsIgnoreCase(target.world) || levels.getOrDefault(entry.getKey(), 0.0) < threshold) {
                continue;
            }
            double dx = target.centerX() - source.centerX();
            double dz = target.centerZ() - source.centerZ();
            if (dx * dx + dz * dz <= radiusSquared) {
                return true;
            }
        }
        return false;
    }

    private void notifyRegionTransition(RegionDefinition definition, String id, RegionType newType) {
        if (!config.getBoolean("regional-infection.notify-players", true)) {
            return;
        }
        String message = config.getString("regional-infection.transition-message",
                "&cRegion %region% is now %type%.");
        message = ChatColor.translateAlternateColorCodes('&', message.replace("%region%", id).replace("%type%", newType.id));
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (definition.contains(player.getLocation())) {
                player.sendMessage(message);
            }
        }
    }

    public RegionStatus regionAt(Location location) {
        if (location.getWorld() == null) {
            return null;
        }
        for (Map.Entry<String, RegionDefinition> entry : definitions.entrySet()) {
            if (entry.getValue().contains(location)) {
                double level = levels.getOrDefault(entry.getKey(), 0.0);
                return new RegionStatus(entry.getKey(), entry.getValue().world, level, type(level).id);
            }
        }
        return null;
    }

    public double infectionChanceMultiplier(Location location) {
        RegionStatus status = regionAt(location);
        if (status == null) {
            return 1;
        }
        return config.getDouble("regional-infection.infection-chance-multipliers." + status.type(), 1);
    }

    public List<RegionStatus> regions() {
        List<RegionStatus> statuses = new ArrayList<>();
        for (Map.Entry<String, RegionDefinition> entry : definitions.entrySet()) {
            double level = levels.getOrDefault(entry.getKey(), 0.0);
            statuses.add(new RegionStatus(entry.getKey(), entry.getValue().world, level, type(level).id));
        }
        return List.copyOf(statuses);
    }

    public RegionStatus region(String id) {
        RegionDefinition definition = definitions.get(id);
        if (definition == null) {
            return null;
        }
        double level = levels.getOrDefault(id, 0.0);
        return new RegionStatus(id, definition.world, level, type(level).id);
    }

    public boolean setLevel(String id, double level) {
        if (!loaded || !definitions.containsKey(id) || !Double.isFinite(level) || level < 0 || level > 100) {
            return false;
        }
        double previous = levels.getOrDefault(id, 0.0);
        levels.put(id, level);
        save();
        if (type(previous) != type(level)) {
            notifyRegionTransition(definitions.get(id), id, type(level));
        }
        return true;
    }

    public boolean isLoaded() {
        return loaded;
    }

    private RegionType type(double level) {
        if (level < config.getDouble("regional-infection.thresholds.quarantined", 25)) {
            return RegionType.CLEAN;
        }
        if (level < config.getDouble("regional-infection.thresholds.contaminated", 50)) {
            return RegionType.QUARANTINED;
        }
        if (level < config.getDouble("regional-infection.thresholds.high-risk", 75)) {
            return RegionType.CONTAMINATED;
        }
        return RegionType.HIGH_RISK;
    }

    private void save() {
        if (loaded) {
            store.save(levels);
        } else {
            pendingSave = true;
        }
    }

    public void close() {
        if (task != null) {
            task.cancel();
        }
        if (loaded) {
            store.save(levels);
        }
    }

    private enum RegionType {
        CLEAN("clean"), QUARANTINED("quarantined"), CONTAMINATED("contaminated"), HIGH_RISK("high-risk");

        private final String id;

        RegionType(String id) {
            this.id = id;
        }
    }

    public record RegionStatus(String id, String world, double level, String type) {
    }

    private record RegionDefinition(String id, String world, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        private boolean contains(Location location) {
            return location.getWorld() != null && location.getWorld().getName().equalsIgnoreCase(world)
                    && location.getBlockX() >= minX && location.getBlockX() <= maxX
                    && location.getBlockY() >= minY && location.getBlockY() <= maxY
                    && location.getBlockZ() >= minZ && location.getBlockZ() <= maxZ;
        }

        private double centerX() {
            return (minX + (double) maxX) / 2;
        }

        private double centerZ() {
            return (minZ + (double) maxZ) / 2;
        }
    }
}