package dev.kodari.zombieapocalypse.item;

import com.cryptomorin.xseries.XSound;
import dev.kodari.zombieapocalypse.ZombieApocalypsePlugin;
import dev.kodari.zombieapocalypse.config.ConfigManager;
import dev.kodari.zombieapocalypse.noise.NoiseService;
import dev.kodari.zombieapocalypse.zombie.ZombieService;
import dev.kodari.zombieapocalypse.zombie.ZombieVariant;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class FirearmService {
    private final ZombieApocalypsePlugin plugin;
    private final ConfigManager config;
    private final CustomItemService items;
    private final NoiseService noise;
    private final ZombieService zombies;
    private final NamespacedKey magazineKey;
    private final NamespacedKey conditionKey;
    private final NamespacedKey serialKey;
    private final Map<UUID, Long> nextShotAt = new HashMap<>();
    private final Map<UUID, BukkitTask> reloadTasks = new HashMap<>();
    private final Map<UUID, BukkitTask> burstTasks = new HashMap<>();
    private final Set<UUID> applyingGunDamage = new HashSet<>();

    public FirearmService(ZombieApocalypsePlugin plugin, ConfigManager config, CustomItemService items,
                          NoiseService noise, ZombieService zombies) {
        this.plugin = plugin;
        this.config = config;
        this.items = items;
        this.noise = noise;
        this.zombies = zombies;
        this.magazineKey = new NamespacedKey("ultra_advanced_zombie_apocalypse", "magazine");
        this.conditionKey = new NamespacedKey("ultra_advanced_zombie_apocalypse", "weapon_condition");
        this.serialKey = new NamespacedKey("ultra_advanced_zombie_apocalypse", "weapon_serial");
    }

    public boolean handleInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !event.getAction().toString().startsWith("RIGHT_CLICK")
                || !config.getBoolean("firearms.enabled", true)) {
            return false;
        }
        Player player = event.getPlayer();
        ItemStack weapon = event.getItem();
        String gunId = items.getItemId(weapon);
        if (!isGun(gunId)) {
            return false;
        }
        event.setCancelled(true);
        if (!player.hasPermission("zombie.use")) {
            message(player, "&cYou are not allowed to use firearms.");
            return true;
        }
        if (!config.isWorldEnabled(player.getWorld())) {
            return true;
        }
        if (!ensureWeaponState(weapon, gunId)) {
            return true;
        }
        if (player.isSneaking()) {
            startReload(player, gunId, weaponSerial(weapon));
        } else {
            trigger(player, gunId, weaponSerial(weapon));
        }
        return true;
    }

    public void handleSwing(Player player) {
        if (!config.getBoolean("firearms.enabled", true) || !player.hasPermission("zombie.use")
                || !config.isWorldEnabled(player.getWorld())) {
            return;
        }
        ItemStack weapon = player.getInventory().getItemInMainHand();
        String gunId = items.getItemId(weapon);
        if (!isGun(gunId) || !config.getString("firearms.guns." + gunId + ".fire-mode", "SEMI")
                .equalsIgnoreCase("AUTO") || !ensureWeaponState(weapon, gunId)) {
            return;
        }
        trigger(player, gunId, weaponSerial(weapon));
    }

    public boolean isGun(String itemId) {
        return itemId != null && config.getSection("firearms.guns." + itemId) != null;
    }

    public boolean isApplyingGunDamage(Player player) {
        return applyingGunDamage.contains(player.getUniqueId());
    }

    public void cancelPlayer(Player player) {
        UUID id = player.getUniqueId();
        BukkitTask reload = reloadTasks.remove(id);
        if (reload != null) {
            reload.cancel();
        }
        BukkitTask burst = burstTasks.remove(id);
        if (burst != null) {
            burst.cancel();
        }
        nextShotAt.remove(id);
    }

    public void close() {
        reloadTasks.values().forEach(BukkitTask::cancel);
        burstTasks.values().forEach(BukkitTask::cancel);
        reloadTasks.clear();
        burstTasks.clear();
        nextShotAt.clear();
        applyingGunDamage.clear();
    }

    private void trigger(Player player, String gunId, String serial) {
        String mode = config.getString("firearms.guns." + gunId + ".fire-mode", "SEMI").toUpperCase(Locale.ROOT);
        if (!mode.equals("BURST")) {
            fireOnce(player, gunId, serial);
            return;
        }
        UUID playerId = player.getUniqueId();
        if (burstTasks.containsKey(playerId) || !fireOnce(player, gunId, serial)) {
            return;
        }
        int remainingShots = Math.max(0, config.getInt("firearms.guns." + gunId + ".burst-shots", 3) - 1);
        if (remainingShots == 0) {
            return;
        }
        int interval = Math.max(1, config.getInt("firearms.guns." + gunId + ".fire-delay-ticks", 4));
        int[] remaining = {remainingShots};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline() || !fireOnce(player, gunId, serial)) {
                stopBurst(playerId, task[0]);
                return;
            }
            remaining[0]--;
            if (remaining[0] <= 0) {
                stopBurst(playerId, task[0]);
            }
        }, interval, interval);
        burstTasks.put(playerId, task[0]);
    }

    private boolean fireOnce(Player player, String gunId, String serial) {
        if (!player.isOnline() || !config.getBoolean("firearms.enabled", true)
                || !config.isWorldEnabled(player.getWorld()) || !player.hasPermission("zombie.use")) {
            return false;
        }
        ItemStack weapon = player.getInventory().getItemInMainHand();
        if (!gunId.equals(items.getItemId(weapon)) || !serial.equals(weaponSerial(weapon))
                || !ensureWeaponState(weapon, gunId)) {
            return false;
        }
        if (reloadTasks.containsKey(player.getUniqueId())) {
            return false;
        }
        String path = "firearms.guns." + gunId + ".";
        ItemMeta meta = weapon.getItemMeta();
        if (meta == null) {
            return false;
        }
        int magazine = meta.getPersistentDataContainer().getOrDefault(magazineKey, PersistentDataType.INTEGER, 0);
        int condition = meta.getPersistentDataContainer().getOrDefault(conditionKey, PersistentDataType.INTEGER, 0);
        if (magazine <= 0) {
            message(player, "&cMagazine empty. Sneak and right-click to reload.");
            return false;
        }
        if (condition <= 0) {
            player.getInventory().setItemInMainHand(null);
            message(player, "&cThat firearm is broken.");
            return false;
        }
        long now = System.currentTimeMillis();
        if (now < nextShotAt.getOrDefault(player.getUniqueId(), 0L)) {
            return false;
        }
        long delayMillis = Math.max(1, config.getInt(path + "fire-delay-ticks", 4)) * 50L;
        nextShotAt.put(player.getUniqueId(), now + delayMillis);

        magazine--;
        condition--;
        setWeaponState(weapon, gunId, serial, magazine, condition);
        playSound(player, config.getString(path + "sound", "ENTITY_ARROW_SHOOT"));
        noise.emit(player, config.getDouble("firearms.noise-radius", 64));
        int pellets = Math.max(1, config.getInt(path + "pellets", 1));
        for (int pellet = 0; pellet < pellets; pellet++) {
            traceShot(player, gunId);
        }
        float recoil = (float) config.getDouble(path + "recoil", 0);
        if (recoil > 0) {
            Location rotation = player.getLocation();
            player.setRotation(rotation.getYaw(), Math.max(-90, Math.min(90, rotation.getPitch() - recoil)));
        }
        if (condition <= 0) {
            player.getInventory().setItemInMainHand(null);
            message(player, "&cYour firearm broke.");
        }
        return true;
    }

    private void traceShot(Player player, String gunId) {
        String path = "firearms.guns." + gunId + ".";
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection().clone();
        double spread = config.getDouble(path + "spread", 0.02);
        if (spread > 0) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            direction.add(new Vector(random.nextDouble(-spread, spread), random.nextDouble(-spread, spread),
                    random.nextDouble(-spread, spread))).normalize();
        }
        double range = Math.min(config.getDouble(path + "range", 64),
                config.getDouble("firearms.max-range-blocks", 128));
        RayTraceResult entityHit = eye.getWorld().rayTraceEntities(eye, direction, range, 0.25,
                entity -> entity instanceof LivingEntity && !entity.getUniqueId().equals(player.getUniqueId()));
        if (entityHit == null || entityHit.getHitEntity() == null) {
            return;
        }
        RayTraceResult blockHit = eye.getWorld().rayTraceBlocks(eye, direction, range, FluidCollisionMode.NEVER, true);
        if (blockHit != null && eye.toVector().distance(blockHit.getHitPosition())
                < eye.toVector().distance(entityHit.getHitPosition())) {
            return;
        }
        if (!(entityHit.getHitEntity() instanceof Zombie zombie)) {
            return;
        }
        double multiplier = config.getDouble("firearms.zombie-damage-multipliers.default", 1.0);
        ZombieVariant variant = zombies.getVariant(zombie);
        if (variant != null) {
            multiplier = config.getDouble("firearms.zombie-damage-multipliers." + variant.id(), multiplier);
        }
        double damage = config.getDouble(path + "damage", 5.0) * multiplier;
        if (damage > 0) {
            UUID shooterId = player.getUniqueId();
            applyingGunDamage.add(shooterId);
            try {
                zombie.damage(damage, player);
            } finally {
                applyingGunDamage.remove(shooterId);
            }
        }
    }

    private void startReload(Player player, String gunId, String serial) {
        UUID playerId = player.getUniqueId();
        if (reloadTasks.containsKey(playerId)) {
            message(player, "&eYou are already reloading.");
            return;
        }
        String path = "firearms.guns." + gunId + ".";
        ItemStack weapon = player.getInventory().getItemInMainHand();
        ItemMeta meta = weapon.getItemMeta();
        if (meta == null) {
            return;
        }
        int capacity = config.getInt(path + "magazine", 1);
        int loaded = meta.getPersistentDataContainer().getOrDefault(magazineKey, PersistentDataType.INTEGER, capacity);
        int needed = capacity - loaded;
        if (needed <= 0) {
            message(player, "&eMagazine is already full.");
            return;
        }
        String ammoId = config.getString(path + "ammo", "");
        if (countAmmo(player, ammoId) <= 0) {
            message(player, "&cYou do not have the required ammunition.");
            return;
        }
        message(player, "&eReloading...");
        long delay = Math.max(1, config.getInt(path + "reload-ticks", 30));
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> finishReload(player, gunId, serial), delay);
        reloadTasks.put(playerId, task);
    }

    private void finishReload(Player player, String gunId, String serial) {
        UUID playerId = player.getUniqueId();
        reloadTasks.remove(playerId);
        if (!player.isOnline()) {
            return;
        }
        ItemStack weapon = player.getInventory().getItemInMainHand();
        if (!gunId.equals(items.getItemId(weapon)) || !serial.equals(weaponSerial(weapon))
                || !ensureWeaponState(weapon, gunId)) {
            return;
        }
        String path = "firearms.guns." + gunId + ".";
        ItemMeta meta = weapon.getItemMeta();
        if (meta == null) {
            return;
        }
        int capacity = config.getInt(path + "magazine", 1);
        int loaded = meta.getPersistentDataContainer().getOrDefault(magazineKey, PersistentDataType.INTEGER, 0);
        int available = countAmmo(player, config.getString(path + "ammo", ""));
        int amount = Math.min(capacity - loaded, available);
        if (amount <= 0 || !removeAmmo(player, config.getString(path + "ammo", ""), amount)) {
            message(player, "&cReload cancelled: no ammunition is available.");
            return;
        }
        setWeaponState(weapon, gunId, serial, loaded + amount,
                meta.getPersistentDataContainer().getOrDefault(conditionKey, PersistentDataType.INTEGER, 0));
        playSound(player, config.getString(path + "reload-sound", "BLOCK_LEVER_CLICK"));
        message(player, "&aReloaded &f" + amount + "&a round(s).");
    }

    private boolean ensureWeaponState(ItemStack weapon, String gunId) {
        if (weapon == null || !weapon.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = weapon.getItemMeta();
        if (meta == null) {
            return false;
        }
        String path = "firearms.guns." + gunId + ".";
        int capacity = config.getInt(path + "magazine", 1);
        int maxCondition = config.getInt(path + "durability", 100);
        var data = meta.getPersistentDataContainer();
        Integer storedMagazine = data.get(magazineKey, PersistentDataType.INTEGER);
        Integer storedCondition = data.get(conditionKey, PersistentDataType.INTEGER);
        String serial = data.get(serialKey, PersistentDataType.STRING);
        int magazine = storedMagazine == null ? capacity : Math.max(0, Math.min(capacity, storedMagazine));
        int condition = storedCondition == null ? maxCondition : Math.max(0, Math.min(maxCondition, storedCondition));
        if (serial == null) {
            serial = UUID.randomUUID().toString();
        }
        data.set(magazineKey, PersistentDataType.INTEGER, magazine);
        data.set(conditionKey, PersistentDataType.INTEGER, condition);
        data.set(serialKey, PersistentDataType.STRING, serial);
        meta.setLore(List.of(ChatColor.GRAY + "Ammo: " + magazine + "/" + capacity,
                ChatColor.GRAY + "Condition: " + condition + "/" + maxCondition));
        weapon.setItemMeta(meta);
        return true;
    }

    private void setWeaponState(ItemStack weapon, String gunId, String serial, int magazine, int condition) {
        ItemMeta meta = weapon.getItemMeta();
        if (meta == null) {
            return;
        }
        String path = "firearms.guns." + gunId + ".";
        int capacity = config.getInt(path + "magazine", 1);
        int maxCondition = config.getInt(path + "durability", 100);
        int boundedMagazine = Math.max(0, Math.min(capacity, magazine));
        int boundedCondition = Math.max(0, Math.min(maxCondition, condition));
        meta.getPersistentDataContainer().set(magazineKey, PersistentDataType.INTEGER, boundedMagazine);
        meta.getPersistentDataContainer().set(conditionKey, PersistentDataType.INTEGER, boundedCondition);
        meta.getPersistentDataContainer().set(serialKey, PersistentDataType.STRING, serial);
        meta.setLore(List.of(ChatColor.GRAY + "Ammo: " + boundedMagazine + "/" + capacity,
                ChatColor.GRAY + "Condition: " + boundedCondition + "/" + maxCondition));
        weapon.setItemMeta(meta);
    }

    private String weaponSerial(ItemStack weapon) {
        if (weapon == null || !weapon.hasItemMeta()) {
            return null;
        }
        return weapon.getItemMeta().getPersistentDataContainer().get(serialKey, PersistentDataType.STRING);
    }

    private int countAmmo(Player player, String ammoId) {
        if (ammoId == null || ammoId.isBlank()) {
            return 0;
        }
        int total = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && ammoId.equals(items.getItemId(item))) {
                total += item.getAmount();
            }
        }
        return total;
    }

    private boolean removeAmmo(Player player, String ammoId, int amount) {
        if (amount < 1 || countAmmo(player, ammoId) < amount) {
            return false;
        }
        int remaining = amount;
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (item == null || !ammoId.equals(items.getItemId(item))) {
                continue;
            }
            int removed = Math.min(remaining, item.getAmount());
            remaining -= removed;
            if (removed == item.getAmount()) {
                player.getInventory().setItem(slot, null);
            } else {
                item.setAmount(item.getAmount() - removed);
            }
        }
        return remaining == 0;
    }

    private void playSound(Player player, String soundName) {
        XSound.matchXSound(soundName).ifPresent(sound -> sound.play(player));
    }

    private void stopBurst(UUID playerId, BukkitTask task) {
        task.cancel();
        burstTasks.remove(playerId, task);
    }

    private void message(Player player, String message) {
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&6[Zombie] " + message));
    }
}