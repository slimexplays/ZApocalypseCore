package dev.kodari.zombieapocalypse.environment;

import com.cryptomorin.xseries.XPotion;
import dev.kodari.zombieapocalypse.ZombieApocalypsePlugin;
import dev.kodari.zombieapocalypse.config.ConfigManager;
import dev.kodari.zombieapocalypse.protection.ProtectionService;
import dev.kodari.zombieapocalypse.regional.RegionalInfectionService;
import dev.kodari.zombieapocalypse.regional.RegionalInfectionService.RegionStatus;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class ToxicFogService implements AutoCloseable {
    private final ZombieApocalypsePlugin plugin;
    private final ConfigManager config;
    private final ProtectionService protection;
    private final RegionalInfectionService regions;
    private final Map<UUID, Long> lastExposureAt = new HashMap<>();
    private final Set<UUID> warnedPlayers = new HashSet<>();
    private BukkitTask task;

    public ToxicFogService(ZombieApocalypsePlugin plugin, ConfigManager config, ProtectionService protection,
                           RegionalInfectionService regions) {
        this.plugin = plugin;
        this.config = config;
        this.protection = protection;
        this.regions = regions;
    }

    public void start() {
        if (task == null) {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        }
    }

    private void tick() {
        if (!config.getBoolean("toxic-fog.enabled", false)) {
            clearState();
            return;
        }
        long now = System.currentTimeMillis();
        long interval = config.getLong("toxic-fog.damage-interval-seconds", 5) * 1000L;
        double damage = config.getDouble("toxic-fog.exposure-damage", 0.5);
        double infectionChance = config.getDouble("toxic-fog.infection-chance-per-exposure", 0.0);
        Set<UUID> exposed = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            RegionStatus region = regions.regionAt(player.getLocation());
            if (region == null || !config.getStringList("toxic-fog.region-types").contains(region.type())
                    || protection.protects(player, "toxic")) {
                continue;
            }
            UUID playerId = player.getUniqueId();
            exposed.add(playerId);
            if (warnedPlayers.add(playerId)) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                        config.getString("toxic-fog.warning", "&2Toxic fog is burning your lungs!")));
            }
            XPotion.matchXPotion(config.getString("toxic-fog.effect", "POISON"))
                    .map(potion -> potion.buildPotionEffect(config.getInt("toxic-fog.effect-duration-ticks", 100),
                            config.getInt("toxic-fog.effect-amplifier", 0)))
                    .ifPresent(player::addPotionEffect);
            Long previous = lastExposureAt.get(playerId);
            if (previous != null && now - previous < interval) {
                continue;
            }
            lastExposureAt.put(playerId, now);
            if (damage > 0) {
                player.damage(damage);
            }
            if (infectionChance > 0 && ThreadLocalRandom.current().nextDouble() < infectionChance) {
                plugin.infections().infect(player);
            }
        }
        warnedPlayers.retainAll(exposed);
        lastExposureAt.keySet().retainAll(exposed);
    }

    private void clearState() {
        warnedPlayers.clear();
        lastExposureAt.clear();
    }

    @Override
    public void close() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        clearState();
    }
}