package dev.kodari.zombieapocalypse.npc;

import dev.kodari.zombieapocalypse.ZombieApocalypsePlugin;
import dev.kodari.zombieapocalypse.config.ConfigManager;
import dev.kodari.zombieapocalypse.quest.QuestService;
import dev.kodari.zombieapocalypse.trader.TraderOffer;
import dev.kodari.zombieapocalypse.trader.TraderService;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SurvivorNpcService implements Listener {
    private static final Set<String> ROLES = Set.of("trader", "questgiver", "doctor", "engineer", "guard", "survivor");
    private final ZombieApocalypsePlugin plugin;
    private final ConfigManager config;
    private final TraderService traders;
    private final QuestService quests;
    private final NamespacedKey roleKey;
    private final NamespacedKey idKey;
    private final Map<UUID, Long> doctorCooldowns = new ConcurrentHashMap<>();

    public SurvivorNpcService(ZombieApocalypsePlugin plugin, ConfigManager config, TraderService traders, QuestService quests) {
        this.plugin = plugin;
        this.config = config;
        this.traders = traders;
        this.quests = quests;
        this.roleKey = new NamespacedKey(plugin, "survivor_npc_role");
        this.idKey = new NamespacedKey(plugin, "survivor_npc_id");
    }

    public boolean isValidRole(String role) {
        return ROLES.contains(role.toLowerCase(Locale.ROOT));
    }

    public Villager spawn(String role, String id, String displayName, org.bukkit.Location location) {
        String normalizedRole = role.toLowerCase(Locale.ROOT);
        String normalizedId = id.toLowerCase(Locale.ROOT);
        if (!config.getBoolean("npcs.enabled", true) || !isValidRole(normalizedRole)
                || !normalizedId.matches("[a-z0-9_-]{1,32}")) {
            return null;
        }
        Villager villager = location.getWorld().spawn(location, Villager.class);
        villager.setAI(false);
        villager.setInvulnerable(true);
        villager.setPersistent(true);
        villager.setRemoveWhenFarAway(false);
        villager.setCanPickupItems(false);
        villager.setCustomName(ChatColor.translateAlternateColorCodes('&', displayName));
        villager.setCustomNameVisible(true);
        villager.getPersistentDataContainer().set(roleKey, PersistentDataType.STRING, normalizedRole);
        villager.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, normalizedId);
        return villager;
    }

    public List<NpcInfo> npcs() {
        List<NpcInfo> found = new ArrayList<>();
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof Villager villager)) {
                    continue;
                }
                String role = villager.getPersistentDataContainer().get(roleKey, PersistentDataType.STRING);
                String id = villager.getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
                if (role != null && id != null) {
                    found.add(new NpcInfo(id, role, villager.getCustomName(), world.getName(), villager.getUniqueId()));
                }
            }
        }
        return List.copyOf(found);
    }

    public boolean remove(String id) {
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof Villager villager)) {
                    continue;
                }
                String npcId = villager.getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
                if (id.equalsIgnoreCase(npcId)) {
                    villager.remove();
                    return true;
                }
            }
        }
        return false;
    }

    @EventHandler
    public void onNpcInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Villager villager)) {
            return;
        }
        String role = villager.getPersistentDataContainer().get(roleKey, PersistentDataType.STRING);
        if (role == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (player.isSneaking() && player.hasPermission("zombie.admin")) {
            villager.remove();
            message(player, "&aRemoved survivor NPC.");
            return;
        }
        if (!config.getBoolean("npcs.enabled", true)) {
            message(player, "&cSurvivor NPCs are currently disabled.");
            return;
        }
        switch (role) {
            case "trader" -> showTrader(player);
            case "questgiver" -> quests.show(player);
            case "doctor" -> heal(player);
            case "engineer" -> message(player, "&eEngineer: check &f/zombie power status &eor use &f/zombie cctv list&e.");
            case "guard" -> message(player, "&7Guard: &fKeep your weapon ready. The infected are never far away.");
            default -> message(player, "&aSurvivor: &fCheck &e/zombie quest list &ffor available work.");
        }
    }

    private void showTrader(Player player) {
        if (!config.getBoolean("trader.enabled", false)) {
            message(player, "&eThis trader is out of supplies. Check back later.");
            return;
        }
        List<TraderOffer> offers = traders.offers();
        if (offers.isEmpty()) {
            message(player, "&eThis trader has no offers configured.");
            return;
        }
        message(player, "&6Trader offers:");
        for (TraderOffer offer : offers) {
            message(player, "&f" + offer.itemId() + " &7- buy " + offer.buyPrice() + ", sell " + offer.sellPrice());
        }
        message(player, "&7Use /ztrade buy <item> [amount] or /ztrade sell <item> [amount].");
    }

    private void heal(Player player) {
        long now = System.currentTimeMillis();
        long readyAt = doctorCooldowns.getOrDefault(player.getUniqueId(), 0L);
        if (readyAt > now) {
            message(player, "&eThe doctor needs &f" + Math.max(1, (readyAt - now + 999) / 1000) + "s &ebefore treating you again.");
            return;
        }
        double amount = config.getDouble("npcs.doctor.heal-amount", 4.0);
        if (player.getHealth() >= player.getMaxHealth()) {
            message(player, "&aYou are already in good health.");
            return;
        }
        player.setHealth(Math.min(player.getMaxHealth(), player.getHealth() + amount));
        doctorCooldowns.put(player.getUniqueId(), now + config.getInt("npcs.doctor.cooldown-seconds", 60) * 1000L);
        message(player, "&aThe doctor treated your wounds.");
    }

    private void message(Player player, String text) {
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', text));
    }

    public record NpcInfo(String id, String role, String name, String world, UUID entityId) {
    }
}