package dev.kodari.zombieapocalypse.noise;

import dev.kodari.zombieapocalypse.config.ConfigManager;
import dev.kodari.zombieapocalypse.zombie.ZombieService;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class NoiseService {
    private final ConfigManager config;
    private final ZombieService zombies;
    private final Map<UUID, Long> lastNoise = new HashMap<>();

    public NoiseService(ConfigManager config, ZombieService zombies) {
        this.config = config;
        this.zombies = zombies;
    }

    public void movement(Player player) {
        long now = System.currentTimeMillis();
        long throttle = config.getLong("noise.movement-throttle-millis", 800);
        long previous = lastNoise.getOrDefault(player.getUniqueId(), 0L);
        if (now - previous < throttle) {
            return;
        }
        lastNoise.put(player.getUniqueId(), now);
        String type = player.isSneaking() ? "sneak" : player.isSprinting() ? "sprint" : "walk";
        emit(player, config.getDouble("noise." + type + "-radius", 8));
    }

    public void blockBreak(Player player) {
        emit(player, config.getDouble("noise.block-break-radius", 14));
    }

    public void doorUse(Player player) {
        emit(player, config.getDouble("noise.door-radius", 10));
    }

    public void emit(Player source, double radius) {
        if (radius > 0 && source.isOnline()) {
            zombies.investigate(source, radius);
        }
    }

    public void emit(Location location, double radius) {
        if (location.getWorld() == null || radius <= 0) {
            return;
        }
        for (Player player : location.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(location) <= radius * radius) {
                zombies.investigate(player, radius);
            }
        }
    }
}