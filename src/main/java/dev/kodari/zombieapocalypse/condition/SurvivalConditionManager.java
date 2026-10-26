package dev.kodari.zombieapocalypse.condition;

import com.cryptomorin.xseries.XPotion;
import dev.kodari.zombieapocalypse.ZombieApocalypsePlugin;
import dev.kodari.zombieapocalypse.config.ConfigManager;
import dev.kodari.zombieapocalypse.storage.SurvivalConditionStore;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class SurvivalConditionManager {
    private final ZombieApocalypsePlugin plugin;
    private final ConfigManager config;
    private final SurvivalConditionStore store;
    private final Map<UUID, SurvivalConditionRecord> records = new HashMap<>();
    private BukkitTask task;
    private boolean loaded;
    private boolean dirty;
    private long elapsedTicks;
    private long ticksSinceSave;

    public SurvivalConditionManager(ZombieApocalypsePlugin plugin, ConfigManager config, SurvivalConditionStore store) {
        this.plugin = plugin;
        this.config = config;
        this.store = store;
        store.load().whenComplete((loadedRecords, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!plugin.isEnabled()) {
                return;
            }
            if (error != null) {
                plugin.getLogger().severe("Survival condition data load failed: " + error.getMessage());
                return;
            }
            loadedRecords.forEach((playerId, record) -> records.put(playerId, clamp(record)));
            loaded = true;
            for (Player player : Bukkit.getOnlinePlayers()) {
                ensureRecord(player);
            }
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        }));
    }

    private void tick() {
        if (!loaded) {
            return;
        }
        elapsedTicks += 20L;
        ticksSinceSave += 20L;
        int updateInterval = config.getInt("survival.update-interval-ticks", 100);
        if (elapsedTicks >= updateInterval) {
            long intervalTicks = elapsedTicks;
            elapsedTicks = 0;
            updateOnlinePlayers(intervalTicks);
        }
        if (dirty && ticksSinceSave >= config.getInt("survival.save-interval-ticks", 1200)) {
            save();
            ticksSinceSave = 0;
        }
    }

    private void updateOnlinePlayers(long intervalTicks) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!config.getBoolean("survival.enabled", true) || !config.isWorldEnabled(player.getWorld())) {
                continue;
            }
            SurvivalConditionRecord current = ensureRecord(player);
            double thirst = current.thirst();
            double stamina = current.stamina();
            double minutes = intervalTicks / 1200.0;

            if (config.getBoolean("survival.thirst.enabled", false)) {
                double drain = config.getDouble("survival.thirst.drain-per-minute", 1.5);
                if (player.isSprinting()) {
                    drain += config.getDouble("survival.thirst.sprint-drain-per-minute", 1.0);
                }
                thirst = clamp(thirst - drain * minutes, 0, config.getDouble("survival.thirst.max", 100));
                applyThirstEffects(player, thirst, intervalTicks);
            }

            if (config.getBoolean("survival.stamina.enabled", false)) {
                if (player.isSprinting()) {
                    stamina -= config.getDouble("survival.stamina.sprint-drain-per-minute", 25.0) * minutes;
                } else {
                    stamina += config.getDouble("survival.stamina.regeneration-per-minute", 18.0) * minutes;
                }
                stamina = clamp(stamina, 0, config.getDouble("survival.stamina.max", 100));
                if (stamina <= 0 && player.isSprinting()) {
                    player.setSprinting(false);
                }
            }

            if (Double.compare(thirst, current.thirst()) != 0 || Double.compare(stamina, current.stamina()) != 0) {
                records.put(player.getUniqueId(), new SurvivalConditionRecord(player.getUniqueId(), thirst, stamina));
                dirty = true;
            }
            display(player, thirst, stamina);
        }
    }

    private void applyThirstEffects(Player player, double thirst, long intervalTicks) {
        double threshold = config.getDouble("survival.thirst.effect-threshold", 0);
        String effect = config.getString("survival.thirst.effect", "");
        if (thirst <= threshold && !effect.isBlank()) {
            int duration = (int) Math.min(Integer.MAX_VALUE, intervalTicks + 40);
            int amplifier = config.getInt("survival.thirst.effect-amplifier", 0);
            XPotion.matchXPotion(effect)
                    .map(potion -> potion.buildPotionEffect(duration, amplifier))
                    .ifPresent(player::addPotionEffect);
        }
        double damage = config.getDouble("survival.thirst.empty-damage", 0);
        if (thirst <= 0 && damage > 0) {
            player.damage(damage);
        }
    }

    private void display(Player player, double thirst, double stamina) {
        if (!config.getBoolean("survival.display.enabled", true)) {
            return;
        }
        StringBuilder status = new StringBuilder();
        if (config.getBoolean("survival.thirst.enabled", false)) {
            String thirstColor = thirst <= config.getDouble("survival.thirst.low-threshold", 25) ? "&c" : "&b";
            status.append(thirstColor).append("Thirst: ").append(format(thirst)).append('/')
                    .append(format(config.getDouble("survival.thirst.max", 100)));
        }
        if (config.getBoolean("survival.stamina.enabled", false)) {
            if (!status.isEmpty()) {
                status.append(" &8| ");
            }
            status.append("&eStamina: ").append(format(stamina)).append('/').append(format(config.getDouble("survival.stamina.max", 100)));
        }
        if (!status.isEmpty()) {
            String legacy = ChatColor.translateAlternateColorCodes('&', status.toString());
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(legacy));
        }
    }

    private String format(double value) {
        return String.format(Locale.ROOT, "%.0f", value);
    }

    private SurvivalConditionRecord ensureRecord(Player player) {
        return records.computeIfAbsent(player.getUniqueId(), playerId -> {
            dirty = true;
            return new SurvivalConditionRecord(playerId,
                    clamp(config.getDouble("survival.thirst.starting", 100), 0, config.getDouble("survival.thirst.max", 100)),
                    clamp(config.getDouble("survival.stamina.starting", 100), 0, config.getDouble("survival.stamina.max", 100)));
        });
    }

    private SurvivalConditionRecord clamp(SurvivalConditionRecord record) {
        return new SurvivalConditionRecord(record.playerId(),
                clamp(record.thirst(), 0, config.getDouble("survival.thirst.max", 100)),
                clamp(record.stamina(), 0, config.getDouble("survival.stamina.max", 100)));
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public void onJoin(Player player) {
        if (loaded) {
            ensureRecord(player);
        }
    }

    public void onDeath(Player player) {
        if (!loaded || !config.getBoolean("survival.stamina.enabled", false)
                || !config.getBoolean("survival.stamina.reset-on-death", true)) {
            return;
        }
        double max = config.getDouble("survival.stamina.max", 100);
        records.put(player.getUniqueId(), new SurvivalConditionRecord(player.getUniqueId(), getThirst(player.getUniqueId()), max));
        dirty = true;
    }

    public boolean canSprint(Player player) {
        if (!loaded || !config.getBoolean("survival.enabled", true)
                || !config.getBoolean("survival.stamina.enabled", false)) {
            return true;
        }
        return getStamina(player.getUniqueId()) > 0;
    }

    public boolean hydrate(Player player, double amount) {
        if (!loaded || amount <= 0 || !config.getBoolean("survival.enabled", true)
                || !config.getBoolean("survival.thirst.enabled", false)) {
            return false;
        }
        SurvivalConditionRecord current = ensureRecord(player);
        double thirst = clamp(current.thirst() + amount, 0, config.getDouble("survival.thirst.max", 100));
        if (Double.compare(thirst, current.thirst()) != 0) {
            records.put(player.getUniqueId(), new SurvivalConditionRecord(player.getUniqueId(), thirst, current.stamina()));
            dirty = true;
            return true;
        }
        return false;
    }

    public boolean restoreStamina(Player player, double amount) {
        if (!loaded || amount <= 0 || !config.getBoolean("survival.enabled", true)
                || !config.getBoolean("survival.stamina.enabled", false)) {
            return false;
        }
        SurvivalConditionRecord current = ensureRecord(player);
        double stamina = clamp(current.stamina() + amount, 0, config.getDouble("survival.stamina.max", 100));
        if (Double.compare(stamina, current.stamina()) != 0) {
            records.put(player.getUniqueId(), new SurvivalConditionRecord(player.getUniqueId(), current.thirst(), stamina));
            dirty = true;
            return true;
        }
        return false;
    }

    public double getThirst(UUID playerId) {
        SurvivalConditionRecord record = records.get(playerId);
        return record == null ? config.getDouble("survival.thirst.starting", 100) : record.thirst();
    }

    public double getStamina(UUID playerId) {
        SurvivalConditionRecord record = records.get(playerId);
        return record == null ? config.getDouble("survival.stamina.starting", 100) : record.stamina();
    }

    private void save() {
        store.save(records);
        dirty = false;
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
        }
        if (loaded && dirty) {
            save();
        }
    }
}