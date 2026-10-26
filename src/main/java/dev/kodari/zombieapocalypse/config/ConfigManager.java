package dev.kodari.zombieapocalypse.config;

import dev.kodari.zombieapocalypse.ZombieApocalypsePlugin;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

public final class ConfigManager {
    private final ZombieApocalypsePlugin plugin;
    private volatile YamlConfiguration active;

    public ConfigManager(ZombieApocalypsePlugin plugin) {
        this.plugin = plugin;
    }

    public boolean reload() {
        File file = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration candidate = YamlConfiguration.loadConfiguration(file);
        candidate.setDefaults(plugin.getConfig());
        if (!validate(candidate)) {
            return false;
        }
        candidate.options().copyDefaults(true);
        try {
            candidate.save(file);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not persist newly introduced configuration defaults: " + exception.getMessage());
        }
        active = candidate;
        return true;
    }

    private boolean validate(YamlConfiguration config) {
        boolean valid = true;
        valid &= range(config, "general.max-zombies-per-world", 1, 10000);
        valid &= range(config, "general.max-spawn-per-command", 1, 100);
        valid &= range(config, "spawning.interval-ticks", 200, 72000);
        valid &= range(config, "spawning.attempts-per-player", 1, 10);
        valid &= range(config, "spawning.min-distance-blocks", 8, 128);
        valid &= range(config, "spawning.max-distance-blocks", 8, 192);
        valid &= range(config, "spawning.hordes.interval-ticks", 1200, 172800);
        valid &= range(config, "spawning.hordes.min-size", 1, 50);
        valid &= range(config, "spawning.hordes.max-size", 1, 100);
        valid &= lessOrEqual(config, "spawning.min-distance-blocks", "spawning.max-distance-blocks");
        valid &= lessOrEqual(config, "spawning.hordes.min-size", "spawning.hordes.max-size");
        valid &= range(config, "ai.update-interval-ticks", 5, 1200);
        valid &= range(config, "ai.detection-radius", 1, 128);
        valid &= range(config, "ai.alert-radius", 1, 128);
        valid &= range(config, "ai.noise-investigation-radius", 1, 128);
        valid &= range(config, "ai.tracked-zombies-per-update", 1, 512);
        valid &= range(config, "infection.bite-probability", 0, 1);
        valid &= range(config, "infection.infected-after-seconds", 1, 31536000);
        valid &= range(config, "infection.critical-after-seconds", 1, 31536000);
        valid &= range(config, "infection.critical-death-after-seconds", 0, 31536000);
        valid &= range(config, "infection.immunity-after-cure-seconds", 0, 31536000);
        valid &= range(config, "noise.movement-throttle-millis", 100, 10000);
        valid &= range(config, "survival.update-interval-ticks", 20, 1200);
        valid &= range(config, "survival.save-interval-ticks", 20, 72000);
        valid &= range(config, "survival.thirst.max", 1, 1000);
        valid &= range(config, "survival.thirst.starting", 0, 1000);
        valid &= range(config, "survival.thirst.drain-per-minute", 0, 100);
        valid &= range(config, "survival.thirst.sprint-drain-per-minute", 0, 500);
        valid &= range(config, "survival.thirst.low-threshold", 0, 1000);
        valid &= range(config, "survival.thirst.empty-damage", 0, 20);
        valid &= range(config, "survival.thirst.effect-threshold", 0, 1000);
        valid &= range(config, "survival.thirst.effect-amplifier", 0, 10);
        valid &= range(config, "survival.stamina.max", 1, 1000);
        valid &= range(config, "survival.stamina.starting", 0, 1000);
        valid &= range(config, "survival.stamina.sprint-drain-per-minute", 0, 500);
        valid &= range(config, "survival.stamina.regeneration-per-minute", 0, 500);
        valid &= range(config, "survival.food-spoilage.seconds", 60, 31536000);
        valid &= range(config, "radio.range-blocks", 0, 100000);
        valid &= range(config, "radio.noise-radius", 0, 128);
        valid &= range(config, "acid-rain.damage-interval-seconds", 1, 120);
        valid &= range(config, "acid-rain.event-duration-seconds", 1, 86400);
        valid &= range(config, "acid-rain.exposure-damage", 0, 20);
        valid &= range(config, "acid-rain.infection-chance-per-exposure", 0, 1);
        valid &= lessOrEqual(config, "survival.thirst.starting", "survival.thirst.max");
        valid &= lessOrEqual(config, "survival.thirst.low-threshold", "survival.thirst.max");
        valid &= lessOrEqual(config, "survival.thirst.effect-threshold", "survival.thirst.max");
        valid &= lessOrEqual(config, "survival.stamina.starting", "survival.stamina.max");
        valid &= validateItems(config);
        valid &= validateFirearms(config);
        valid &= validateLootSearch(config);
        valid &= validateWaterPoints(config);
        valid &= validateRegionalInfection(config);
        for (String variant : List.of("normal", "runner", "brute", "crawler", "screamer", "armored", "toxic", "demolisher", "alpha", "boss", "stealth", "mutated", "revenant")) {
            String prefix = "variants." + variant + ".";
            valid &= range(config, prefix + "health", 1, 2048);
            valid &= range(config, prefix + "damage", 0, 2048);
            valid &= range(config, prefix + "speed", 0.01, 2);
            valid &= range(config, prefix + "resistance", 0, 1);
            valid &= range(config, prefix + "spawn-chance", 0, 1);
            valid &= range(config, prefix + "drop-chance", 0, 1);
        }
        return valid;
    }

    private boolean validateRegionalInfection(YamlConfiguration config) {
        boolean valid = true;
        valid &= range(config, "regional-infection.update-interval-ticks", 20, 72000);
        valid &= range(config, "regional-infection.regions-per-update", 1, 256);
        valid &= range(config, "regional-infection.max-regions", 1, 256);
        valid &= range(config, "regional-infection.spread-radius-blocks", 0, 1000000);
        valid &= range(config, "regional-infection.spread-threshold", 0, 100);
        valid &= range(config, "regional-infection.spread-per-update", 0, 100);
        valid &= range(config, "regional-infection.decay-per-update", 0, 100);
        valid &= range(config, "regional-infection.spawn-pressure.ambient-attempts-at-100", 0, 10);
        valid &= range(config, "regional-infection.spawn-pressure.horde-size-at-100", 0, 50);
        valid &= range(config, "regional-infection.thresholds.quarantined", 0, 100);
        valid &= range(config, "regional-infection.thresholds.contaminated", 0, 100);
        valid &= range(config, "regional-infection.thresholds.high-risk", 0, 100);
        valid &= range(config, "regional-infection.infection-chance-multipliers.clean", 0, 4);
        valid &= range(config, "regional-infection.infection-chance-multipliers.quarantined", 0, 4);
        valid &= range(config, "regional-infection.infection-chance-multipliers.contaminated", 0, 4);
        valid &= range(config, "regional-infection.infection-chance-multipliers.high-risk", 0, 4);
        valid &= lessOrEqual(config, "regional-infection.regions-per-update", "regional-infection.max-regions");
        double quarantined = config.getDouble("regional-infection.thresholds.quarantined", Double.NaN);
        double contaminated = config.getDouble("regional-infection.thresholds.contaminated", Double.NaN);
        double highRisk = config.getDouble("regional-infection.thresholds.high-risk", Double.NaN);
        if (!(quarantined < contaminated && contaminated < highRisk)) {
            plugin.getLogger().severe("Regional infection thresholds must be strictly increasing.");
            valid = false;
        }
        ConfigurationSection regions = config.getConfigurationSection("regional-infection.regions");
        if (regions == null) {
            plugin.getLogger().severe("Missing configuration section 'regional-infection.regions'.");
            return false;
        }
        int maximum = config.getInt("regional-infection.max-regions", 256);
        if (regions.getKeys(false).size() > maximum) {
            plugin.getLogger().severe("Configured regional infection regions exceed regional-infection.max-regions.");
            valid = false;
        }
        for (String id : regions.getKeys(false)) {
            ConfigurationSection region = regions.getConfigurationSection(id);
            String prefix = "regional-infection.regions." + id + ".";
            if (!id.matches("[a-z0-9_-]{1,32}") || region == null || region.getString("world", "").isBlank()) {
                plugin.getLogger().severe("Invalid regional infection region '" + id + "'; use a 1-32 character lowercase ID and a world name.");
                valid = false;
                continue;
            }
            for (String coordinate : List.of("min-x", "max-x", "min-y", "max-y", "min-z", "max-z")) {
                if (!region.isInt(coordinate)) {
                    plugin.getLogger().severe("Invalid coordinate '" + prefix + coordinate + "'; an integer is required.");
                    valid = false;
                }
            }
            if (List.of("min-x", "max-x", "min-y", "max-y", "min-z", "max-z").stream().allMatch(region::isInt)) {
                if (region.getInt("min-x") < -30000000 || region.getInt("max-x") > 30000000
                        || region.getInt("min-z") < -30000000 || region.getInt("max-z") > 30000000
                        || region.getInt("min-y") < -2048 || region.getInt("max-y") > 2048
                        || region.getInt("min-x") > region.getInt("max-x")
                        || region.getInt("min-y") > region.getInt("max-y")
                        || region.getInt("min-z") > region.getInt("max-z")) {
                    plugin.getLogger().severe("Invalid cuboid bounds for regional infection region '" + id + "'.");
                    valid = false;
                }
            }
            valid &= range(config, prefix + "starting-level", 0, 100);
        }
        return valid;
    }

    private boolean validateItems(ConfigurationSection config) {
        ConfigurationSection items = config.getConfigurationSection("items");
        if (items == null) {
            plugin.getLogger().severe("Missing configuration section 'items'.");
            return false;
        }
        boolean valid = true;
        for (String itemId : items.getKeys(false)) {
            String prefix = "items." + itemId + ".";
            valid &= optionalRange(config, prefix + "heal", 0, 40);
            valid &= optionalRange(config, prefix + "food-level", 0, 20);
            valid &= optionalRange(config, prefix + "saturation", 0, 20);
            valid &= optionalRange(config, prefix + "hydration", 0, 1000);
            valid &= optionalRange(config, prefix + "stamina", 0, 1000);
            valid &= optionalRange(config, prefix + "immunity-seconds", 0, 31536000);
            valid &= optionalRange(config, prefix + "cooldown-seconds", 0, 31536000);
            valid &= optionalRange(config, prefix + "effect-duration-ticks", 1, 72000);
            valid &= optionalRange(config, prefix + "effect-amplifier", 0, 10);
            valid &= optionalRange(config, prefix + "contamination-chance", 0, 1);
            valid &= optionalRange(config, prefix + "infection-resistance", 0, 1);
            valid &= optionalRange(config, prefix + "repair-durability", 0, 100000);
            valid &= optionalRange(config, prefix + "repair-cost-amount", 0, 64);
            if (config.contains(prefix + "storage-size")) {
                valid &= range(config, prefix + "storage-size", 9, 54);
                int size = config.getInt(prefix + "storage-size", 0);
                if (size % 9 != 0) {
                    plugin.getLogger().severe("Invalid storage size for '" + itemId + "'; expected a multiple of 9.");
                    valid = false;
                }
            }
        }
        return valid;
    }

    private boolean validateFirearms(YamlConfiguration config) {
        if (!config.getBoolean("firearms.enabled", true)) {
            return true;
        }
        boolean valid = true;
        valid &= range(config, "firearms.max-range-blocks", 1, 256);
        valid &= range(config, "firearms.noise-radius", 0, 512);
        valid &= range(config, "firearms.zombie-damage-multipliers.default", 0, 5);
        for (String variant : List.of("normal", "runner", "brute", "crawler", "screamer", "armored", "toxic",
                "demolisher", "alpha", "boss", "stealth", "mutated", "revenant")) {
            valid &= optionalRange(config, "firearms.zombie-damage-multipliers." + variant, 0, 5);
        }
        ConfigurationSection guns = config.getConfigurationSection("firearms.guns");
        if (guns == null) {
            plugin.getLogger().severe("Missing configuration section 'firearms.guns'.");
            return false;
        }
        for (String gunId : guns.getKeys(false)) {
            String prefix = "firearms.guns." + gunId + ".";
            String ammoId = config.getString(prefix + "ammo", "");
            if (!gunId.matches("[a-z0-9_-]{1,48}") || config.getConfigurationSection("items." + gunId) == null) {
                plugin.getLogger().severe("Firearm '" + gunId + "' must have a matching custom item definition.");
                valid = false;
            }
            if (ammoId.isBlank() || config.getConfigurationSection("items." + ammoId) == null) {
                plugin.getLogger().severe("Firearm '" + gunId + "' references an unknown ammo item.");
                valid = false;
            }
            valid &= range(config, prefix + "magazine", 1, 200);
            valid &= range(config, prefix + "damage", 0.1, 100);
            valid &= range(config, prefix + "range", 1, 256);
            valid &= range(config, prefix + "spread", 0, 1);
            valid &= range(config, prefix + "recoil", 0, 15);
            valid &= range(config, prefix + "pellets", 1, 16);
            valid &= range(config, prefix + "durability", 1, 100000);
            valid &= range(config, prefix + "fire-delay-ticks", 1, 1200);
            valid &= range(config, prefix + "reload-ticks", 1, 2400);
            valid &= range(config, prefix + "burst-shots", 1, 10);
            String mode = config.getString(prefix + "fire-mode", "").toUpperCase(Locale.ROOT);
            if (!List.of("SEMI", "AUTO", "BURST").contains(mode)) {
                plugin.getLogger().severe("Invalid fire mode for '" + gunId + "'; expected SEMI, AUTO, or BURST.");
                valid = false;
            }
        }
        return valid;
    }

    private boolean validateLootSearch(YamlConfiguration config) {
        if (!config.getBoolean("loot-search.enabled", false)) {
            return true;
        }
        boolean valid = true;
        valid &= range(config, "loot-search.duration-seconds", 1, 300);
        valid &= range(config, "loot-search.cooldown-seconds", 1, 31536000);
        valid &= range(config, "loot-search.max-distance-blocks", 1, 12);
        ConfigurationSection weights = config.getConfigurationSection("loot-search.rarity-weights");
        ConfigurationSection rewards = config.getConfigurationSection("loot-search.rewards");
        if (config.getStringList("loot-search.containers").isEmpty() || weights == null || rewards == null) {
            plugin.getLogger().severe("Enabled loot search requires containers, rarity weights, and rewards sections.");
            return false;
        }
        double totalWeight = 0;
        for (String tier : weights.getKeys(false)) {
            valid &= range(weights, tier, 0, 1000000);
            totalWeight += weights.getDouble(tier, 0);
        }
        if (totalWeight <= 0) {
            plugin.getLogger().severe("Loot search rarity weights must contain a positive total weight.");
            valid = false;
        }
        for (String itemId : rewards.getKeys(false)) {
            String prefix = "loot-search.rewards." + itemId + ".";
            ConfigurationSection reward = rewards.getConfigurationSection(itemId);
            String rarity = config.getString(prefix + "rarity", "");
            if (reward == null || config.getConfigurationSection("items." + itemId) == null
                    || !weights.contains(rarity.toLowerCase(Locale.ROOT))) {
                plugin.getLogger().severe("Invalid loot search reward '" + itemId + "': item and weighted rarity are required.");
                valid = false;
                continue;
            }
            valid &= range(config, prefix + "weight", 0.0001, 1000000);
            valid &= range(config, prefix + "min", 1, 64);
            valid &= range(config, prefix + "max", 1, 64);
            if (reward.getInt("min", 1) > reward.getInt("max", 1)) {
                plugin.getLogger().severe("Loot search reward minimum exceeds maximum for '" + itemId + "'.");
                valid = false;
            }
        }
        for (String tier : weights.getKeys(false)) {
            if (weights.getDouble(tier, 0) > 0 && rewards.getKeys(false).stream().noneMatch(itemId -> {
                ConfigurationSection reward = rewards.getConfigurationSection(itemId);
                return reward != null && reward.getString("rarity", "").equalsIgnoreCase(tier)
                        && reward.getDouble("weight", 0) > 0;
            })) {
                plugin.getLogger().severe("Loot search rarity '" + tier + "' has weight but no matching reward.");
                valid = false;
            }
        }
        return valid;
    }

    private boolean validateWaterPoints(ConfigurationSection config) {
        boolean valid = true;
        for (String entry : config.getStringList("survival.water-points.locations")) {
            String[] fields = entry.split(",", -1);
            try {
                if (fields.length != 5 || fields[0].isBlank()
                        || (!fields[4].trim().equalsIgnoreCase("clean") && !fields[4].trim().equalsIgnoreCase("contaminated"))) {
                    throw new IllegalArgumentException();
                }
                Integer.parseInt(fields[1].trim());
                Integer.parseInt(fields[2].trim());
                Integer.parseInt(fields[3].trim());
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().severe("Invalid water point '" + entry + "'; expected world,x,y,z,clean|contaminated.");
                valid = false;
            }
        }
        return valid;
    }

    private boolean optionalRange(ConfigurationSection section, String path, double min, double max) {
        return !section.contains(path) || range(section, path, min, max);
    }

    private boolean lessOrEqual(ConfigurationSection section, String leftPath, String rightPath) {
        double left = section.getDouble(leftPath, Double.NaN);
        double right = section.getDouble(rightPath, Double.NaN);
        if (left <= right) {
            return true;
        }
        plugin.getLogger().severe("Invalid configuration relationship: '" + leftPath + "' must not exceed '" + rightPath + "'.");
        return false;
    }

    private boolean range(ConfigurationSection section, String path, double min, double max) {
        double value = section.getDouble(path, Double.NaN);
        if (!Double.isFinite(value) || value < min || value > max) {
            plugin.getLogger().severe("Invalid or missing configuration value '" + path + "' (expected " + min + ".." + max + ").");
            return false;
        }
        return true;
    }

    public boolean isWorldEnabled(World world) {
        YamlConfiguration config = active;
        if (config == null || !config.getBoolean("general.enabled", true) || !config.getBoolean("worlds.enabled", true)) {
            return false;
        }
        List<String> worlds = config.getStringList("worlds.enabled-worlds");
        return worlds.isEmpty() || worlds.stream().anyMatch(name -> name.equalsIgnoreCase(world.getName()));
    }

    public boolean getBoolean(String path, boolean fallback) {
        return active == null ? fallback : active.getBoolean(path, fallback);
    }

    public double getDouble(String path, double fallback) {
        return active == null ? fallback : active.getDouble(path, fallback);
    }

    public long getLong(String path, long fallback) {
        return active == null ? fallback : active.getLong(path, fallback);
    }

    public int getInt(String path, int fallback) {
        return active == null ? fallback : active.getInt(path, fallback);
    }

    public String getString(String path, String fallback) {
        return active == null ? fallback : active.getString(path, fallback);
    }

    public List<String> getStringList(String path) {
        return active == null ? List.of() : List.copyOf(active.getStringList(path));
    }

    public ConfigurationSection getSection(String path) {
        return active == null ? null : active.getConfigurationSection(path);
    }
}