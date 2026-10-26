package dev.kodari.zombieapocalypse.environment;

import dev.kodari.zombieapocalypse.ZombieApocalypsePlugin;
import dev.kodari.zombieapocalypse.config.ConfigManager;
import dev.kodari.zombieapocalypse.infection.InfectionManager;
import dev.kodari.zombieapocalypse.protection.ProtectionService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class AcidRainService {
    private final ZombieApocalypsePlugin plugin;
    private final ConfigManager config;
    private final ProtectionService protection;
    private final InfectionManager infections;
    private final Map<UUID, Long> stormStartedAt = new HashMap<>();
    private final Map<UUID, Long> lastExposureAt = new HashMap<>();
    private final Set<UUID> warnedPlayers = new HashSet<>();
    private BukkitTask task;

    public AcidRainService(ZombieApocalypsePlugin plugin, ConfigManager config,
                           ProtectionService protection, InfectionManager infections) {
        this.plugin = plugin;
        this.config = config;
        this.protection = protection;
        this.infections = infections;
    }

    public void start() {
        if (task == null) {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        }
    }

    private void tick() {
        if (!config.getBoolean("acid-rain.enabled", false)) {
            clearState();
            return;
        }

        long now = System.currentTimeMillis();
        Set<UUID> rainingWorlds = new HashSet<>();
        for (World world : Bukkit.getWorlds()) {
            UUID worldId = world.getUID();
            if (config.isWorldEnabled(world) && world.hasStorm()) {
                rainingWorlds.add(worldId);
                stormStartedAt.putIfAbsent(worldId, now);
            } else {
                stormStartedAt.remove(worldId);
            }
        }
        stormStartedAt.keySet().retainAll(rainingWorlds);

        long maximumDurationMillis = config.getLong("acid-rain.event-duration-seconds", 600) * 1000L;
        long exposureIntervalMillis = config.getLong("acid-rain.damage-interval-seconds", 5) * 1000L;
        double damage = config.getDouble("acid-rain.exposure-damage", 1.0);
        double infectionChance = config.getDouble("acid-rain.infection-chance-per-exposure", 0.0);
        Set<UUID> exposedPlayers = new HashSet<>();

        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID worldId = player.getWorld().getUID();
            Long startedAt = stormStartedAt.get(worldId);
            if (startedAt == null || now - startedAt >= maximumDurationMillis
                    || !isOutdoors(player) || protection.protects(player, "acid")) {
                continue;
            }

            UUID playerId = player.getUniqueId();
            exposedPlayers.add(playerId);
            if (warnedPlayers.add(playerId)) {
                String warning = config.getString("acid-rain.warning", "&cAcid rain is burning your exposed skin!");
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', warning));
            }

            Long lastExposure = lastExposureAt.get(playerId);
            if (lastExposure != null && now - lastExposure < exposureIntervalMillis) {
                continue;
            }
            lastExposureAt.put(playerId, now);

            if (damage > 0) {
                player.damage(damage);
            }
            if (infectionChance > 0 && ThreadLocalRandom.current().nextDouble() < infectionChance) {
                infections.infect(player);
            }
        }

        warnedPlayers.retainAll(exposedPlayers);
        lastExposureAt.keySet().retainAll(exposedPlayers);
    }

    private boolean isOutdoors(Player player) {
        World world = player.getWorld();
        int x = player.getLocation().getBlockX();
        int y = player.getLocation().getBlockY();
        int z = player.getLocation().getBlockZ();
        return world.getHighestBlockYAt(x, z) < y;
    }

    private void clearState() {
        stormStartedAt.clear();
        lastExposureAt.clear();
        warnedPlayers.clear();
    }

    public void close() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        clearState();
    }
}