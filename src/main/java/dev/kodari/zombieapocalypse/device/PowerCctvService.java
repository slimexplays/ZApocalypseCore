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
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PowerCctvService implements Listener, AutoCloseable {
    private static final DateTimeFormatter MOTION_TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private final ZombieApocalypsePlugin plugin;
    private final ConfigManager config;
    private final File dataFile;
    private final Map<String, Device> devices = new LinkedHashMap<>();
    private final Map<String, String> deviceAtBlock = new HashMap<>();
    private final Map<String, String> poweredBy = new HashMap<>();
    private final Set<String> disabledBreakers = new HashSet<>();
    private final Map<String, List<String>> motionLogs = new HashMap<>();
    private final Map<String, Map<UUID, Location>> cameraPositions = new HashMap<>();
    private final Map<UUID, ViewingSession> viewingSessions = new HashMap<>();
    private final Map<UUID, ArmorStand> cameraEntities = new HashMap<>();
    private final BukkitTask powerTask;
    private boolean forcedOutage;
    private long lastFuelSave = System.currentTimeMillis();

    public PowerCctvService(ZombieApocalypsePlugin plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
        this.dataFile = new File(plugin.getDataFolder(), "devices.yml");
        load();
        updatePower();
        this.powerTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updatePower, 20L, 20L);
    }

    public List<Device> devices() {
        return List.copyOf(devices.values());
    }

    public Device find(String id) {
        return devices.get(id.toLowerCase(Locale.ROOT));
    }

    public boolean place(String type, String id, Location location, float yaw, float pitch) {
        if (!config.getBoolean("devices.enabled", true)
                || !List.of("camera", "generator", "battery", "cable", "connector", "breaker", "charger", "monitor", "terminal", "laptop", "secure_door").contains(type)
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
        updatePower();
        return true;
    }

    public boolean remove(String id) {
        Device device = devices.remove(id.toLowerCase(Locale.ROOT));
        if (device == null) {
            return false;
        }
        deviceAtBlock.remove(blockKey(device));
        disabledBreakers.remove(device.id());
        poweredBy.remove(device.id());
        motionLogs.remove(device.id());
        cameraPositions.remove(device.id());
        save();
        updatePower();
        return true;
    }

    public boolean isPowered(Device camera) {
        return camera != null && isConsumer(camera) && poweredBy.containsKey(camera.id());
    }

    public boolean isBreakerEnabled(Device breaker) {
        return breaker != null && breaker.type().equals("breaker") && !disabledBreakers.contains(breaker.id());
    }

    public void setForcedOutage(boolean forcedOutage) {
        this.forcedOutage = forcedOutage;
        updatePower();
    }

    public boolean view(Player player, String id) {
        if (!player.hasPermission("zombie.cctv")) {
            message(player, "&cYou do not have permission to use CCTV.");
            return false;
        }
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
        viewingSessions.put(player.getUniqueId(), new ViewingSession(returnLocation, returnGameMode, returnTarget, camera.id()));
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
        if (device.type().equals("secure_door")) {
            return false;
        }
        if (device.type().equals("breaker")) {
            if (!player.hasPermission("zombie.power.manage")) {
                message(player, "&cYou do not have permission to operate this breaker.");
                return true;
            }
            if (!disabledBreakers.add(device.id())) {
                disabledBreakers.remove(device.id());
                message(player, "&aBreaker &f" + device.id() + " &aclosed; power can flow.");
            } else {
                message(player, "&eBreaker &f" + device.id() + " &eopened; downstream devices lose power.");
            }
            save();
            updatePower();
            return true;
        }
        if (device.type().equals("battery")) {
            message(player, "&bBattery &f" + device.id() + "&b: " + device.fuelTicks() / 20 + "s stored.");
            return true;
        }
        if (List.of("monitor", "terminal", "laptop").contains(device.type())) {
            openMonitor(player, device);
            return true;
        }
        if (!device.type().equals("generator")) {
            message(player, device.type().equals("charger")
                    ? (isPowered(device) ? "&aCharging station is powered." : "&cCharging station has no power.")
                    : "&7Cable connector registered.");
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
        updatePower();
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
        poweredBy.clear();
        if (!config.getBoolean("devices.enabled", true) || forcedOutage) {
            for (UUID playerId : new ArrayList<>(viewingSessions.keySet())) {
                Player player = Bukkit.getPlayer(playerId);
                if (player != null) {
                    stopViewing(player, true);
                }
            }
            return;
        }
        Map<String, Integer> components = wireComponents();
        List<Device> sources = devices.values().stream()
                .filter(device -> (device.type().equals("generator") || device.type().equals("battery"))
                        && device.fuelTicks() > 0)
                .sorted(Comparator.comparing(Device::id)).toList();
        List<Device> consumers = devices.values().stream().filter(this::isConsumer)
                .sorted(Comparator.comparing(Device::id)).toList();
        Map<String, Integer> sourceLoads = new HashMap<>();
        for (Device consumer : consumers) {
            int load = Math.max(1, config.getInt("devices.power.consumer-loads." + consumer.type(), 1));
            for (Device source : sources) {
                int capacity = source.type().equals("generator")
                        ? config.getInt("devices.power.generator-output-units", 4)
                        : config.getInt("devices.power.battery-output-units", 2);
                int used = sourceLoads.getOrDefault(source.id(), 0);
                if (used + load <= capacity && connected(source, consumer, components)) {
                    poweredBy.put(consumer.id(), source.id());
                    sourceLoads.put(source.id(), used + load);
                    break;
                }
            }
        }
        Map<String, String> chargingBatteries = new HashMap<>();
        int generatorCapacity = config.getInt("devices.power.generator-output-units", 4);
        int batteryChargeLoad = Math.max(1, config.getInt("devices.power.battery-charge-load", 1));
        long batteryCapacity = (long) config.getInt("devices.power.battery-capacity-seconds", 3600) * 20L;
        for (Device battery : devices.values()) {
            if (!battery.type().equals("battery") || battery.fuelTicks() >= batteryCapacity
                    || sourceLoads.containsKey(battery.id())) {
                continue;
            }
            for (Device generator : sources) {
                if (!generator.type().equals("generator") || !connected(generator, battery, components)) {
                    continue;
                }
                int used = sourceLoads.getOrDefault(generator.id(), 0);
                if (used + batteryChargeLoad <= generatorCapacity) {
                    chargingBatteries.put(battery.id(), generator.id());
                    sourceLoads.put(generator.id(), used + batteryChargeLoad);
                    break;
                }
            }
        }
        boolean fuelChanged = false;
        int baseDrain = config.getInt("devices.power.fuel-ticks-per-second", 20);
        for (Device source : sources) {
            int load = sourceLoads.getOrDefault(source.id(), 0);
            if (load > 0) {
                long drain = Math.max(1L, (long) baseDrain * load);
                devices.put(source.id(), source.withFuelTicks(Math.max(0L, source.fuelTicks() - drain)));
                fuelChanged = true;
            }
        }
        int chargeTicks = config.getInt("devices.power.battery-charge-ticks-per-second", 20);
        for (String batteryId : chargingBatteries.keySet()) {
            Device battery = devices.get(batteryId);
            if (battery != null) {
                devices.put(batteryId, battery.withFuelTicks(Math.min(batteryCapacity, battery.fuelTicks() + chargeTicks)));
                fuelChanged = true;
            }
        }
        chargePhones();
        boolean motionChanged = recordMotion();
        for (Map.Entry<UUID, ViewingSession> entry : new ArrayList<>(viewingSessions.entrySet())) {
            if (!poweredBy.containsKey(entry.getValue().cameraId())) {
                Player viewer = Bukkit.getPlayer(entry.getKey());
                if (viewer != null) {
                    stopViewing(viewer, true);
                }
            }
        }
        long now = System.currentTimeMillis();
        if ((fuelChanged || motionChanged) && now - lastFuelSave >= 60_000L) {
            save();
            lastFuelSave = now;
        }
    }

    private boolean isConsumer(Device device) {
        return List.of("camera", "charger", "monitor", "terminal", "laptop", "secure_door").contains(device.type());
    }

    private boolean connected(Device source, Device consumer, Map<String, Integer> components) {
        Integer sourceComponent = components.get(source.id());
        Integer consumerComponent = components.get(consumer.id());
        if (sourceComponent != null || consumerComponent != null) {
            return sourceComponent != null && sourceComponent.equals(consumerComponent);
        }
        double radiusSquared = Math.pow(config.getDouble("devices.power.radius-blocks", 32.0), 2);
        if (!source.world().equalsIgnoreCase(consumer.world())) {
            return false;
        }
        double dx = source.x() - consumer.x();
        double dy = source.y() - consumer.y();
        double dz = source.z() - consumer.z();
        return dx * dx + dy * dy + dz * dz <= radiusSquared;
    }

    private Map<String, Integer> wireComponents() {
        Set<String> conductors = new HashSet<>();
        for (Device device : devices.values()) {
            if (device.type().equals("cable") || device.type().equals("connector")
                    || (device.type().equals("breaker") && !disabledBreakers.contains(device.id()))) {
                conductors.add(device.id());
            }
        }
        Map<String, Integer> components = new HashMap<>();
        int component = 0;
        for (String start : conductors) {
            if (components.containsKey(start)) {
                continue;
            }
            Deque<String> queue = new ArrayDeque<>();
            queue.add(start);
            components.put(start, component);
            while (!queue.isEmpty()) {
                Device current = devices.get(queue.removeFirst());
                for (String neighbor : adjacentDevices(current)) {
                    if (conductors.contains(neighbor) && !components.containsKey(neighbor)) {
                        components.put(neighbor, component);
                        queue.addLast(neighbor);
                    }
                }
            }
            component++;
        }
        for (Device device : devices.values()) {
            if (conductors.contains(device.id())) {
                continue;
            }
            for (String neighbor : adjacentDevices(device)) {
                Integer connectedComponent = components.get(neighbor);
                if (connectedComponent != null) {
                    components.put(device.id(), connectedComponent);
                    break;
                }
            }
        }
        return components;
    }

    private List<String> adjacentDevices(Device device) {
        List<String> adjacent = new ArrayList<>(6);
        int[][] directions = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] direction : directions) {
            String id = deviceAtBlock.get(blockKey(device.world(), device.x() + direction[0],
                    device.y() + direction[1], device.z() + direction[2]));
            if (id != null) {
                adjacent.add(id);
            }
        }
        return adjacent;
    }

    private void chargePhones() {
        int radius = config.getInt("devices.power.charger-radius-blocks", 6);
        int radiusSquared = radius * radius;
        int charge = config.getInt("devices.power.phone-charge-percent-per-second", 1);
        Set<UUID> charged = new HashSet<>();
        for (Device charger : devices.values()) {
            if (!charger.type().equals("charger") || !poweredBy.containsKey(charger.id())) {
                continue;
            }
            World world = Bukkit.getWorld(charger.world());
            if (world == null) {
                continue;
            }
            Location station = new Location(world, charger.x() + 0.5, charger.y() + 0.5, charger.z() + 0.5);
            for (Player player : world.getPlayers()) {
                if (player.getLocation().distanceSquared(station) <= radiusSquared && charged.add(player.getUniqueId())) {
                    plugin.phones().chargeOnline(player, charge);
                }
            }
        }
    }

    private void openMonitor(Player player, Device monitor) {
        if (!player.hasPermission("zombie.cctv")) {
            message(player, "&cYou do not have permission to use CCTV.");
            return;
        }
        if (!isPowered(monitor)) {
            message(player, "&cThis monitor has no power.");
            return;
        }
        MonitorHolder holder = new MonitorHolder(player.getUniqueId(), monitor.id());
        Inventory inventory = Bukkit.createInventory(holder, 54, ChatColor.DARK_GRAY + "CCTV Monitor");
        holder.inventory = inventory;
        int slot = 0;
        for (Device camera : devices.values().stream().filter(device -> device.type().equals("camera"))
                .sorted(Comparator.comparing(Device::id)).toList()) {
            if (slot >= 45) {
                break;
            }
            ItemStack card = XMaterial.matchXMaterial("PAPER").map(XMaterial::parseItem).orElse(null);
            if (card == null) {
                continue;
            }
            ItemMeta meta = card.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.translateAlternateColorCodes('&',
                        (isPowered(camera) ? "&a" : "&c") + camera.id()));
                meta.setLore(List.of(isPowered(camera) ? "§7Status: §aONLINE" : "§7Status: §cOFFLINE",
                        "§7Motion records: §f" + motionLogs.getOrDefault(camera.id(), List.of()).size(),
                        isPowered(camera) ? "§eClick to view live camera." : "§cCamera requires power."));
                card.setItemMeta(meta);
            }
            inventory.setItem(slot, card);
            holder.cameraSlots.put(slot, camera.id());
            slot++;
        }
        ItemStack close = XMaterial.matchXMaterial("BARRIER").map(XMaterial::parseItem).orElse(null);
        if (close != null) {
            inventory.setItem(49, close);
        }
        player.openInventory(inventory);
    }

    public List<String> motionLog(String cameraId) {
        Device camera = find(cameraId);
        if (camera == null || !camera.type().equals("camera")) {
            return List.of();
        }
        List<String> formatted = new ArrayList<>();
        for (String entry : motionLogs.getOrDefault(camera.id(), List.of())) {
            String[] parts = entry.split("\\|", 2);
            try {
                formatted.add(MOTION_TIME.format(Instant.ofEpochMilli(Long.parseLong(parts[0])))
                        + " - " + (parts.length > 1 ? parts[1] : "unknown"));
            } catch (NumberFormatException exception) {
                formatted.add(entry);
            }
        }
        return List.copyOf(formatted);
    }

    private boolean recordMotion() {
        double radius = config.getDouble("devices.cctv.motion-radius-blocks", 16.0);
        double radiusSquared = radius * radius;
        Set<String> activeCameras = new HashSet<>();
        boolean changed = false;
        for (Device camera : devices.values()) {
            if (!camera.type().equals("camera") || !isPowered(camera)) {
                continue;
            }
            World world = Bukkit.getWorld(camera.world());
            if (world == null) {
                continue;
            }
            activeCameras.add(camera.id());
            Map<UUID, Location> previousPositions = cameraPositions.computeIfAbsent(camera.id(), ignored -> new HashMap<>());
            Set<UUID> nearbyPlayers = new HashSet<>();
            Location cameraLocation = new Location(world, camera.x() + 0.5, camera.y() + 0.5, camera.z() + 0.5);
            for (Player player : world.getPlayers()) {
                if (player.getLocation().distanceSquared(cameraLocation) > radiusSquared) {
                    continue;
                }
                UUID playerId = player.getUniqueId();
                nearbyPlayers.add(playerId);
                Location current = player.getLocation();
                Location previous = previousPositions.put(playerId, current.clone());
                if (previous != null && previous.distanceSquared(current) >= 0.25) {
                    List<String> log = motionLogs.computeIfAbsent(camera.id(), ignored -> new ArrayList<>());
                    log.add(System.currentTimeMillis() + "|" + player.getName());
                    while (log.size() > 50) {
                        log.remove(0);
                    }
                    changed = true;
                }
            }
            previousPositions.keySet().retainAll(nearbyPlayers);
        }
        cameraPositions.keySet().retainAll(activeCameras);
        return changed;
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
            if (!id.matches("[a-z0-9_-]{1,32}")
                    || !List.of("camera", "generator", "battery", "cable", "connector", "breaker", "charger", "monitor", "terminal", "laptop", "secure_door").contains(type)
                    || world.isBlank()) {
                plugin.getLogger().warning("Ignoring invalid persisted device '" + id + "'.");
                continue;
            }
            Device device = new Device(id, type, world, entry.getInt("x"), entry.getInt("y"), entry.getInt("z"),
                    (float) entry.getDouble("yaw"), (float) entry.getDouble("pitch"), Math.max(0L, entry.getLong("fuel-ticks")));
            if (type.equals("breaker") && !entry.getBoolean("enabled", true)) {
                disabledBreakers.add(id);
            }
            String blockKey = blockKey(device);
            if (deviceAtBlock.containsKey(blockKey)) {
                plugin.getLogger().warning("Ignoring duplicate persisted device location for '" + id + "'.");
                continue;
            }
            devices.put(id, device);
            deviceAtBlock.put(blockKey, id);
            if (type.equals("camera")) {
                motionLogs.put(id, new ArrayList<>(entry.getStringList("motion-log")));
            }
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
            if (device.type().equals("camera")) {
                data.set(path + "motion-log", motionLogs.getOrDefault(device.id(), List.of()));
            }
            if (device.type().equals("breaker")) {
                data.set(path + "enabled", !disabledBreakers.contains(device.id()));
            }
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

    private String blockKey(String world, int x, int y, int z) {
        return world.toLowerCase(Locale.ROOT) + ":" + x + ":" + y + ":" + z;
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

    @EventHandler
    public void onMonitorClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MonitorHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.owner.equals(player.getUniqueId())) {
            return;
        }
        if (event.getRawSlot() == 49) {
            player.closeInventory();
            return;
        }
        if (!player.hasPermission("zombie.cctv")) {
            message(player, "&cYou do not have permission to use CCTV.");
            return;
        }
        Device monitor = find(holder.monitorId);
        if (monitor == null || !isPowered(monitor)) {
            message(player, "&cThis monitor has lost power.");
            player.closeInventory();
            return;
        }
        String cameraId = holder.cameraSlots.get(event.getRawSlot());
        if (cameraId != null) {
            view(player, cameraId);
        }
    }

    @EventHandler
    public void onMonitorDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MonitorHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        String id = deviceAtBlock.get(blockKey(event.getBlock().getLocation()));
        if (id == null) {
            return;
        }
        if (!event.getPlayer().hasPermission("zombie.admin")) {
            event.setCancelled(true);
            message(event.getPlayer(), "&cYou cannot dismantle registered power or CCTV devices.");
            return;
        }
        if (remove(id)) {
            message(event.getPlayer(), "&eRemoved device " + id + ".");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        stopViewing(event.getPlayer(), false);
        cameraPositions.values().forEach(positions -> positions.remove(event.getPlayer().getUniqueId()));
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

    private record ViewingSession(Location location, GameMode gameMode, Entity spectatorTarget, String cameraId) {
    }

    private static final class MonitorHolder implements InventoryHolder {
        private final UUID owner;
        private final String monitorId;
        private final Map<Integer, String> cameraSlots = new HashMap<>();
        private Inventory inventory;

        private MonitorHolder(UUID owner, String monitorId) {
            this.owner = owner;
            this.monitorId = monitorId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}