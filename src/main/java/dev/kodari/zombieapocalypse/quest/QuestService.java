package dev.kodari.zombieapocalypse.quest;

import dev.kodari.zombieapocalypse.ZombieApocalypsePlugin;
import dev.kodari.zombieapocalypse.config.ConfigManager;
import dev.kodari.zombieapocalypse.item.CustomItemService;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class QuestService implements Listener, AutoCloseable {
    private final ZombieApocalypsePlugin plugin;
    private final ConfigManager config;
    private final CustomItemService items;
    private final File dataFile;
    private final Map<UUID, Map<String, Progress>> progress = new HashMap<>();

    public QuestService(ZombieApocalypsePlugin plugin, ConfigManager config, CustomItemService items) {
        this.plugin = plugin;
        this.config = config;
        this.items = items;
        this.dataFile = new File(plugin.getDataFolder(), "quests.yml");
        load();
    }

    public List<Quest> quests() {
        ConfigurationSection definitions = config.getSection("quests.definitions");
        if (definitions == null) {
            return List.of();
        }
        List<Quest> quests = new ArrayList<>();
        for (String id : definitions.getKeys(false)) {
            ConfigurationSection section = definitions.getConfigurationSection(id);
            if (section == null) {
                continue;
            }
            quests.add(new Quest(id, section.getString("name", id), section.getString("description", ""),
                    section.getInt("target", 1), section.getString("reward-item", ""),
                    section.getInt("reward-amount", 1)));
        }
        return List.copyOf(quests);
    }

    public void show(Player player) {
        if (!config.getBoolean("quests.enabled", true)) {
            message(player, "&cQuests are currently disabled.");
            return;
        }
        List<Quest> available = quests();
        if (available.isEmpty()) {
            message(player, "&eNo quests are configured.");
            return;
        }
        message(player, "&6Available survivor quests:");
        Map<String, Progress> playerProgress = progress.getOrDefault(player.getUniqueId(), Map.of());
        for (Quest quest : available) {
            Progress state = playerProgress.get(quest.id());
            String status = state == null ? "&7available" : state.claimed() ? "&aclaimed"
                    : "&e" + Math.min(state.kills(), quest.target()) + "/" + quest.target() + " kills";
            message(player, "&f" + quest.id() + " &7- " + quest.name() + " &8[" + status + "&8] &7- " + quest.description());
        }
        message(player, "&7Use /zombie quest accept <id>, status, or claim <id>.");
    }

    public boolean accept(Player player, String id) {
        if (!config.getBoolean("quests.enabled", true)) {
            message(player, "&cQuests are currently disabled.");
            return false;
        }
        Quest quest = quest(id);
        if (quest == null) {
            message(player, "&cUnknown quest.");
            return false;
        }
        Map<String, Progress> playerProgress = progress.computeIfAbsent(player.getUniqueId(), ignored -> new LinkedHashMap<>());
        if (playerProgress.containsKey(quest.id())) {
            message(player, "&eYou already accepted or completed that quest.");
            return false;
        }
        playerProgress.put(quest.id(), new Progress(0, false));
        save();
        message(player, "&aQuest accepted: &f" + quest.name() + "&a. " + quest.description());
        return true;
    }

    public void status(Player player) {
        Map<String, Progress> playerProgress = progress.get(player.getUniqueId());
        if (playerProgress == null || playerProgress.isEmpty()) {
            message(player, "&eYou have no active quests. Use /zombie quest list.");
            return;
        }
        for (Quest quest : quests()) {
            Progress state = playerProgress.get(quest.id());
            if (state == null) {
                continue;
            }
            message(player, "&f" + quest.name() + "&7: " + (state.claimed() ? "&aclaimed"
                    : "&e" + Math.min(state.kills(), quest.target()) + "/" + quest.target() + " zombie kills"));
        }
    }

    public boolean claim(Player player, String id) {
        if (!config.getBoolean("quests.enabled", true)) {
            message(player, "&cQuests are currently disabled.");
            return false;
        }
        Quest quest = quest(id);
        Progress state = progress.getOrDefault(player.getUniqueId(), Map.of()).get(id.toLowerCase(Locale.ROOT));
        if (quest == null || state == null) {
            message(player, "&cYou have not accepted that quest.");
            return false;
        }
        if (state.claimed()) {
            message(player, "&eThat quest reward has already been claimed.");
            return false;
        }
        if (state.kills() < quest.target()) {
            message(player, "&cQuest incomplete: " + state.kills() + "/" + quest.target() + " zombie kills.");
            return false;
        }
        List<ItemStack> rewards = new ArrayList<>();
        for (int index = 0; index < quest.rewardAmount(); index++) {
            ItemStack reward = items.create(quest.rewardItem());
            if (reward == null) {
                message(player, "&cThe configured quest reward could not be created.");
                return false;
            }
            rewards.add(reward);
        }
        progress.get(player.getUniqueId()).put(quest.id(), new Progress(state.kills(), true));
        save();
        for (ItemStack reward : rewards) {
            for (ItemStack overflow : player.getInventory().addItem(reward).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), overflow);
            }
        }
        message(player, "&aClaimed &f" + quest.rewardAmount() + " " + quest.rewardItem() + " &afor " + quest.name() + ".");
        return true;
    }

    private Quest quest(String id) {
        String normalized = id.toLowerCase(Locale.ROOT);
        return quests().stream().filter(quest -> quest.id().equals(normalized)).findFirst().orElse(null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onZombieDeath(EntityDeathEvent event) {
        if (!config.getBoolean("quests.enabled", true) || !(event.getEntity() instanceof Zombie zombie)
                || zombie.getKiller() == null || !config.isWorldEnabled(zombie.getWorld())) {
            return;
        }
        UUID playerId = zombie.getKiller().getUniqueId();
        Map<String, Progress> playerProgress = progress.get(playerId);
        if (playerProgress == null || playerProgress.isEmpty()) {
            return;
        }
        Player player = zombie.getKiller();
        boolean changed = false;
        for (Quest quest : quests()) {
            Progress state = playerProgress.get(quest.id());
            if (state == null || state.claimed() || state.kills() >= quest.target()) {
                continue;
            }
            int kills = state.kills() + 1;
            playerProgress.put(quest.id(), new Progress(kills, false));
            changed = true;
            if (kills >= quest.target()) {
                message(player, "&aQuest complete: &f" + quest.name() + "&a. Use /zombie quest claim " + quest.id() + ".");
            }
        }
        if (changed) {
            save();
        }
    }

    private void load() {
        if (!dataFile.exists()) {
            return;
        }
        YamlConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection players = data.getConfigurationSection("players");
        if (players == null) {
            return;
        }
        for (String rawUuid : players.getKeys(false)) {
            UUID playerId;
            try {
                playerId = UUID.fromString(rawUuid);
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Ignoring invalid quest-progress UUID '" + rawUuid + "'.");
                continue;
            }
            ConfigurationSection playerSection = players.getConfigurationSection(rawUuid + ".quests");
            if (playerSection == null) {
                continue;
            }
            Map<String, Progress> playerProgress = new LinkedHashMap<>();
            for (String id : playerSection.getKeys(false)) {
                ConfigurationSection entry = playerSection.getConfigurationSection(id);
                if (entry != null && id.matches("[a-z0-9_-]{1,32}")) {
                    playerProgress.put(id, new Progress(Math.max(0, entry.getInt("kills")), entry.getBoolean("claimed", false)));
                }
            }
            if (!playerProgress.isEmpty()) {
                progress.put(playerId, playerProgress);
            }
        }
    }

    private void save() {
        YamlConfiguration data = new YamlConfiguration();
        data.set("schema", 1);
        for (Map.Entry<UUID, Map<String, Progress>> playerEntry : progress.entrySet()) {
            for (Map.Entry<String, Progress> questEntry : playerEntry.getValue().entrySet()) {
                String path = "players." + playerEntry.getKey() + ".quests." + questEntry.getKey() + ".";
                data.set(path + "kills", questEntry.getValue().kills());
                data.set(path + "claimed", questEntry.getValue().claimed());
            }
        }
        try {
            dataFile.getParentFile().mkdirs();
            data.save(dataFile);
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not save quest progress: " + exception.getMessage());
        }
    }

    private void message(Player player, String text) {
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', text));
    }

    @Override
    public void close() {
        save();
    }

    public record Quest(String id, String name, String description, int target, String rewardItem, int rewardAmount) {
    }

    private record Progress(int kills, boolean claimed) {
    }
}