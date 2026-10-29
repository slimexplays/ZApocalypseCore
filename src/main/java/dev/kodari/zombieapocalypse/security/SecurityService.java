package dev.kodari.zombieapocalypse.security;

import dev.kodari.zombieapocalypse.ZombieApocalypsePlugin;
import dev.kodari.zombieapocalypse.config.ConfigManager;
import dev.kodari.zombieapocalypse.device.PowerCctvService;
import dev.kodari.zombieapocalypse.item.CustomItemService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Door;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityBreakDoorEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class SecurityService implements Listener, AutoCloseable {
    private final ZombieApocalypsePlugin plugin;
    private final ConfigManager config;
    private final CustomItemService items;
    private final PowerCctvService power;
    private final File file;
    private final YamlConfiguration data;
    private final NamespacedKey cardSerialKey;
    private final NamespacedKey cardDoorKey;
    private final NamespacedKey cardOwnerKey;
    private final Map<String, DoorLock> doors = new HashMap<>();
    private final Map<String, String> doorAtBlock = new HashMap<>();
    private final Map<String, Long> openUntil = new HashMap<>();
    private final Map<UUID, List<Long>> failedPins = new HashMap<>();
    private final BukkitTask doorTask;
    private boolean lockdown;

    public SecurityService(ZombieApocalypsePlugin plugin, ConfigManager config, CustomItemService items,
                           PowerCctvService power) {
        this.plugin = plugin;
        this.config = config;
        this.items = items;
        this.power = power;
        this.file = new File(plugin.getDataFolder(), "security.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
        this.cardSerialKey = new NamespacedKey(plugin, "security_card_serial");
        this.cardDoorKey = new NamespacedKey(plugin, "security_card_door");
        this.cardOwnerKey = new NamespacedKey(plugin, "security_card_owner");
        load();
        this.doorTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updateDoors, 10L, 10L);
    }

    public void lock(Player player, String id, String pin) {
        if (!config.getBoolean("security.enabled", true) || !player.hasPermission("zombie.security.manage")) {
            message(player, "&cSecure doors are disabled or you lack permission.");
            return;
        }
        if (!validId(id) || !validPin(pin)) {
            message(player, "&cUse a valid 1-32 character ID and a 4-8 digit PIN.");
            return;
        }
        Block base = doorBase(player.getTargetBlockExact(8));
        if (base == null) {
            message(player, "&cLook at a door within 8 blocks to secure it.");
            return;
        }
        String key = blockKey(base.getLocation());
        if (doorAtBlock.containsKey(key) || doors.size() >= config.getInt("security.max-doors", 256)) {
            message(player, "&cThat door is already secured or the secure-door limit was reached.");
            return;
        }
        String normalizedId = id.toLowerCase(Locale.ROOT);
        Location location = base.getLocation();
        if (!power.place("secure_door", normalizedId, location, player.getLocation().getYaw(), player.getLocation().getPitch())) {
            message(player, "&cCould not register the powered lock; check the device limit and ID.");
            return;
        }
        String path = "doors." + normalizedId;
        String salt = UUID.randomUUID().toString();
        data.set(path + ".world", location.getWorld().getName());
        data.set(path + ".x", location.getBlockX());
        data.set(path + ".y", location.getBlockY());
        data.set(path + ".z", location.getBlockZ());
        data.set(path + ".owner", player.getUniqueId().toString());
        data.set(path + ".pin-salt", salt);
        data.set(path + ".pin-hash", hashPin(salt, pin));
        data.set(path + ".members", List.of());
        DoorLock lock = new DoorLock(normalizedId, location.getWorld().getName(), location.getBlockX(),
                location.getBlockY(), location.getBlockZ());
        doors.put(normalizedId, lock);
        indexDoor(lock);
        save();
        message(player, "&aPowered secure door registered as &f" + normalizedId + "&a. It fails closed without power.");
    }

    public void setLockdown(boolean lockdown) {
        this.lockdown = lockdown;
        if (lockdown) {
            openUntil.clear();
            updateDoors();
        }
    }

    public void allow(Player player, String id, String name, boolean allowed) {
        DoorLock door = managedDoor(player, id);
        if (door == null) {
            return;
        }
        UUID target = resolvePlayer(name);
        if (target == null || target.equals(owner(door.id()))) {
            message(player, "&cChoose a player who has joined this server.");
            return;
        }
        String path = "doors." + door.id() + ".members";
        List<String> members = new ArrayList<>(data.getStringList(path));
        if (allowed && !members.contains(target.toString())) {
            members.add(target.toString());
        } else if (!allowed) {
            members.remove(target.toString());
            revokeCards(door.id(), target);
        }
        data.set(path, members);
        save();
        message(player, allowed ? "&aAccess granted to " + displayName(Bukkit.getOfflinePlayer(target)) + "."
                : "&eAccess revoked from " + displayName(Bukkit.getOfflinePlayer(target)) + ".");
    }

    public void issueKeycard(Player player, String id, String targetName) {
        DoorLock door = managedDoor(player, id);
        if (door == null) {
            return;
        }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            message(player, "&cThe keycard recipient must be online.");
            return;
        }
        ItemStack card = items.create("keycard");
        if (card == null || !card.hasItemMeta()) {
            message(player, "&cThe keycard item is not configured correctly.");
            return;
        }
        String serial = UUID.randomUUID().toString();
        ItemMeta meta = card.getItemMeta();
        meta.getPersistentDataContainer().set(cardSerialKey, PersistentDataType.STRING, serial);
        meta.getPersistentDataContainer().set(cardDoorKey, PersistentDataType.STRING, door.id());
        meta.getPersistentDataContainer().set(cardOwnerKey, PersistentDataType.STRING, target.getUniqueId().toString());
        card.setItemMeta(meta);
        data.set("cards." + serial + ".door", door.id());
        data.set("cards." + serial + ".owner", target.getUniqueId().toString());
        save();
        Map<Integer, ItemStack> overflow = target.getInventory().addItem(card);
        overflow.values().forEach(item -> target.getWorld().dropItemNaturally(target.getLocation(), item));
        message(player, "&aIssued a bound keycard to " + target.getName() + ".");
        message(target, "&bYou received a keycard for secure door &f" + door.id() + "&b.");
    }

    public void setPin(Player player, String id, String pin) {
        DoorLock door = managedDoor(player, id);
        if (door == null) {
            return;
        }
        if (!validPin(pin)) {
            message(player, "&cPIN must contain 4-8 digits.");
            return;
        }
        String salt = UUID.randomUUID().toString();
        data.set("doors." + door.id() + ".pin-salt", salt);
        data.set("doors." + door.id() + ".pin-hash", hashPin(salt, pin));
        save();
        message(player, "&aDoor PIN updated.");
    }

    public void unlockWithPin(Player player, String id, String pin) {
        DoorLock door = doors.get(id.toLowerCase(Locale.ROOT));
        if (door == null) {
            message(player, "&cUnknown secure door.");
            return;
        }
        if (!validPin(pin)) {
            message(player, "&cPIN must contain 4-8 digits.");
            return;
        }
        List<Long> attempts = failedPins.computeIfAbsent(player.getUniqueId(), ignored -> new ArrayList<>());
        long now = System.currentTimeMillis();
        attempts.removeIf(attempt -> now - attempt > 60_000L);
        if (attempts.size() >= 5) {
            message(player, "&cToo many incorrect PIN attempts. Try again in a minute.");
            return;
        }
        String salt = data.getString("doors." + door.id() + ".pin-salt", "");
        String expected = data.getString("doors." + door.id() + ".pin-hash", "");
        boolean correct = MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                hashPin(salt, pin).getBytes(StandardCharsets.UTF_8));
        if (!correct) {
            attempts.add(now);
            message(player, "&cIncorrect PIN.");
            return;
        }
        attempts.clear();
        openDoor(player, door);
    }

    public void remove(Player player, String id) {
        DoorLock door = managedDoor(player, id);
        if (door == null) {
            return;
        }
        power.remove(door.id());
        doors.remove(door.id());
        doorAtBlock.remove(blockKey(door.world(), door.x(), door.y(), door.z()));
        doorAtBlock.remove(blockKey(door.world(), door.x(), door.y() + 1, door.z()));
        openUntil.remove(door.id());
        ConfigurationSection cards = data.getConfigurationSection("cards");
        if (cards != null) {
            for (String serial : new ArrayList<>(cards.getKeys(false))) {
                if (door.id().equals(data.getString("cards." + serial + ".door"))) {
                    data.set("cards." + serial, null);
                }
            }
        }
        data.set("doors." + door.id(), null);
        save();
        message(player, "&eSecure door " + door.id() + " removed.");
    }

    public void status(Player player, String id) {
        DoorLock door = doors.get(id.toLowerCase(Locale.ROOT));
        if (door == null) {
            message(player, "&cUnknown secure door.");
            return;
        }
        PowerCctvService.Device device = power.find(door.id());
        message(player, "&eDoor &f" + door.id() + " &7| Power: "
                + (device != null && power.isPowered(device) ? "&aON" : "&cOFF")
                + " &7| Owner: &f" + displayName(Bukkit.getOfflinePlayer(owner(door.id()))));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDoorInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        Block base = doorBase(event.getClickedBlock());
        if (base == null) {
            return;
        }
        String doorId = doorAtBlock.get(blockKey(base.getLocation()));
        DoorLock door = doorId == null ? null : doors.get(doorId);
        if (door == null) {
            return;
        }
        event.setCancelled(true);
        if (lockdown) {
            message(event.getPlayer(), "&cEmergency lockdown is active.");
            return;
        }
        openDoor(event.getPlayer(), door);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityBreakDoor(EntityBreakDoorEvent event) {
        Block base = doorBase(event.getBlock());
        if (base != null && doorAtBlock.containsKey(blockKey(base.getLocation()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDoorBreak(org.bukkit.event.block.BlockBreakEvent event) {
        Block base = doorBase(event.getBlock());
        if (base == null) {
            return;
        }
        String id = doorAtBlock.get(blockKey(base.getLocation()));
        if (id == null) {
            return;
        }
        if (!isAdmin(event.getPlayer())) {
            event.setCancelled(true);
            message(event.getPlayer(), "&cYou cannot break a powered secure door.");
            return;
        }
        remove(event.getPlayer(), id);
    }

    @EventHandler
    public void onKeycardPickup(EntityPickupItemEvent event) {
        ItemStack item = event.getItem().getItemStack();
        if (!"keycard".equals(items.getItemId(item))) {
            return;
        }
        Entity entity = event.getEntity();
        String ownerId = item.getItemMeta().getPersistentDataContainer().get(cardOwnerKey, PersistentDataType.STRING);
        if (!(entity instanceof Player player) || !player.getUniqueId().toString().equals(ownerId)) {
            event.setCancelled(true);
        }
    }

    private void openDoor(Player player, DoorLock door) {
        if (lockdown) {
            message(player, "&cEmergency lockdown is active.");
            return;
        }
        PowerCctvService.Device device = power.find(door.id());
        if (device == null || !device.type().equals("secure_door") || !power.isPowered(device)) {
            message(player, "&cThe secure door has no power and remains locked.");
            return;
        }
        if (!authorized(player, door.id())) {
            message(player, "&cAccess denied. Use your bound keycard or /zsecurity pin " + door.id() + " <PIN>.");
            return;
        }
        Block block = doorBlock(door);
        if (block == null) {
            message(player, "&cThe registered door is missing or its world is unloaded.");
            return;
        }
        Door state = (Door) block.getBlockData();
        if (state.isOpen()) {
            state.setOpen(false);
            block.setBlockData(state, false);
            openUntil.remove(door.id());
        } else {
            state.setOpen(true);
            block.setBlockData(state, false);
            openUntil.put(door.id(), System.currentTimeMillis() + config.getInt("security.open-seconds", 5) * 1000L);
        }
    }

    private boolean authorized(Player player, String id) {
        return isAdmin(player)
                || player.getUniqueId().equals(owner(id))
                || data.getStringList("doors." + id + ".members").contains(player.getUniqueId().toString())
                || hasKeycard(player, id);
    }

    private boolean hasKeycard(Player player, String doorId) {
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (validKeycard(item, player.getUniqueId(), doorId)) {
                return true;
            }
        }
        return validKeycard(player.getInventory().getItemInOffHand(), player.getUniqueId(), doorId);
    }

    private boolean validKeycard(ItemStack item, UUID player, String doorId) {
        if (item == null || !"keycard".equals(items.getItemId(item)) || !item.hasItemMeta()) {
            return false;
        }
        var container = item.getItemMeta().getPersistentDataContainer();
        String serial = container.get(cardSerialKey, PersistentDataType.STRING);
        String cardDoor = container.get(cardDoorKey, PersistentDataType.STRING);
        String cardOwner = container.get(cardOwnerKey, PersistentDataType.STRING);
        return serial != null && doorId.equals(cardDoor) && player.toString().equals(cardOwner)
                && doorId.equals(data.getString("cards." + serial + ".door"))
                && player.toString().equals(data.getString("cards." + serial + ".owner"));
    }

    private DoorLock managedDoor(Player player, String id) {
        DoorLock door = doors.get(id.toLowerCase(Locale.ROOT));
        if (door == null) {
            message(player, "&cUnknown secure door.");
            return null;
        }
        if (!isAdmin(player) && !player.getUniqueId().equals(owner(door.id()))) {
            message(player, "&cOnly the door owner or a security admin can change access.");
            return null;
        }
        return door;
    }

    private void revokeCards(String doorId, UUID owner) {
        ConfigurationSection cards = data.getConfigurationSection("cards");
        if (cards == null) {
            return;
        }
        for (String serial : new ArrayList<>(cards.getKeys(false))) {
            String path = "cards." + serial;
            if (doorId.equals(data.getString(path + ".door")) && owner.toString().equals(data.getString(path + ".owner"))) {
                data.set(path, null);
            }
        }
    }

    private void updateDoors() {
        long now = System.currentTimeMillis();
        for (DoorLock door : doors.values()) {
            PowerCctvService.Device device = power.find(door.id());
            boolean powered = device != null && power.isPowered(device);
            Long until = openUntil.get(door.id());
            if (lockdown || !powered || until == null || now >= until) {
                closeDoor(door);
                if (until != null && now >= until) {
                    openUntil.remove(door.id());
                }
            }
        }
    }

    private void closeDoor(DoorLock door) {
        Block block = doorBlock(door);
        if (block == null || !(block.getBlockData() instanceof Door state) || !state.isOpen()) {
            return;
        }
        state.setOpen(false);
        block.setBlockData(state, false);
    }

    private Block doorBlock(DoorLock door) {
        var world = Bukkit.getWorld(door.world());
        if (world == null) {
            return null;
        }
        Block block = world.getBlockAt(door.x(), door.y(), door.z());
        return block.getBlockData() instanceof Door ? block : null;
    }

    private Block doorBase(Block block) {
        if (block == null || !(block.getBlockData() instanceof Door door)) {
            return null;
        }
        return door.getHalf() == Bisected.Half.TOP ? block.getRelative(BlockFace.DOWN) : block;
    }

    private void indexDoor(DoorLock door) {
        doorAtBlock.put(blockKey(door.world(), door.x(), door.y(), door.z()), door.id());
        doorAtBlock.put(blockKey(door.world(), door.x(), door.y() + 1, door.z()), door.id());
    }

    private void load() {
        ConfigurationSection section = data.getConfigurationSection("doors");
        if (section == null) {
            return;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(id);
            if (entry == null || !validId(id)) {
                continue;
            }
            String world = entry.getString("world", "");
            String owner = entry.getString("owner", "");
            try {
                UUID.fromString(owner);
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Ignoring secure door with invalid owner: " + id);
                continue;
            }
            DoorLock door = new DoorLock(id, world, entry.getInt("x"), entry.getInt("y"), entry.getInt("z"));
            PowerCctvService.Device device = power.find(id);
            if (world.isBlank() || device == null || !device.type().equals("secure_door")) {
                plugin.getLogger().warning("Ignoring secure door without a matching powered device: " + id);
                continue;
            }
            doors.put(id, door);
            indexDoor(door);
        }
    }

    private void save() {
        try {
            data.save(file);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not save security data: " + exception.getMessage());
        }
    }

    private UUID owner(String id) {
        try {
            return UUID.fromString(data.getString("doors." + id + ".owner", ""));
        } catch (IllegalArgumentException exception) {
            return new UUID(0L, 0L);
        }
    }

    private UUID resolvePlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        return offline.hasPlayedBefore() ? offline.getUniqueId() : null;
    }

    private String displayName(OfflinePlayer player) {
        return player.getName() == null ? player.getUniqueId().toString().substring(0, 8) : player.getName();
    }

    private boolean isAdmin(Player player) {
        return player.hasPermission("zombie.admin") || player.hasPermission("zombie.security.admin");
    }

    private String blockKey(Location location) {
        return blockKey(location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    private String blockKey(String world, int x, int y, int z) {
        return world.toLowerCase(Locale.ROOT) + ":" + x + ":" + y + ":" + z;
    }

    private boolean validId(String id) {
        return id != null && id.matches("[a-zA-Z0-9_-]{1,32}");
    }

    private boolean validPin(String pin) {
        return pin != null && pin.matches("[0-9]{4,8}");
    }

    private String hashPin(String salt, String pin) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest((salt + ":" + pin).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void message(Player player, String text) {
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', text));
    }

    public void close() {
        doorTask.cancel();
        for (DoorLock door : doors.values()) {
            closeDoor(door);
        }
        save();
    }

    private record DoorLock(String id, String world, int x, int y, int z) {
    }
}