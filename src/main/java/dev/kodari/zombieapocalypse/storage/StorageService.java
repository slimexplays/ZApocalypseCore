package dev.kodari.zombieapocalypse.storage;

import dev.kodari.zombieapocalypse.ZombieApocalypsePlugin;
import dev.kodari.zombieapocalypse.config.ConfigManager;
import dev.kodari.zombieapocalypse.item.CustomItemService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class StorageService implements Listener {
    private static final class StorageRecord {
        private final UUID id;
        private final UUID ownerId;
        private final String itemId;
        private final Inventory inventory;
        private StorageAccessMode accessMode;

        private StorageRecord(UUID id, UUID ownerId, String itemId, Inventory inventory, StorageAccessMode accessMode) {
            this.id = id;
            this.ownerId = ownerId;
            this.itemId = itemId;
            this.inventory = inventory;
            this.accessMode = accessMode;
        }
    }

    private final ZombieApocalypsePlugin plugin;
    private final ConfigManager config;
    private final CustomItemService items;
    private final StorageStore store;
    private final Map<UUID, StorageRecord> storages = new HashMap<>();
    private boolean loaded;
    private boolean dirty;
    private BukkitTask saveTask;
    private StorageTeamResolver teamResolver;

    public StorageService(ZombieApocalypsePlugin plugin, ConfigManager config, CustomItemService items, StorageStore store) {
        this.plugin = plugin;
        this.config = config;
        this.items = items;
        this.store = store;
        store.load().whenComplete((contents, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!plugin.isEnabled()) {
                return;
            }
            if (error != null) {
                plugin.getLogger().severe("Custom storage data could not be loaded; storage access remains disabled to protect existing data: "
                        + error.getMessage());
                return;
            }
            try {
                loadContents(contents);
                loaded = true;
            } catch (InvalidConfigurationException | RuntimeException exception) {
                plugin.getLogger().severe("Custom storage data has an unsupported or invalid schema; storage access remains disabled: "
                        + exception.getMessage());
            }
        }));
    }

    public void setTeamResolver(StorageTeamResolver teamResolver) {
        this.teamResolver = teamResolver;
    }

    public boolean handleInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !event.getAction().toString().startsWith("RIGHT_CLICK")) {
            return false;
        }
        ItemStack held = event.getItem();
        String itemId = items.getItemId(held);
        if (itemId == null || config.getInt("items." + itemId + ".storage-size", 0) <= 0) {
            return false;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!loaded) {
            message(player, "&cStorage data is still loading or was rejected; try again later.");
            return true;
        }
        UUID storageId = items.getStorageId(held);
        if (storageId == null) {
            message(player, "&cThis storage item has no valid identity.");
            return true;
        }
        StorageRecord record = storages.get(storageId);
        if (record == null) {
            record = createRecord(player, storageId, itemId);
            if (record == null) {
                message(player, "&cThe configured storage size is invalid.");
                return true;
            }
            storages.put(storageId, record);
            markDirty();
        }
        if (!canAccess(player, record)) {
            plugin.getLogger().warning("Denied storage access: player=" + player.getUniqueId() + " storage=" + storageId);
            message(player, "&cYou do not have access to that storage.");
            return true;
        }
        player.openInventory(record.inventory);
        return true;
    }

    public boolean setAccessMode(Player player, ItemStack storageItem, StorageAccessMode mode) {
        if (!loaded || storageItem == null) {
            return false;
        }
        String itemId = items.getItemId(storageItem);
        UUID id = items.getStorageId(storageItem);
        if (itemId == null || id == null || config.getInt("items." + itemId + ".storage-size", 0) <= 0) {
            return false;
        }
        StorageRecord record = storages.get(id);
        if (record == null) {
            record = createRecord(player, id, itemId);
            if (record == null) {
                return false;
            }
            storages.put(id, record);
        }
        if (!player.getUniqueId().equals(record.ownerId) && !player.hasPermission("zombie.admin")) {
            return false;
        }
        record.accessMode = mode;
        markDirty();
        return true;
    }

    public StorageAccessMode getAccessMode(ItemStack storageItem) {
        UUID id = items.getStorageId(storageItem);
        StorageRecord record = id == null ? null : storages.get(id);
        return record == null ? null : record.accessMode;
    }

    private StorageRecord createRecord(Player owner, UUID id, String itemId) {
        int size = config.getInt("items." + itemId + ".storage-size", 0);
        if (size < 9 || size > 54 || size % 9 != 0) {
            return null;
        }
        StorageAccessMode mode;
        try {
            mode = StorageAccessMode.valueOf(config.getString("items." + itemId + ".storage-access", "PRIVATE").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            mode = StorageAccessMode.PRIVATE;
        }
        StorageInventoryHolder holder = new StorageInventoryHolder(id);
        String title = ChatColor.translateAlternateColorCodes('&', config.getString("items." + itemId + ".name", itemId));
        Inventory inventory = Bukkit.createInventory(holder, size, title);
        holder.setInventory(inventory);
        return new StorageRecord(id, owner.getUniqueId(), itemId, inventory, mode);
    }

    private boolean canAccess(Player player, StorageRecord record) {
        if (player.hasPermission("zombie.admin") || player.getUniqueId().equals(record.ownerId)
                || record.accessMode == StorageAccessMode.PUBLIC) {
            return true;
        }
        return record.accessMode == StorageAccessMode.TEAM && teamResolver != null
                && teamResolver.areTeammates(record.ownerId, player.getUniqueId());
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        StorageRecord record = recordFrom(event.getView().getTopInventory());
        if (record == null || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!canAccess(player, record)) {
            event.setCancelled(true);
            plugin.getLogger().warning("Denied storage click: player=" + player.getUniqueId() + " storage=" + record.id);
            return;
        }
        markDirty();
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        StorageRecord record = recordFrom(event.getView().getTopInventory());
        if (record == null || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!canAccess(player, record)) {
            event.setCancelled(true);
            plugin.getLogger().warning("Denied storage drag: player=" + player.getUniqueId() + " storage=" + record.id);
            return;
        }
        if (event.getRawSlots().stream().anyMatch(slot -> slot < event.getView().getTopInventory().getSize())) {
            markDirty();
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (recordFrom(event.getView().getTopInventory()) != null) {
            markDirty();
        }
    }

    private StorageRecord recordFrom(Inventory inventory) {
        if (!(inventory.getHolder() instanceof StorageInventoryHolder holder)) {
            return null;
        }
        return storages.get(holder.storageId());
    }

    private void loadContents(String contents) throws InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(contents);
        if (yaml.getInt("schema", -1) != 1) {
            throw new IllegalArgumentException("unsupported schema version");
        }
        ConfigurationSection section = yaml.getConfigurationSection("storages");
        if (section == null) {
            return;
        }
        for (String rawId : section.getKeys(false)) {
            UUID id = UUID.fromString(rawId);
            String path = "storages." + rawId + ".";
            UUID owner = UUID.fromString(yaml.getString(path + "owner", ""));
            String itemId = yaml.getString(path + "item-id", "");
            int size = yaml.getInt(path + "size", 0);
            StorageAccessMode mode = StorageAccessMode.valueOf(yaml.getString(path + "access", "PRIVATE"));
            if (config.getInt("items." + itemId + ".storage-size", 0) <= 0 || size < 9 || size > 54 || size % 9 != 0) {
                throw new IllegalArgumentException("invalid record " + rawId);
            }
            StorageInventoryHolder holder = new StorageInventoryHolder(id);
            String title = ChatColor.translateAlternateColorCodes('&', config.getString("items." + itemId + ".name", itemId));
            Inventory inventory = Bukkit.createInventory(holder, size, title);
            holder.setInventory(inventory);
            for (int slot = 0; slot < size; slot++) {
                Object stored = yaml.get(path + "contents." + slot);
                if (stored instanceof ItemStack item) {
                    inventory.setItem(slot, item);
                }
            }
            storages.put(id, new StorageRecord(id, owner, itemId, inventory, mode));
        }
    }

    private void markDirty() {
        if (!loaded) {
            return;
        }
        dirty = true;
        if (saveTask == null) {
            saveTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                saveTask = null;
                save();
            }, 1L);
        }
    }

    private void save() {
        if (!loaded || !dirty) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("schema", 1);
        for (StorageRecord record : storages.values()) {
            String path = "storages." + record.id + ".";
            yaml.set(path + "owner", record.ownerId.toString());
            yaml.set(path + "item-id", record.itemId);
            yaml.set(path + "access", record.accessMode.name());
            yaml.set(path + "size", record.inventory.getSize());
            for (int slot = 0; slot < record.inventory.getSize(); slot++) {
                ItemStack item = record.inventory.getItem(slot);
                if (item != null) {
                    yaml.set(path + "contents." + slot, item);
                }
            }
        }
        store.save(yaml.saveToString());
        dirty = false;
    }

    private void message(Player player, String text) {
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', text));
    }

    public void shutdown() {
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }
        save();
    }
}