package dev.kodari.zombieapocalypse.item;

import com.cryptomorin.xseries.XPotion;
import dev.kodari.zombieapocalypse.ZombieApocalypsePlugin;
import dev.kodari.zombieapocalypse.communication.RadioService;
import dev.kodari.zombieapocalypse.condition.SurvivalConditionManager;
import dev.kodari.zombieapocalypse.config.ConfigManager;
import dev.kodari.zombieapocalypse.infection.InfectionManager;
import dev.kodari.zombieapocalypse.infection.InfectionStage;
import dev.kodari.zombieapocalypse.navigation.Waypoint;
import dev.kodari.zombieapocalypse.navigation.WaypointService;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class SurvivalItemService {
    private final ZombieApocalypsePlugin plugin;
    private final ConfigManager config;
    private final InfectionManager infections;
    private final SurvivalConditionManager conditions;
    private final CustomItemService items;
    private final RadioService radio;
    private final WaypointService waypoints;

    public SurvivalItemService(ZombieApocalypsePlugin plugin, ConfigManager config, InfectionManager infections,
                               SurvivalConditionManager conditions, CustomItemService items, RadioService radio,
                               WaypointService waypoints) {
        this.plugin = plugin;
        this.config = config;
        this.infections = infections;
        this.conditions = conditions;
        this.items = items;
        this.radio = radio;
        this.waypoints = waypoints;
    }

    public void handleInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !event.getAction().toString().startsWith("RIGHT_CLICK")) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack held = event.getItem();
        String itemId = items.getItemId(held);
        if (itemId == null) {
            return;
        }

        if (itemId.equals("refillable_bottle") && refillAtPoint(event, player, held)) {
            event.setCancelled(true);
            return;
        }

        if (itemId.equals("contaminated_water") && player.isSneaking()
                && config.getBoolean("survival.water-purification.enabled", true)) {
            event.setCancelled(true);
            if (purify(player, held)) {
                message(player, "&aYou purified the water.");
            } else {
                message(player, "&cPurification requires a water filter and a configured clean-water item.");
            }
            return;
        }

        if (!hasUseBehavior(itemId)) {
            return;
        }
        event.setCancelled(true);
        if (items.isSpoiled(held)) {
            replaceWithSpoiledFood(player, held);
            message(player, "&cThat food has spoiled and cannot be used.");
            return;
        }

        long cooldown = config.getLong("items." + itemId + ".cooldown-seconds", 0);
        NamespacedKey cooldownKey = new NamespacedKey("ultra_advanced_zombie_apocalypse", "use_" + itemId);
        long now = System.currentTimeMillis();
        Long lastUse = player.getPersistentDataContainer().get(cooldownKey, PersistentDataType.LONG);
        if (lastUse != null && cooldown > 0 && now - lastUse < cooldown * 1000L) {
            message(player, "&cYou must wait before using that again.");
            return;
        }

        boolean returnBottle = config.getBoolean("items." + itemId + ".return-empty-bottle", false);
        ItemStack emptyBottle = returnBottle ? items.create("refillable_bottle") : null;
        if (returnBottle && emptyBottle == null) {
            message(player, "&cThe empty bottle item is not configured correctly.");
            return;
        }
        if (!apply(player, itemId, event)) {
            message(player, "&7You cannot use that right now.");
            return;
        }
        if (cooldown > 0) {
            player.getPersistentDataContainer().set(cooldownKey, PersistentDataType.LONG, now);
        }
        if (config.getBoolean("items." + itemId + ".consume-on-use", true)) {
            consume(player, held, emptyBottle);
        }
    }

    private boolean hasUseBehavior(String itemId) {
        String path = "items." + itemId + ".";
        return config.getBoolean(path + "cure", false)
                || config.getBoolean(path + "infection-reduction", false)
                || config.getDouble(path + "immunity-seconds", 0) > 0
                || config.getDouble(path + "heal", 0) > 0
                || config.getInt(path + "food-level", 0) > 0
                || config.getDouble(path + "hydration", 0) > 0
                || config.getDouble(path + "stamina", 0) > 0
                || config.getInt(path + "repair-durability", 0) > 0
                || !config.getString(path + "radio-alert", "").isBlank()
                || !config.getString(path + "navigation-action", "").isBlank()
                || !config.getString(path + "effect", "").isBlank()
                || !config.getString(path + "contamination-effect", "").isBlank();
    }

    private boolean apply(Player player, String itemId, PlayerInteractEvent event) {
        String path = "items." + itemId + ".";
        boolean applied = false;
        double healing = config.getDouble(path + "heal", 0);
        if (healing > 0 && player.getHealth() < player.getMaxHealth()) {
            player.setHealth(Math.min(player.getMaxHealth(), player.getHealth() + healing));
            applied = true;
        }

        int food = config.getInt(path + "food-level", 0);
        if (food > 0 && player.getFoodLevel() < 20) {
            int newFood = Math.min(20, player.getFoodLevel() + food);
            player.setFoodLevel(newFood);
            player.setSaturation(Math.min(newFood, player.getSaturation() + (float) config.getDouble(path + "saturation", 0)));
            applied = true;
        }

        double hydration = config.getDouble(path + "hydration", 0);
        if (hydration > 0) {
            if (conditions.hydrate(player, hydration)
                    || (config.getBoolean("survival.enabled", true)
                    && !config.getBoolean("survival.thirst.enabled", false))) {
                applied = true;
            }
        }
        double stamina = config.getDouble(path + "stamina", 0);
        if (stamina > 0 && conditions.restoreStamina(player, stamina)) {
            applied = true;
        }

        int repairAmount = config.getInt(path + "repair-durability", 0);
        if (repairAmount > 0 && repairOffhand(player, path, repairAmount)) {
            applied = true;
        }

        String alert = config.getString(path + "radio-alert", "");
        if (!alert.isBlank() && radio.broadcast(player, alert) > 0) {
            applied = true;
        }

        String navigationAction = config.getString(path + "navigation-action", "");
        if (navigationAction.equalsIgnoreCase("locate")) {
            waypoints.showLocation(player, player.getLocation());
            applied = true;
        } else if (navigationAction.equalsIgnoreCase("list")) {
            List<Waypoint> playerWaypoints = waypoints.list(player);
            if (playerWaypoints.isEmpty()) {
                message(player, "&7No saved waypoints.");
            } else {
                for (Waypoint waypoint : playerWaypoints) {
                    message(player, "&e" + waypoint.id() + " &7— &f" + waypoint.world() + " ["
                            + waypoint.x() + ", " + waypoint.y() + ", " + waypoint.z() + "]");
                }
            }
            applied = true;
        } else if (navigationAction.equalsIgnoreCase("mark")) {
            Location marker = event.getClickedBlock() == null
                    ? player.getLocation() : event.getClickedBlock().getLocation().add(0.5, 0.5, 0.5);
            String markerId = "marker_" + System.currentTimeMillis();
            if (waypoints.save(player, markerId, marker)) {
                message(player, "&aWaypoint saved as &f" + markerId + "&a.");
                applied = true;
            }
        }

        if (items.isCureItem(itemId) && infections.getStage(player.getUniqueId()) != InfectionStage.HEALTHY) {
            infections.cure(player);
            applied = true;
        }
        if (config.getBoolean(path + "infection-reduction", false) && infections.reduceStage(player)) {
            applied = true;
        }
        long immunitySeconds = config.getLong(path + "immunity-seconds", 0);
        if (immunitySeconds > 0) {
            infections.grantImmunity(player, immunitySeconds);
            applied = true;
        }

        String effect = config.getString(path + "effect", "");
        if (!effect.isBlank() && applyEffect(player, effect, path)) {
            applied = true;
        }
        String contaminationEffect = config.getString(path + "contamination-effect", "");
        double contaminationChance = config.getDouble(path + "contamination-chance", 1.0);
        if (!contaminationEffect.isBlank() && ThreadLocalRandom.current().nextDouble() < contaminationChance
                && applyEffect(player, contaminationEffect, path)) {
            applied = true;
        }
        return applied;
    }

    private boolean repairOffhand(Player player, String path, int repairAmount) {
        return repair(player, repairAmount, config.getString(path + "repair-cost-item", ""),
                config.getInt(path + "repair-cost-amount", 0));
    }

    public boolean repair(Player player, int repairAmount, String costItem, int costAmount) {
        if (repairAmount <= 0 || costAmount < 0) {
            return false;
        }
        ItemStack target = player.getInventory().getItemInOffHand();
        if (target == null || !target.hasItemMeta() || !(target.getItemMeta() instanceof Damageable damageable)
                || damageable.getDamage() <= 0) {
            return false;
        }
        if (!costItem.isBlank() && costAmount > 0 && !removeCustomItems(player, costItem, costAmount)) {
            return false;
        }
        damageable.setDamage(Math.max(0, damageable.getDamage() - repairAmount));
        target.setItemMeta(damageable);
        message(player, "&aRepaired the off-hand item.");
        return true;
    }

    private boolean removeCustomItems(Player player, String itemId, int amount) {
        ItemStack[] contents = player.getInventory().getStorageContents();
        int available = 0;
        for (ItemStack item : contents) {
            if (item != null && itemId.equals(items.getItemId(item))) {
                available += item.getAmount();
            }
        }
        if (available < amount) {
            return false;
        }
        int remaining = amount;
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (item == null || !itemId.equals(items.getItemId(item))) {
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
        return true;
    }

    private boolean applyEffect(Player player, String effect, String path) {
        int duration = config.getInt(path + "effect-duration-ticks", 100);
        int amplifier = config.getInt(path + "effect-amplifier", 0);
        return XPotion.matchXPotion(effect)
                .map(potion -> potion.buildPotionEffect(duration, amplifier))
                .map(player::addPotionEffect)
                .orElse(false);
    }

    private void consume(Player player, ItemStack held, ItemStack emptyBottle) {
        if (held.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(emptyBottle);
            return;
        }
        held.setAmount(held.getAmount() - 1);
        if (emptyBottle != null) {
            give(player, emptyBottle);
        }
    }

    private void replaceWithSpoiledFood(Player player, ItemStack held) {
        ItemStack spoiled = items.create("spoiled_food");
        if (spoiled == null) {
            return;
        }
        spoiled.setAmount(held.getAmount());
        player.getInventory().setItemInMainHand(spoiled);
    }

    private boolean refillAtPoint(PlayerInteractEvent event, Player player, ItemStack bottle) {
        if (!config.getBoolean("survival.water-points.enabled", true) || event.getClickedBlock() == null) {
            return false;
        }
        String sourceType = sourceAt(event.getClickedBlock());
        if (sourceType == null) {
            return false;
        }
        String filledId = sourceType.equalsIgnoreCase("clean") ? "clean_water" : "contaminated_water";
        ItemStack filled = items.create(filledId);
        if (filled == null) {
            message(player, "&cThe configured water item is missing.");
            return true;
        }
        if (bottle.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(filled);
        } else {
            bottle.setAmount(bottle.getAmount() - 1);
            give(player, filled);
        }
        return true;
    }

    private String sourceAt(Block block) {
        for (String entry : config.getStringList("survival.water-points.locations")) {
            String[] fields = entry.split(",", -1);
            if (fields.length != 5) {
                continue;
            }
            try {
                if (block.getWorld().getName().equalsIgnoreCase(fields[0].trim())
                        && block.getX() == Integer.parseInt(fields[1].trim())
                        && block.getY() == Integer.parseInt(fields[2].trim())
                        && block.getZ() == Integer.parseInt(fields[3].trim())
                        && (fields[4].trim().equalsIgnoreCase("clean") || fields[4].trim().equalsIgnoreCase("contaminated"))) {
                    return fields[4].trim();
                }
            } catch (NumberFormatException ignored) {
                continue;
            }
        }
        return null;
    }

    private boolean purify(Player player, ItemStack water) {
        ItemStack cleanWater = items.create("clean_water");
        if (cleanWater == null || !removeOne(player, "water_filter")) {
            return false;
        }
        if (water.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(cleanWater);
        } else {
            water.setAmount(water.getAmount() - 1);
            give(player, cleanWater);
        }
        return true;
    }

    private boolean removeOne(Player player, String itemId) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (item == null || !itemId.equals(items.getItemId(item))) {
                continue;
            }
            if (item.getAmount() <= 1) {
                player.getInventory().setItem(slot, null);
            } else {
                item.setAmount(item.getAmount() - 1);
            }
            return true;
        }
        return false;
    }

    private void give(Player player, ItemStack item) {
        for (ItemStack overflow : player.getInventory().addItem(item).values()) {
            Location location = player.getLocation();
            location.getWorld().dropItemNaturally(location, overflow);
        }
    }

    private void message(Player player, String text) {
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', text));
    }
}