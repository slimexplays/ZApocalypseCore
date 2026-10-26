package dev.kodari.zombieapocalypse.recipe;

import dev.kodari.zombieapocalypse.ZombieApocalypsePlugin;
import dev.kodari.zombieapocalypse.config.ConfigManager;
import dev.kodari.zombieapocalypse.item.CustomItemService;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class RecipeRegistry {
    public enum CraftResult {
        SUCCESS,
        UNKNOWN_RECIPE,
        MISSING_INGREDIENTS,
        INVALID_RESULT
    }

    private final ZombieApocalypsePlugin plugin;
    private final ConfigManager config;
    private final CustomItemService items;
    private volatile Map<String, RecipeDefinition> recipes = Map.of();

    public RecipeRegistry(ZombieApocalypsePlugin plugin, ConfigManager config, CustomItemService items) {
        this.plugin = plugin;
        this.config = config;
        this.items = items;
    }

    public void reload() {
        Map<String, RecipeDefinition> candidate = new LinkedHashMap<>();
        ConfigurationSection section = config.getSection("recipes");
        if (section != null) {
            for (String rawId : section.getKeys(false)) {
                String id = rawId.toLowerCase(Locale.ROOT);
                ConfigurationSection recipe = section.getConfigurationSection(rawId);
                if (recipe == null) {
                    plugin.getLogger().warning("Skipping recipe '" + id + "': definition is not a section.");
                    continue;
                }
                String result = recipe.getString("result", "").toLowerCase(Locale.ROOT);
                int amount = recipe.getInt("amount", 1);
                String station = recipe.getString("station", "hand");
                ConfigurationSection ingredientSection = recipe.getConfigurationSection("ingredients");
                Map<String, Integer> ingredients = new LinkedHashMap<>();
                boolean valid = !id.isBlank() && config.getSection("items." + result) != null
                        && amount >= 1 && amount <= 64 && ingredientSection != null
                        && (config.getInt("items." + result + ".storage-size", 0) <= 0 || amount == 1);
                if (ingredientSection != null) {
                    for (String ingredientId : ingredientSection.getKeys(false)) {
                        String normalizedIngredientId = ingredientId.toLowerCase(Locale.ROOT);
                        int count = ingredientSection.getInt(ingredientId, 0);
                        if (config.getSection("items." + normalizedIngredientId) == null || count < 1 || count > 64
                                || config.getInt("items." + normalizedIngredientId + ".storage-size", 0) > 0) {
                            valid = false;
                            break;
                        }
                        ingredients.put(normalizedIngredientId, count);
                    }
                }
                if (!valid || ingredients.isEmpty() || candidate.containsKey(id)) {
                    plugin.getLogger().warning("Skipping invalid or duplicate recipe '" + id + "'.");
                    continue;
                }
                candidate.put(id, new RecipeDefinition(id, result, amount, station, ingredients));
            }
        }
        recipes = Collections.unmodifiableMap(candidate);
        plugin.getLogger().info("Loaded " + recipes.size() + " survival recipe(s).");
    }

    public List<RecipeDefinition> list() {
        return List.copyOf(recipes.values());
    }

    public RecipeDefinition get(String id) {
        return recipes.get(id.toLowerCase(Locale.ROOT));
    }

    public List<RecipeDefinition> search(String term) {
        String query = term.toLowerCase(Locale.ROOT);
        return recipes.values().stream()
                .filter(recipe -> recipe.id().contains(query) || recipe.resultItemId().contains(query)
                        || recipe.ingredients().keySet().stream().anyMatch(ingredient -> ingredient.contains(query)))
                .toList();
    }

    public List<RecipeDefinition> forItem(String itemId) {
        String id = itemId.toLowerCase(Locale.ROOT);
        return recipes.values().stream().filter(recipe -> recipe.resultItemId().equals(id)).toList();
    }

    public CraftResult craft(Player player, String recipeId) {
        RecipeDefinition recipe = get(recipeId);
        if (recipe == null) {
            return CraftResult.UNKNOWN_RECIPE;
        }
        ItemStack output = items.create(recipe.resultItemId());
        if (output == null) {
            return CraftResult.INVALID_RESULT;
        }
        output.setAmount(recipe.resultAmount());

        for (Map.Entry<String, Integer> ingredient : recipe.ingredients().entrySet()) {
            if (count(player, ingredient.getKey()) < ingredient.getValue()) {
                return CraftResult.MISSING_INGREDIENTS;
            }
        }
        for (Map.Entry<String, Integer> ingredient : recipe.ingredients().entrySet()) {
            remove(player, ingredient.getKey(), ingredient.getValue());
        }
        for (ItemStack overflow : player.getInventory().addItem(output).values()) {
            Location location = player.getLocation();
            location.getWorld().dropItemNaturally(location, overflow);
        }
        return CraftResult.SUCCESS;
    }

    private int count(Player player, String itemId) {
        int total = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && itemId.equals(items.getItemId(item))) {
                total += item.getAmount();
            }
        }
        return total;
    }

    private void remove(Player player, String itemId, int amount) {
        int remaining = amount;
        for (int slot = 0; slot < player.getInventory().getStorageContents().length && remaining > 0; slot++) {
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
    }
}