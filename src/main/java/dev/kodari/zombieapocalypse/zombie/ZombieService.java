package dev.kodari.zombieapocalypse.zombie;

import com.cryptomorin.xseries.XAttribute;
import dev.kodari.zombieapocalypse.ZombieApocalypsePlugin;
import dev.kodari.zombieapocalypse.config.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class ZombieService {
    private final ZombieApocalypsePlugin plugin;
    private final ConfigManager config;
    private final NamespacedKey variantKey;
    private final List<UUID> tracked = new ArrayList<>();
    private final Map<UUID, TargetMemory> targetMemories = new HashMap<>();
    private BukkitTask task;
    private BukkitTask spawnTask;
    private BukkitTask hordeTask;
    private int cursor;

    public ZombieService(ZombieApocalypsePlugin plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
        this.variantKey = new NamespacedKey("ultra_advanced_zombie_apocalypse", "zombie_variant");
    }

    public void start() {
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                trackIfCustom(entity);
            }
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::updateBatch,
                config.getInt("ai.update-interval-ticks", 20), config.getInt("ai.update-interval-ticks", 20));
        if (config.getBoolean("spawning.enabled", true)) {
            int interval = config.getInt("spawning.interval-ticks", 1200);
            spawnTask = Bukkit.getScheduler().runTaskTimer(plugin, this::spawnForOnlinePlayers, interval, interval);
            if (config.getBoolean("spawning.hordes.enabled", true)) {
                int hordeInterval = config.getInt("spawning.hordes.interval-ticks", 12000);
                hordeTask = Bukkit.getScheduler().runTaskTimer(plugin, this::spawnHorde, hordeInterval, hordeInterval);
            }
        }
    }

    public Zombie spawn(Location location, ZombieVariant variant) {
        if (location.getWorld() == null || !config.isWorldEnabled(location.getWorld())) {
            return null;
        }
        int cap = config.getInt("general.max-zombies-per-world", 200);
        if (location.getWorld().getEntitiesByClass(Zombie.class).size() >= cap) {
            return null;
        }
        Zombie zombie = location.getWorld().spawn(location, Zombie.class);
        applyVariant(zombie, variant);
        trackIfCustom(zombie);
        return zombie;
    }

    private void spawnForOnlinePlayers() {
        int attempts = config.getInt("spawning.attempts-per-player", 1);
        double minDistance = config.getDouble("spawning.min-distance-blocks", 24);
        double maxDistance = config.getDouble("spawning.max-distance-blocks", 48);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!canSpawnNear(player)) {
                continue;
            }
            int pressureBonus = (int) Math.round(regionalSpawnPressure(player.getLocation())
                    * config.getInt("regional-infection.spawn-pressure.ambient-attempts-at-100", 2) / 100.0);
            for (int attempt = 0; attempt < attempts + pressureBonus; attempt++) {
                Location location = findSpawnLocation(player.getLocation(), minDistance, maxDistance);
                if (location != null) {
                    spawn(location, chooseVariant());
                }
            }
        }
    }

    private void spawnHorde() {
        List<? extends Player> candidates = Bukkit.getOnlinePlayers().stream().filter(this::canSpawnNear).toList();
        if (candidates.isEmpty()) {
            return;
        }
        Player target = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        Location center = findSpawnLocation(target.getLocation(),
                config.getDouble("spawning.min-distance-blocks", 24),
                config.getDouble("spawning.max-distance-blocks", 48));
        if (center == null) {
            return;
        }
        int minSize = config.getInt("spawning.hordes.min-size", 3);
        int maxSize = config.getInt("spawning.hordes.max-size", 6)
                + (int) Math.round(regionalSpawnPressure(center)
                * config.getInt("regional-infection.spawn-pressure.horde-size-at-100", 4) / 100.0);
        int size = ThreadLocalRandom.current().nextInt(minSize, maxSize + 1);
        for (int i = 0; i < size; i++) {
            Location location = i == 0 ? center : findSpawnLocation(center, 2, 8);
            if (location != null && spawn(location, chooseVariant()) == null) {
                break;
            }
        }
    }

    private double regionalSpawnPressure(Location location) {
        if (!config.getBoolean("regional-infection.enabled", true)
                || !config.getBoolean("regional-infection.spawn-pressure.enabled", true)) {
            return 0;
        }
        dev.kodari.zombieapocalypse.regional.RegionalInfectionService regionalInfection = plugin.regionalInfection();
        if (regionalInfection == null) {
            return 0;
        }
        dev.kodari.zombieapocalypse.regional.RegionalInfectionService.RegionStatus status = regionalInfection.regionAt(location);
        return status == null ? 0 : status.level();
    }

    private boolean canSpawnNear(Player player) {
        if (!player.isOnline() || player.isDead() || !config.isWorldEnabled(player.getWorld())) {
            return false;
        }
        if (config.getBoolean("spawning.night-only", true)) {
            long time = player.getWorld().getTime();
            return time >= 13000 && time <= 23000;
        }
        return true;
    }

    private Location findSpawnLocation(Location origin, double minDistance, double maxDistance) {
        if (origin.getWorld() == null) {
            return null;
        }
        for (int attempt = 0; attempt < 12; attempt++) {
            double angle = ThreadLocalRandom.current().nextDouble(0, Math.PI * 2);
            double distance = ThreadLocalRandom.current().nextDouble(minDistance, maxDistance + 1);
            int x = origin.getBlockX() + (int) Math.round(Math.cos(angle) * distance);
            int z = origin.getBlockZ() + (int) Math.round(Math.sin(angle) * distance);
            if (!origin.getWorld().isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            org.bukkit.block.Block ground = origin.getWorld().getHighestBlockAt(x, z);
            org.bukkit.block.Block feet = ground.getRelative(0, 1, 0);
            org.bukkit.block.Block head = ground.getRelative(0, 2, 0);
            if (ground.isPassable() || ground.isLiquid() || !feet.isPassable() || !head.isPassable()) {
                continue;
            }
            return new Location(origin.getWorld(), x + 0.5, feet.getY(), z + 0.5);
        }
        return null;
    }

    private ZombieVariant chooseVariant() {
        for (ZombieVariant variant : ZombieVariant.values()) {
            if (variant == ZombieVariant.NORMAL || variant == ZombieVariant.REVENANT) {
                continue;
            }
            if (ThreadLocalRandom.current().nextDouble() < config.getDouble("variants." + variant.id() + ".spawn-chance", 0)) {
                return variant;
            }
        }
        return ZombieVariant.NORMAL;
    }

    private void applyVariant(Zombie zombie, ZombieVariant variant) {
        String prefix = "variants." + variant.id() + ".";
        zombie.getPersistentDataContainer().set(variantKey, PersistentDataType.STRING, variant.id());
        zombie.setPersistent(true);
        zombie.setRemoveWhenFarAway(false);
        zombie.setCustomNameVisible(true);
        String name = config.getString(prefix + "name", "&7" + variant.id());
        zombie.setCustomName(org.bukkit.ChatColor.translateAlternateColorCodes('&', name));
        setAttribute(zombie, "max_health", config.getDouble(prefix + "health", 20));
        zombie.setHealth(Math.min(zombie.getHealth(), config.getDouble(prefix + "health", 20)));
        setAttribute(zombie, "movement_speed", config.getDouble(prefix + "speed", 0.23));
        setAttribute(zombie, "attack_damage", config.getDouble(prefix + "damage", 3));
        setAttribute(zombie, "knockback_resistance", config.getDouble(prefix + "resistance", 0));
    }

    private void setAttribute(LivingEntity entity, String name, double value) {
        XAttribute.of(name).map(XAttribute::get).map(entity::getAttribute).ifPresent(attribute -> attribute.setBaseValue(value));
    }

    public ZombieVariant getVariant(Entity entity) {
        String id = entity.getPersistentDataContainer().get(variantKey, PersistentDataType.STRING);
        return id == null ? null : ZombieVariant.fromId(id).orElse(null);
    }

    public void trackIfCustom(Entity entity) {
        if (entity instanceof Zombie && getVariant(entity) != null && !tracked.contains(entity.getUniqueId())) {
            tracked.add(entity.getUniqueId());
        }
    }

    public void onChunkUnload(org.bukkit.Chunk chunk) {
        for (Entity entity : chunk.getEntities()) {
            tracked.remove(entity.getUniqueId());
            targetMemories.remove(entity.getUniqueId());
        }
    }

    public void onEntityDeath(Entity entity) {
        tracked.remove(entity.getUniqueId());
        targetMemories.remove(entity.getUniqueId());
    }

    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (!config.getBoolean("ai.enabled", true) || !(event.getEntity() instanceof Zombie zombie)
                || getVariant(zombie) == null || !(event.getTarget() instanceof Player player)) {
            return;
        }
        alertNearby(zombie, player);
    }

    public void investigate(Player player, double radius) {
        if (!config.getBoolean("ai.enabled", true) || !config.isWorldEnabled(player.getWorld())) {
            return;
        }
        for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
            if (entity instanceof Zombie zombie && getVariant(zombie) != null && zombie.getTarget() == null
                    && zombie.hasLineOfSight(player)) {
                zombie.setTarget(player);
                rememberTarget(zombie, player);
            }
        }
    }

    private void alertNearby(Zombie source, Player target) {
        double radius = config.getDouble("ai.alert-radius", 40);
        for (Entity entity : source.getNearbyEntities(radius, radius, radius)) {
            if (entity instanceof Zombie nearby && nearby != source && getVariant(nearby) != null
                    && nearby.getTarget() == null && nearby.hasLineOfSight(target)) {
                nearby.setTarget(target);
                rememberTarget(nearby, target);
            }
        }
    }

    private void updateBatch() {
        if (!config.getBoolean("ai.enabled", true) || tracked.isEmpty()) {
            return;
        }
        int budget = config.getInt("ai.tracked-zombies-per-update", 32);
        for (int checked = 0; checked < budget && !tracked.isEmpty(); checked++) {
            if (cursor >= tracked.size()) {
                cursor = 0;
            }
            UUID id = tracked.get(cursor);
            Entity entity = Bukkit.getEntity(id);
            if (!(entity instanceof Zombie zombie) || !zombie.isValid() || getVariant(zombie) == null) {
                tracked.remove(cursor);
                continue;
            }
            updateTargetMemory(zombie);
            cursor++;
        }
    }

    private void rememberTarget(Zombie zombie, Player target) {
        targetMemories.put(zombie.getUniqueId(), new TargetMemory(target.getUniqueId(), target.getLocation().clone(),
                System.currentTimeMillis(), 0L));
    }

    private void updateTargetMemory(Zombie zombie) {
        UUID zombieId = zombie.getUniqueId();
        LivingEntity target = zombie.getTarget();
        long now = System.currentTimeMillis();
        if (target instanceof Player player) {
            if (!player.isValid() || player.isDead() || !player.getWorld().equals(zombie.getWorld())) {
                zombie.setTarget(null);
                return;
            }
            TargetMemory memory = targetMemories.get(zombieId);
            if (zombie.hasLineOfSight(player) || memory == null || !memory.targetId().equals(player.getUniqueId())) {
                targetMemories.put(zombieId, new TargetMemory(player.getUniqueId(), player.getLocation().clone(), now, 0L));
                return;
            }
            long memoryMillis = config.getLong("ai.target-memory-seconds", 20) * 1000L;
            if (now - memory.lastSeenAt() > memoryMillis) {
                zombie.setTarget(null);
                targetMemories.put(zombieId, memory.withLastSearchAt(now));
            }
            return;
        }
        if (target != null) {
            targetMemories.remove(zombieId);
            return;
        }
        TargetMemory memory = targetMemories.get(zombieId);
        if (memory == null || !memory.lastLocation().getWorld().equals(zombie.getWorld())) {
            targetMemories.remove(zombieId);
            return;
        }
        long elapsed = now - memory.lastSeenAt();
        long memoryMillis = config.getLong("ai.target-memory-seconds", 20) * 1000L;
        long searchMillis = config.getLong("ai.search-seconds", 12) * 1000L;
        if (elapsed > memoryMillis + searchMillis) {
            targetMemories.remove(zombieId);
            return;
        }
        if (elapsed < memoryMillis || now - memory.lastSearchAt() < 1000L) {
            return;
        }
        double radius = config.getDouble("ai.search-radius-blocks", 8.0);
        if (zombie.getLocation().distanceSquared(memory.lastLocation()) <= radius * radius) {
            for (Entity nearby : zombie.getNearbyEntities(radius, radius, radius)) {
                if (nearby instanceof Player player && !player.isDead() && zombie.hasLineOfSight(player)
                        && player.getLocation().distanceSquared(memory.lastLocation()) <= radius * radius) {
                    zombie.setTarget(player);
                    rememberTarget(zombie, player);
                    return;
                }
            }
        }
        targetMemories.put(zombieId, memory.withLastSearchAt(now));
    }

    public Zombie spawnRevenant(Location location) {
        if (!config.getBoolean("infection.revenant-enabled", true) || location.getWorld() == null
                || !location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)
                || !location.getBlock().isPassable() || !location.clone().add(0, 1, 0).getBlock().isPassable()) {
            if (plugin.isDebugEnabled()) {
                plugin.getLogger().info("Revenant spawn skipped: disabled, unloaded, or unsafe death location.");
            }
            return null;
        }
        return spawn(location, ZombieVariant.REVENANT);
    }

    public int trackedCount() {
        return tracked.size();
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
        }
        if (spawnTask != null) {
            spawnTask.cancel();
        }
        if (hordeTask != null) {
            hordeTask.cancel();
        }
        targetMemories.clear();
    }

    private record TargetMemory(UUID targetId, Location lastLocation, long lastSeenAt, long lastSearchAt) {
        private TargetMemory withLastSearchAt(long time) {
            return new TargetMemory(targetId, lastLocation, lastSeenAt, time);
        }
    }
}