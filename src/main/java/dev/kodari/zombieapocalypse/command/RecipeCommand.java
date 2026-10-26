package dev.kodari.zombieapocalypse.command;

import com.cryptomorin.xseries.XMaterial;
import dev.kodari.zombieapocalypse.config.ConfigManager;
import dev.kodari.zombieapocalypse.item.CustomItemService;
import dev.kodari.zombieapocalypse.recipe.RecipeDefinition;
import dev.kodari.zombieapocalypse.recipe.RecipeRegistry;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public final class RecipeCommand implements CommandExecutor, TabCompleter, Listener {
    private static final int PAGE_SIZE = 45;
    private final ConfigManager config;
    private final CustomItemService items;
    private final RecipeRegistry recipes;

    public RecipeCommand(ConfigManager config, CustomItemService items, RecipeRegistry recipes) {
        this.config = config;
        this.items = items;
        this.recipes = recipes;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            sender.sendMessage("&eRecipes: &f" + recipes.list().stream().map(RecipeDefinition::id).collect(Collectors.joining(", ")));
            return true;
        }
        String subcommand = args[0].toLowerCase(Locale.ROOT);
        if (subcommand.equals("search")) {
            if (args.length < 2) {
                sender.sendMessage("&cUsage: /zrecipe search <term>");
                return true;
            }
            String term = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
            sender.sendMessage("&eMatches: &f" + recipes.search(term).stream().map(RecipeDefinition::id).collect(Collectors.joining(", ")));
            return true;
        }
        if (subcommand.equals("gui")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("&cThe recipe browser requires a player.");
                return true;
            }
            String query = args.length > 1 ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)) : "";
            open(player, query, 0);
            return true;
        }
        if (subcommand.equals("craft")) {
            if (!(sender instanceof Player player) || args.length != 2) {
                sender.sendMessage("&cUsage: /zrecipe craft <recipe-id> (in game)");
                return true;
            }
            RecipeRegistry.CraftResult result = recipes.craft(player, args[1]);
            switch (result) {
                case SUCCESS -> message(sender, "&aCrafted recipe &f" + args[1] + "&a.");
                case UNKNOWN_RECIPE -> message(sender, "&cUnknown recipe.");
                case MISSING_INGREDIENTS -> message(sender, "&cYou do not have the required custom ingredients.");
                case INVALID_RESULT -> message(sender, "&cThe recipe result item is not configured.");
            }
            return true;
        }
        List<RecipeDefinition> itemRecipes = recipes.forItem(subcommand);
        if (!itemRecipes.isEmpty()) {
            itemRecipes.forEach(recipe -> showRecipe(sender, recipe));
            return true;
        }
        RecipeDefinition recipe = recipes.get(subcommand);
        if (recipe != null) {
            showRecipe(sender, recipe);
        } else {
            message(sender, "&cUnknown recipe or item. Use /zrecipe list or /zrecipe search <term>.");
        }
        return true;
    }

    private void showRecipe(CommandSender sender, RecipeDefinition recipe) {
        String resultName = config.getString("items." + recipe.resultItemId() + ".name", recipe.resultItemId());
        String ingredients = recipe.ingredients().entrySet().stream()
                .map(entry -> entry.getValue() + "x " + entry.getKey())
                .collect(Collectors.joining(", "));
        message(sender, "&e" + recipe.id() + " &7→ &f" + resultName + " x" + recipe.resultAmount()
                + " &7| station: &f" + recipe.station() + " &7| ingredients: &f" + ingredients);
    }

    private void open(Player player, String query, int page) {
        List<RecipeDefinition> matches = query.isBlank() ? recipes.list() : recipes.search(query);
        int pages = Math.max(1, (matches.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int currentPage = Math.max(0, Math.min(page, pages - 1));
        RecipeBrowserHolder holder = new RecipeBrowserHolder();
        holder.setQuery(query);
        holder.setPage(currentPage);
        Inventory inventory = Bukkit.createInventory(holder, 54, ChatColor.DARK_GREEN + "Ultra Advanced Recipes " + (currentPage + 1) + "/" + pages);
        holder.setInventory(inventory);
        int start = currentPage * PAGE_SIZE;
        for (int index = start; index < Math.min(start + PAGE_SIZE, matches.size()); index++) {
            RecipeDefinition recipe = matches.get(index);
            ItemStack icon = items.create(recipe.resultItemId());
            if (icon == null) {
                continue;
            }
            icon.setAmount(recipe.resultAmount());
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GRAY + "Recipe: " + recipe.id());
                lore.add(ChatColor.GRAY + "Click to view ingredients");
                meta.setLore(lore);
                icon.setItemMeta(meta);
            }
            int slot = index - start;
            inventory.setItem(slot, icon);
            holder.recipes().put(slot, recipe.id());
        }
        if (currentPage > 0) {
            inventory.setItem(45, namedItem("ARROW", "&ePrevious page"));
        }
        inventory.setItem(49, namedItem("BARRIER", "&cClose"));
        if (currentPage + 1 < pages) {
            inventory.setItem(53, namedItem("ARROW", "&eNext page"));
        }
        player.openInventory(inventory);
    }

    private ItemStack namedItem(String material, String name) {
        ItemStack item = XMaterial.matchXMaterial(material).map(XMaterial::parseItem).orElse(null);
        if (item == null) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof RecipeBrowserHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() < 0 || event.getRawSlot() >= top.getSize()) {
            return;
        }
        if (event.getRawSlot() == 49) {
            player.closeInventory();
            return;
        }
        String recipeId = holder.recipes().get(event.getRawSlot());
        if (recipeId != null) {
            RecipeDefinition recipe = recipes.get(recipeId);
            if (recipe != null) {
                showRecipe(player, recipe);
            }
            return;
        }
        if (event.getRawSlot() == 45 && holder.page() > 0) {
            open(player, holder.query(), holder.page() - 1);
        } else if (event.getRawSlot() == 53) {
            open(player, holder.query(), holder.page() + 1);
        }
    }

    private void message(CommandSender sender, String text) {
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', text));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> values = new ArrayList<>(List.of("list", "search", "gui", "craft"));
            values.addAll(recipes.list().stream().map(RecipeDefinition::id).toList());
            return filter(values, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("craft")) {
            return filter(recipes.list().stream().map(RecipeDefinition::id).toList(), args[1]);
        }
        return List.of();
    }

    private List<String> filter(List<String> values, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }
}