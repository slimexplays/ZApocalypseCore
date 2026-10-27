package dev.kodari.zombieapocalypse.device;

import com.cryptomorin.xseries.XMaterial;
import dev.kodari.zombieapocalypse.ZombieApocalypsePlugin;
import dev.kodari.zombieapocalypse.config.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PowerCctvService implements Listener, AutoCloseable {
    private final ZombieApocalypsePlugin plugin;
    private final ConfigManager config;
    private final File dataFile;
    private final Map<String, Device> devices = new LinkedHashMap<>();
    private final Map<String, String> deviceAtBlock = new HashMap<>();
    private final Map<UUID, ViewingSession> viewingSessions = new HashMap<>();
    private final Map<UUID, ArmorStand> cameraEntities = new HashMap<>();
    private final BukkitTask powerTask;
    private long lastFuelSave = System.currentTimeMillis();

    public PowerCctvService(ZombieApocalypsePlugin plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
        this.dataFile = new File(plugin.getDataFolder(), "devices.yml");
        load();
        this.powerTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updatePower, 20L, 20L);
    }

    public List<Device> devices() {
        return List.copyOf(devices.values());
    }

    public Device find(String id) {
        return devices.get(id.toLowerCase(Locale.ROOT));
    }

    public boolean place(String type, String id, Location location, float yaw, float pitch) {
        if (!config.getBoolean("devices.enabled", true) || !List.of("camera", "generator").contains(type)
                || !id.matches("[a-z0-9_-]{1,32}") || devices.containsKey(id)
                || devices.size() >= config.getInt("devices.max-devices", 512)
                || deviceAtBlock.containsKey(blockKey(location))) {
            return false;
        }
        Device device = new Device(id, type, location.getWorld().getName(), location.getBlockX(), location.getBlockY(),
                location.getBlockZ(), yaw, pitch, 0L);
        devices.put(id, device);
        deviceAtBlock.put(blockKey(device), id);
        save();
        return true;
    }

    public boolean remove(String id) {
        Device device = devices.remove(id.toLowerCase(Locale.ROOT));
        if (device == null) {
            return false;
        }
        deviceAtBlock.remove(blockKey(device));
        save();
        return true;
    }

    public boolean isPowered(Device camera) {
        return camera != null && camera.type().equals("camera") && powerSource(camera) != null;
    }

    public boolean view(Player player, String id) {
        Device camera = find(id);
        if (camera == null || !camera.type().equals("camera")) {
            message(player, "&cUnknown CCTV camera.");
            return false;
        }
        if (!isPowered(camera)) {
            message(player, "&cThat camera has no power.");
            return false;
        }
        World world = Bukkit.getWorld(camera.world());
        if (world == null) {
            message(player, "&cThe camera's world is not loaded.");
            return false;
        }
        stopViewing(player, true);
        Location returnLocation = player.getLocation().clone();
        GameMode returnGameMode = player.getGameMode();
        Entity returnTarget = player.getSpectatorTarget();
        Location viewLocation = new Location(world, camera.x() + 0.5, camera.y() + 1.2, camera.z() + 0.5,
                camera.yaw(), camera.pitch());
        ArmorStand cameraEntity = world.spawn(viewLocation, ArmorStand.class);
        cameraEntity.setInvisible(true);
        cameraEntity.setMarker(true);
        cameraEntity.setGravity(false);
        cameraEntity.setInvulnerable(true);
        cameraEntity.setSilent(true);
        cameraEntity.setPersistent(false);
        viewingSessions.put(player.getUniqueId(), new ViewingSession(returnLocation, returnGameMode, returnTarget));
        cameraEntities.put(player.getUniqueId(), cameraEntity);
        player.setGameMode(GameMode.SPECTATOR);
        player.setSpectatorTarget(cameraEntity);
        message(player, "&aViewing CCTV &f" + camera.id() + "&a. Use &f/zombie cctv exit &ato return.");
        return true;
    }

    public boolean stopViewing(Player player) {
        return stopViewing(player, true);
    }

    private boolean stopViewing(Player player, boolean restorePlayer) {
        UUID playerId = player.getUniqueId();
        ViewingSession session = viewingSessions.remove(playerId);
        ArmorStand entity = cameraEntities.remove(playerId);
        if (entity != null && entity.isValid()) {
            entity.remove();
        }
        if (session == null) {
            return false;
        }
        if (restorePlayer) {
            player.setGameMode(session.gameMode());
            player.teleport(session.location());
            if (session.gameMode() == GameMode.SPECTATOR && session.spectatorTarget() != null
                    && session.spectatorTarget().isValid()) {
                player.setSpectatorTarget(session.spectatorTarget());
            }
            message(player, "&aCCTV view closed.");
        }
        return true;
    }

    private boolean handleInteract(Player player, Block block) {
        String id = deviceAtBlock.get(blockKey(block.getLocation()));
        Device device = id == null ? null : devices.get(id);
        if (device == null) {
            return false;
        }
        if (device.type().equals("camera")) {
            view(player, device.id());
            return true;
        }
        if (!consumeCoal(player)) {
            message(player, "&eHold coal or charcoal to refuel generator &f" + device.id() + "&e.");
            return true;
        }
        long addedFuel = config.getInt("devices.power.fuel-ticks-per-coal", 1200);
        Device fueled = device.withFuelTicks(Math.min(Long.MAX_VALUE - addedFuel, device.fuelTicks()) + addedFuel);
        devices.put(device.id(), fueled);
        save();
        lastFuelSave = System.currentTimeMillis();
        message(player, "&aRefueled &f" + device.id() + "&a. Stored fuel: &f" + (fueled.fuelTicks() / 20) + "s&a.");
        return true;
    }

    private boolean consumeCoal(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack held = inventory.getItemInMainHand();
        if (held == null || held.getType().isAir()) {
            return false;
        }
        Material coal = XMaterial.matchXMaterial("COAL").map(XMaterial::parseMaterial).orElse(null);
        Material charcoal = XMaterial.matchXMaterial("CHARCOAL").map(XMaterial::parseMaterial).orElse(null);
        if (!held.getType().equals(coal) && !held.getType().equals(charcoal)) {
            return false;
        }
        if (held.getAmount() <= 1) {
            inventory.setItemInMainHand(null);
        } else {
            held.setAmount(held.getAmount() - 1);
        }
        return true;
    }

    private void updatePower() {
        if (!config.getBoolean("devices.enabled", true)) {
            return;
        }
        Set<String> loadedGenerators = new HashSet<>();
        for (Device camera : devices.values()) {
            if (camera.type().equals("camera")) {
                Device source = powerSource(camera);
                if (source != null) {
                    loadedGenerators.add(source.id());
                }
            }
        }
        boolean fuelChanged = false;
        for (Device generator : devices()) {
            if (generator.type().equals("generator") && generator.fuelTicks() > 0
                    && loadedGenerators.contains(generator.id())) {
                devices.put(generator.id(), generator.withFuelTicks(Math.max(0L, generator.fuelTicks() - 20L)));
                fuelChanged = true;
            }
        }
        long now = System.currentTimeMillis();
        if (fuelChanged && now - lastFuelSave >= 60_000L) {
            save();
            lastFuelSave = now;
        }
    }

    private Device powerSource(Device camera) {
        if (!config.getBoolean("devices.enabled", true)) {
            return null;
        }
        double radiusSquared = Math.pow(config.getDouble("devices.power.radius-blocks", 32.0), 2);
        for (Device generator : devices.values()) {
            if (generator.type().equals("generator") && generator.fuelTicks() > 0
                    && generator.world().equalsIgnoreCase(camera.world())) {
                double dx = generator.x() - camera.x();
                double dy = generator.y() - camera.y();
                double dz = generator.z() - camera.z();
                if (dx * dx + dy * dy + dz * dz <= radiusSquared) {
                    return generator;
                }
            }
        }
        return null;
    }

    private void load() {
        if (!dataFile.exists()) {
            return;
        }
        YamlConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection entries = data.getConfigurationSection("devices");
        if (entries == null) {
            return;
        }
        int limit = config.getInt("devices.max-devices", 512);
        for (String id : entries.getKeys(false)) {
            ConfigurationSection entry = entries.getConfigurationSection(id);
            if (entry == null || devices.size() >= limit) {
                continue;
            }
            String type = entry.getString("type", "").toLowerCase(Locale.ROOT);
            String world = entry.getString("world", "");
            if (!id.matches("[a-z0-9_-]{1,32}") || !List.of("camera", "generator").contains(type) || world.isBlank()) {
                plugin.getLogger().warning("Ignoring invalid persisted device '" + id + "'.");
                continue;
            }
            Device device = new Device(id, type, world, entry.getInt("x"), entry.getInt("y"), entry.getInt("z"),
                    (float) entry.getDouble("yaw"), (float) entry.getDouble("pitch"), Math.max(0L, entry.getLong("fuel-ticks")));
            String blockKey = blockKey(device);
            if (deviceAtBlock.containsKey(blockKey)) {
                plugin.getLogger().warning("Ignoring duplicate persisted device location for '" + id + "'.");
                continue;
            }
            devices.put(id, device);
            deviceAtBlock.put(blockKey, id);
        }
    }

    private void save() {
        YamlConfiguration data = new YamlConfiguration();
        data.set("schema", 1);
        for (Device device : devices.values()) {
            String path = "devices." + device.id() + ".";
            data.set(path + "type", device.type());
            data.set(path + "world", device.world());
            data.set(path + "x", device.x());
            data.set(path + "y", device.y());
            data.set(path + "z", device.z());
            data.set(path + "yaw", device.yaw());
            data.set(path + "pitch", device.pitch());
            data.set(path + "fuel-ticks", device.fuelTicks());
        }
        try {
            dataFile.getParentFile().mkdirs();
            data.save(dataFile);
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not save CCTV/power devices: " + exception.getMessage());
        }
    }

    private String blockKey(Device device) {
        return device.world().toLowerCase(Locale.ROOT) + ":" + device.x() + ":" + device.y() + ":" + device.z();
    }

    private String blockKey(Location location) {
        return location.getWorld().getName().toLowerCase(Locale.ROOT) + ":" + location.getBlockX() + ":"
                + location.getBlockY() + ":" + location.getBlockZ();
    }

    private void message(Player player, String text) {
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', text));
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null
                && handleInteract(event.getPlayer(), event.getClickedBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        String id = deviceAtBlock.get(blockKey(event.getBlock().getLocation()));
        if (id != null && remove(id)) {
            event.getPlayer().sendMessage(ChatColor.YELLOW + "Removed device " + id + ".");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        stopViewing(event.getPlayer(), false);
    }

    @Override
    public void close() {
        powerTask.cancel();
        for (UUID playerId : new ArrayList<>(viewingSessions.keySet())) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null) {
                ArmorStand entity = cameraEntities.remove(playerId);
                if (entity != null && entity.isValid()) {
                    entity.remove();
                }
                viewingSessions.remove(playerId);
            } else {
                stopViewing(player, true);
            }
        }
        save();
    }

    public record Device(String id, String type, String world, int x, int y, int z, float yaw, float pitch, long fuelTicks) {
        public Device withFuelTicks(long newFuelTicks) {
            return new Device(id, type, world, x, y, z, yaw, pitch, newFuelTicks);
        }
    }

    private record ViewingSession(Location location, GameMode gameMode, Entity spectatorTarget) {
    }
}