package dev.kodari.zombieapocalypse.infection;

import dev.kodari.zombieapocalypse.ZombieApocalypsePlugin;
import dev.kodari.zombieapocalypse.config.ConfigManager;
import dev.kodari.zombieapocalypse.storage.InfectionStore;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class InfectionManager {
    private final ZombieApocalypsePlugin plugin;
    private final ConfigManager config;
    private final InfectionStore store;
    private final NamespacedKey immunityKey;
    private final Map<UUID, InfectionRecord> records = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> infectionDeaths = ConcurrentHashMap.newKeySet();
    private final Set<UUID> clearedBeforeLoad = ConcurrentHashMap.newKeySet();
    private volatile boolean loaded;
    private boolean pendingSave;
    private BukkitTask task;

    public InfectionManager(ZombieApocalypsePlugin plugin, ConfigManager config, InfectionStore store) {
        this.plugin = plugin;
        this.config = config;
        this.store = store;
        this.immunityKey = new NamespacedKey("ultra_advanced_zombie_apocalypse", "infection_immunity_until");
        store.load().whenComplete((loadedRecords, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                plugin.getLogger().severe("Infection data load failed: " + error.getMessage());
                return;
            }
            loadedRecords.forEach((playerId, record) -> {
                if (!clearedBeforeLoad.contains(playerId)) {
                    records.putIfAbsent(playerId, record);
                }
            });
            loaded = true;
            clearedBeforeLoad.clear();
            if (pendingSave) {
                pendingSave = false;
                store.save(records);
            }
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        }));
    }

    private void tick() {
        if (!loaded || !config.getBoolean("infection.enabled", true)) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Player player : Bukkit.getOnlinePlayers()) {
            InfectionRecord record = records.get(player.getUniqueId());
            if (record == null) {
                continue;
            }
            if (record.stage() == InfectionStage.INFECTED
                    && now - record.infectedAtMillis() >= config.getLong("infection.infected-after-seconds", 900) * 1000L) {
                record = new InfectionRecord(record.playerId(), InfectionStage.CRITICAL, record.infectedAtMillis(), now);
                records.put(player.getUniqueId(), record);
                player.sendMessage("&cYour infection has become critical.");
                changed = true;
            }
            long lethalSeconds = config.getLong("infection.critical-death-after-seconds", 0);
            if (record.stage() == InfectionStage.CRITICAL && lethalSeconds > 0 && record.criticalAtMillis() > 0
                    && now - record.criticalAtMillis() >= lethalSeconds * 1000L) {
                infectionDeaths.add(player.getUniqueId());
                player.setHealth(0.0);
            }
        }
        if (changed) {
            save();
        }
    }

    public void infect(Player player) {
        if (!config.getBoolean("infection.enabled", true) || records.containsKey(player.getUniqueId()) || isImmune(player)) {
            return;
        }
        long now = System.currentTimeMillis();
        records.put(player.getUniqueId(), new InfectionRecord(player.getUniqueId(), InfectionStage.INFECTED, now, 0));
        player.sendMessage("&cYou have been infected.");
        save();
    }

    public void setStage(Player player, InfectionStage stage) {
        if (stage == InfectionStage.HEALTHY) {
            cure(player);
            return;
        }
        long now = System.currentTimeMillis();
        long infectedAt = stage == InfectionStage.CRITICAL
                ? now - config.getLong("infection.infected-after-seconds", 900) * 1000L : now;
        long criticalAt = stage == InfectionStage.CRITICAL ? now : 0;
        records.put(player.getUniqueId(), new InfectionRecord(player.getUniqueId(), stage, infectedAt, criticalAt));
        save();
    }

    public void cure(Player player) {
        records.remove(player.getUniqueId());
        if (!loaded) {
            clearedBeforeLoad.add(player.getUniqueId());
        }
        infectionDeaths.remove(player.getUniqueId());
        long immunitySeconds = config.getLong("infection.immunity-after-cure-seconds", 0);
        if (immunitySeconds > 0) {
            grantImmunity(player, immunitySeconds);
        }
        player.sendMessage("&aInfection cleared.");
        save();
    }

    public boolean reduceStage(Player player) {
        InfectionStage stage = getStage(player.getUniqueId());
        if (stage == InfectionStage.CRITICAL) {
            setStage(player, InfectionStage.INFECTED);
            return true;
        }
        if (stage == InfectionStage.INFECTED) {
            cure(player);
            return true;
        }
        return false;
    }

    public void grantImmunity(Player player, long seconds) {
        if (seconds <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        long durationMillis = Math.min(seconds, 31536000L) * 1000L;
        long existingExpiry = player.getPersistentDataContainer().getOrDefault(immunityKey, PersistentDataType.LONG, 0L);
        player.getPersistentDataContainer().set(immunityKey, PersistentDataType.LONG, Math.max(existingExpiry, now + durationMillis));
    }

    public boolean isImmune(Player player) {
        Long expiry = player.getPersistentDataContainer().get(immunityKey, PersistentDataType.LONG);
        if (expiry == null) {
            return false;
        }
        if (expiry > System.currentTimeMillis()) {
            return true;
        }
        player.getPersistentDataContainer().remove(immunityKey);
        return false;
    }

    public InfectionStage getStage(UUID playerId) {
        InfectionRecord record = records.get(playerId);
        return record == null ? InfectionStage.HEALTHY : record.stage();
    }

    public boolean consumeInfectionDeath(Player player) {
        if (!infectionDeaths.remove(player.getUniqueId())) {
            return false;
        }
        records.remove(player.getUniqueId());
        if (!loaded) {
            clearedBeforeLoad.add(player.getUniqueId());
        }
        save();
        return true;
    }

    public boolean isLoaded() {
        return loaded;
    }

    private void save() {
        if (loaded) {
            store.save(records);
        } else {
            pendingSave = true;
        }
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
        }
        if (loaded) {
            store.save(records);
        }
    }
}