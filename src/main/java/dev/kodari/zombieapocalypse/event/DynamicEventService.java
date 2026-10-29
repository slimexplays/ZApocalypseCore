package dev.kodari.zombieapocalypse.event;

import dev.kodari.zombieapocalypse.ZombieApocalypsePlugin;
import dev.kodari.zombieapocalypse.communication.RadioService;
import dev.kodari.zombieapocalypse.config.ConfigManager;
import dev.kodari.zombieapocalypse.device.PowerCctvService;
import dev.kodari.zombieapocalypse.infection.InfectionManager;
import dev.kodari.zombieapocalypse.item.CustomItemService;
import dev.kodari.zombieapocalypse.noise.NoiseService;
import dev.kodari.zombieapocalypse.security.SecurityService;
import dev.kodari.zombieapocalypse.zombie.ZombieService;
import dev.kodari.zombieapocalypse.zombie.ZombieVariant;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class DynamicEventService implements Listener, AutoCloseable {
    private static final List<String> EVENT_IDS = List.of("blood-moon", "blackout", "supply-drop", "horde-migration",
            "safehouse-siege", "infection-surge", "silent-night", "military-evacuation", "distress-call",
            "lab-breach", "lockdown", "boss-hunt");
    private final ZombieApocalypsePlugin plugin;
    private final ConfigManager config;
    private final ZombieService zombies;
    private final InfectionManager infections;
    private final PowerCctvService power;
    private final SecurityService security;
    private final RadioService radios;
    private final NoiseService noise;
    private final CustomItemService items;
    private final File file;
    private final YamlConfiguration state;
    private final Map<String, Long> cooldowns = new HashMap<>();
    private final Map<String, Long> originalWorldTimes = new HashMap<>();
    private String activeId;
    private long startedAt;
    private long endsAt;
    private long nextPulseAt;
    private long nextAutoStartAt;
    private String targetWorld;
    private double targetX;
    private double targetY;
    private double targetZ;
    private UUID bossId;
    private BukkitTask task;

    public DynamicEventService(ZombieApocalypsePlugin plugin, ConfigManager config, ZombieService zombies,
                               InfectionManager infections, PowerCctvService power, SecurityService security,
                               RadioService radios, NoiseService noise, CustomItemService items) {
        this.plugin = plugin;
        this.config = config;
        this.zombies = zombies;
        this.infections = infections;
        this.power = power;
        this.security = security;
        this.radios = radios;
        this.noise = noise;
        this.items = items;
        this.file = new File(plugin.getDataFolder(), "events.yml");
        this.state = YamlConfiguration.loadConfiguration(file);
        load();
    }

    public void start() {
        if (activeId != null) {
            if (endsAt <= System.currentTimeMillis()) {
                endActive("expired while the server was offline", false);
            } else if (!resumeEffects()) {
                endActive("could not resume safely", false);
            }
        }
        if (nextAutoStartAt == 0L) {
            nextAutoStartAt = System.currentTimeMillis()
                    + config.getLong("events.auto-start-interval-seconds", 3600) * 1000L;
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        save();
    }

    public List<String> eventIds() {
        return EVENT_IDS;
    }

    public String status() {
        if (activeId == null) {
            return "&eNo event is active.";
        }
        long remaining = Math.max(0L, (endsAt - System.currentTimeMillis()) / 1000L);
        return "&eActive event: &f" + activeId + " &7| &eTime remaining: &f" + remaining + "s";
    }

    public boolean startEvent(String requestedId) {
        String id = normalize(requestedId);
        if (!config.getBoolean("events.enabled", true) || !EVENT_IDS.contains(id)
                || !config.getBoolean("events.definitions." + id + ".enabled", true) || activeId != null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (cooldowns.getOrDefault(id, 0L) > now) {
            return false;
        }
        activeId = id;
        startedAt = now;
        endsAt = now + config.getLong("events.definitions." + id + ".duration-seconds", 300) * 1000L;
        nextPulseAt = now;
        bossId = null;
        originalWorldTimes.clear();
        if (!startEffects()) {
            cleanupEffects(false);
            clearActive();
            save();
            return false;
        }
        cooldowns.put(id, now + config.getLong("events.definitions." + id + ".cooldown-seconds", 1800) * 1000L);
        nextPulseAt = now + config.getLong("events.pulse-interval-seconds", 60) * 1000L;
        broadcast("&c[Event] &f" + id + " &chas begun. Duration: &f"
                + config.getLong("events.definitions." + id + ".duration-seconds", 300) + "s.");
        save();
        return true;
    }

    public boolean stopEvent() {
        if (activeId == null) {
            return false;
        }
        endActive("stopped by an administrator", true);
        return true;
    }

    private boolean startEffects() {
        switch (activeId) {
            case "blood-moon" -> {
                for (World world : Bukkit.getWorlds()) {
                    if (config.isWorldEnabled(world)) {
                        originalWorldTimes.put(world.getName(), world.getTime());
                        world.setTime(18000L);
                    }
                }
                pulse();
            }
            case "blackout" -> power.setForcedOutage(true);
            case "supply-drop" -> {
                Location target = randomPlayerLocation();
                if (target == null) {
                    return false;
                }
                setTarget(target);
                dropSupply(target);
            }
            case "horde-migration" -> {
                if (Bukkit.getOnlinePlayers().isEmpty()) {
                    return false;
                }
                pulse();
            }
            case "safehouse-siege" -> {
                PowerCctvService.Device safehouse = randomDevice("secure_door");
                Location target = deviceLocation(safehouse);
                if (target == null) {
                    return false;
                }
                setTarget(target);
                pulse();
            }
            case "infection-surge" -> {
                if (Bukkit.getOnlinePlayers().isEmpty()) {
                    return false;
                }
                pulse();
            }
            case "silent-night" -> noise.setAttractionSuppressed(true);
            case "military-evacuation" -> {
                Location target = randomPlayerLocation();
                if (target == null) {
                    return false;
                }
                setTarget(target);
                broadcast("&b[Evacuation] &fExtraction point established near " + target.getBlockX() + ", "
                        + target.getBlockY() + ", " + target.getBlockZ() + ". Reach it before time expires.");
            }
            case "distress-call" -> {
                Player sender = randomPlayer();
                if (sender == null || radios.broadcast(sender, "distress") == 0) {
                    return false;
                }
            }
            case "lab-breach" -> {
                Location target = deviceLocation(randomDevice("terminal"));
                if (target == null) {
                    return false;
                }
                setTarget(target);
                pulse();
            }
            case "lockdown" -> security.setLockdown(true);
            case "boss-hunt" -> {
                Location target = randomPlayerLocation();
                Zombie boss = spawnOne(target, ZombieVariant.BOSS);
                if (boss == null) {
                    return false;
                }
                setTarget(target);
                bossId = boss.getUniqueId();
                broadcast("&4[Boss Hunt] &cA boss has entered the area. Find and eliminate it!");
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private boolean resumeEffects() {
        switch (activeId) {
            case "blood-moon" -> {
                for (World world : Bukkit.getWorlds()) {
                    if (originalWorldTimes.containsKey(world.getName())) {
                        world.setTime(18000L);
                    }
                }
            }
            case "blackout" -> power.setForcedOutage(true);
            case "silent-night" -> noise.setAttractionSuppressed(true);
            case "lockdown" -> security.setLockdown(true);
            case "boss-hunt" -> {
                if (bossId == null || findEntity(bossId) == null) {
                    Zombie boss = spawnOne(targetLocation(), ZombieVariant.BOSS);
                    if (boss == null) {
                        return false;
                    }
                    bossId = boss.getUniqueId();
                }
            }
            default -> {
            }
        }
        return true;
    }

    private void tick() {
        long now = System.currentTimeMillis();
        if (activeId != null) {
            if (activeId.equals("blood-moon")) {
                for (World world : Bukkit.getWorlds()) {
                    if (originalWorldTimes.containsKey(world.getName())) {
                        world.setTime(18000L);
                    }
                }
            }
            if (now >= endsAt) {
                endActive("time expired", true);
            } else if (now >= nextPulseAt) {
                pulse();
                nextPulseAt = now + config.getLong("events.pulse-interval-seconds", 60) * 1000L;
                save();
            }
        } else if (config.getBoolean("events.enabled", true)
                && config.getBoolean("events.auto-start", false) && now >= nextAutoStartAt) {
            nextAutoStartAt = now + config.getLong("events.auto-start-interval-seconds", 3600) * 1000L;
            List<String> eligible = EVENT_IDS.stream()
                    .filter(id -> config.getBoolean("events.definitions." + id + ".enabled", true))
                    .filter(id -> cooldowns.getOrDefault(id, 0L) <= now)
                    .toList();
            if (!eligible.isEmpty()) {
                startEvent(eligible.get(ThreadLocalRandom.current().nextInt(eligible.size())));
            }
            save();
        }
    }

    private void pulse() {
        switch (activeId) {
            case "blood-moon", "horde-migration" -> spawnWave(randomPlayerLocation(), ZombieVariant.NORMAL);
            case "safehouse-siege" -> spawnWave(targetLocation(), ZombieVariant.NORMAL);
            case "infection-surge" -> Bukkit.getOnlinePlayers().forEach(infections::infect);
            case "lab-breach" -> {
                spawnWave(targetLocation(), ZombieVariant.TOXIC);
                Location target = targetLocation();
                if (target != null) {
                    for (Player player : target.getWorld().getPlayers()) {
                        if (player.getLocation().distanceSquared(target) <= 12 * 12) {
                            infections.infect(player);
                        }
                    }
                }
            }
            default -> {
            }
        }
    }

    private void spawnWave(Location center, ZombieVariant variant) {
        if (center == null || center.getWorld() == null) {
            return;
        }
        int size = config.getInt("events.wave-size", 3);
        for (int i = 0; i < size; i++) {
            Location spawn = safeSpawn(center);
            if (spawn != null && zombies.spawn(spawn, variant) == null) {
                break;
            }
        }
    }

    private Zombie spawnOne(Location center, ZombieVariant variant) {
        if (center == null) {
            return null;
        }
        Location spawn = safeSpawn(center);
        return spawn == null ? null : zombies.spawn(spawn, variant);
    }

    private Location safeSpawn(Location center) {
        World world = center.getWorld();
        if (world == null) {
            return null;
        }
        for (int attempt = 0; attempt < 32; attempt++) {
            double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
            int radius = ThreadLocalRandom.current().nextInt(8, 17);
            int x = center.getBlockX() + (int) Math.round(Math.cos(angle) * radius);
            int z = center.getBlockZ() + (int) Math.round(Math.sin(angle) * radius);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            int y = world.getHighestBlockYAt(x, z) + 1;
            if (y < world.getMinHeight() || y + 1 >= world.getMaxHeight()) {
                continue;
            }
            Block floor = world.getBlockAt(x, y - 1, z);
            Block feet = world.getBlockAt(x, y, z);
            Block head = world.getBlockAt(x, y + 1, z);
            Location location = new Location(world, x + 0.5, y, z + 0.5);
            if (floor.getType().isSolid() && feet.isPassable() && head.isPassable()
                    && world.getWorldBorder().isInside(location)) {
                return location;
            }
        }
        return null;
    }

    private void dropSupply(Location location) {
        List<String> rewards = config.getStringList("events.supply-drop.items");
        int drops = 0;
        for (String itemId : rewards) {
            ItemStack item = items.create(itemId);
            if (item != null) {
                item.setAmount(1);
                location.getWorld().dropItemNaturally(location.clone().add(0.5, 1, 0.5), item);
                drops++;
            }
        }
        broadcast("&6[Supply Drop] &e" + drops + " configured supply item(s) landed near "
                + location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ() + ".");
    }

    private void rewardNearby(Location center, String itemId, int amount, double radius) {
        if (center == null || center.getWorld() == null) {
            return;
        }
        for (Player player : center.getWorld().getPlayers()) {
            if (!player.isDead() && player.getLocation().distanceSquared(center) <= radius * radius) {
                giveReward(player, itemId, amount);
            }
        }
    }

    private void giveReward(Player player, String itemId, int amount) {
        ItemStack reward = items.create(itemId);
        if (reward == null) {
            plugin.getLogger().warning("Dynamic event reward item is not configured: " + itemId);
            return;
        }
        reward.setAmount(Math.max(1, Math.min(amount, reward.getMaxStackSize())));
        player.getInventory().addItem(reward).values()
                .forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
    }

    private Location randomPlayerLocation() {
        Player player = randomPlayer();
        return player == null ? null : player.getLocation().clone();
    }

    private Player randomPlayer() {
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        return players.isEmpty() ? null : players.get(ThreadLocalRandom.current().nextInt(players.size()));
    }

    private PowerCctvService.Device randomDevice(String type) {
        List<PowerCctvService.Device> matches = power.devices().stream()
                .filter(device -> device.type().equals(type)).toList();
        return matches.isEmpty() ? null : matches.get(ThreadLocalRandom.current().nextInt(matches.size()));
    }

    private Location deviceLocation(PowerCctvService.Device device) {
        if (device == null) {
            return null;
        }
        World world = Bukkit.getWorld(device.world());
        if (world == null || !world.isChunkLoaded(device.x() >> 4, device.z() >> 4)) {
            return null;
        }
        return new Location(world, device.x() + 0.5, device.y() + 1, device.z() + 0.5);
    }

    private void setTarget(Location location) {
        targetWorld = location.getWorld().getName();
        targetX = location.getX();
        targetY = location.getY();
        targetZ = location.getZ();
    }

    private Location targetLocation() {
        World world = targetWorld == null ? null : Bukkit.getWorld(targetWorld);
        if (world == null || !world.isChunkLoaded(((int) targetX) >> 4, ((int) targetZ) >> 4)) {
            return null;
        }
        return new Location(world, targetX, targetY, targetZ);
    }

    private Entity findEntity(UUID id) {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity.getUniqueId().equals(id)) {
                    return entity;
                }
            }
        }
        return null;
    }

    @EventHandler
    public void onBossDeath(EntityDeathEvent event) {
        if (!"boss-hunt".equals(activeId) || bossId == null || !event.getEntity().getUniqueId().equals(bossId)) {
            return;
        }
        Player killer = event.getEntity().getKiller();
        if (killer != null) {
            giveReward(killer, config.getString("events.boss-hunt.reward-item", "medkit"),
                    config.getInt("events.boss-hunt.reward-amount", 1));
        }
        endActive("the boss was defeated", false);
    }

    private void endActive(String reason, boolean announce) {
        if (activeId == null) {
            return;
        }
        String endedId = activeId;
        if (endedId.equals("military-evacuation")) {
            rewardNearby(targetLocation(), config.getString("events.military-evacuation.reward-item", "ration_pack"),
                    config.getInt("events.military-evacuation.reward-amount", 1),
                    config.getDouble("events.military-evacuation.extraction-radius-blocks", 16));
        }
        cleanupEffects(true);
        clearActive();
        if (announce) {
            broadcast("&7[Event] &f" + endedId + " &7ended: " + reason + ".");
        }
        save();
    }

    private void cleanupEffects(boolean removeBoss) {
        if ("blackout".equals(activeId)) {
            power.setForcedOutage(false);
        } else if ("silent-night".equals(activeId)) {
            noise.setAttractionSuppressed(false);
        } else if ("lockdown".equals(activeId)) {
            security.setLockdown(false);
        } else if ("blood-moon".equals(activeId)) {
            originalWorldTimes.forEach((worldName, time) -> {
                World world = Bukkit.getWorld(worldName);
                if (world != null) {
                    world.setTime(time);
                }
            });
        }
        if (removeBoss && bossId != null) {
            Entity boss = findEntity(bossId);
            if (boss != null && boss.isValid()) {
                boss.remove();
            }
        }
    }

    private void clearActive() {
        activeId = null;
        startedAt = 0L;
        endsAt = 0L;
        nextPulseAt = 0L;
        targetWorld = null;
        targetX = 0;
        targetY = 0;
        targetZ = 0;
        bossId = null;
        originalWorldTimes.clear();
    }

    private void load() {
        ConfigurationSection cooldownSection = state.getConfigurationSection("cooldowns");
        if (cooldownSection != null) {
            for (String id : cooldownSection.getKeys(false)) {
                cooldowns.put(id, cooldownSection.getLong(id));
            }
        }
        nextAutoStartAt = state.getLong("next-auto-start-at", 0L);
        ConfigurationSection active = state.getConfigurationSection("active");
        if (active == null) {
            return;
        }
        String id = normalize(active.getString("id", ""));
        if (!EVENT_IDS.contains(id)) {
            return;
        }
        activeId = id;
        startedAt = active.getLong("started-at", 0L);
        endsAt = active.getLong("ends-at", 0L);
        nextPulseAt = active.getLong("next-pulse-at", 0L);
        targetWorld = active.getString("target-world");
        targetX = active.getDouble("target-x");
        targetY = active.getDouble("target-y");
        targetZ = active.getDouble("target-z");
        try {
            String savedBoss = active.getString("boss-id");
            bossId = savedBoss == null ? null : UUID.fromString(savedBoss);
        } catch (IllegalArgumentException ignored) {
            bossId = null;
        }
        ConfigurationSection times = active.getConfigurationSection("original-world-times");
        if (times != null) {
            for (String world : times.getKeys(false)) {
                originalWorldTimes.put(world, times.getLong(world));
            }
        }
    }

    private void save() {
        state.set("active", null);
        state.set("cooldowns", null);
        cooldowns.forEach((id, time) -> state.set("cooldowns." + id, time));
        state.set("next-auto-start-at", nextAutoStartAt);
        if (activeId != null) {
            String path = "active.";
            state.set(path + "id", activeId);
            state.set(path + "started-at", startedAt);
            state.set(path + "ends-at", endsAt);
            state.set(path + "next-pulse-at", nextPulseAt);
            state.set(path + "target-world", targetWorld);
            state.set(path + "target-x", targetX);
            state.set(path + "target-y", targetY);
            state.set(path + "target-z", targetZ);
            state.set(path + "boss-id", bossId == null ? null : bossId.toString());
            originalWorldTimes.forEach((world, time) -> state.set(path + "original-world-times." + world, time));
        }
        try {
            file.getParentFile().mkdirs();
            state.save(file);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not save dynamic event state: " + exception.getMessage());
        }
    }

    private String normalize(String id) {
        return id == null ? "" : id.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private void broadcast(String text) {
        String message = ChatColor.translateAlternateColorCodes('&', text);
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(message);
        }
        plugin.getLogger().info(ChatColor.stripColor(message));
    }

    @Override
    public void close() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        save();
    }
}