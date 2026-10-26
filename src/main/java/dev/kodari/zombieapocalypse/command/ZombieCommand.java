package dev.kodari.zombieapocalypse.command;

import dev.kodari.zombieapocalypse.ZombieApocalypsePlugin;
import dev.kodari.zombieapocalypse.config.ConfigManager;
import dev.kodari.zombieapocalypse.infection.InfectionManager;
import dev.kodari.zombieapocalypse.infection.InfectionStage;
import dev.kodari.zombieapocalypse.regional.RegionalInfectionService;
import dev.kodari.zombieapocalypse.recipe.RecipeRegistry;
import dev.kodari.zombieapocalypse.trader.TraderService;
import dev.kodari.zombieapocalypse.zombie.ZombieService;
import dev.kodari.zombieapocalypse.zombie.ZombieVariant;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public final class ZombieCommand implements CommandExecutor, TabCompleter {
    private final ZombieApocalypsePlugin plugin;
    private final ConfigManager config;
    private final InfectionManager infections;
    private final ZombieService zombies;
    private final RecipeRegistry recipes;
    private final TraderService traders;
    private final RegionalInfectionService regionalInfection;

    public ZombieCommand(ZombieApocalypsePlugin plugin, ConfigManager config, InfectionManager infections, ZombieService zombies,
                        RecipeRegistry recipes, TraderService traders, RegionalInfectionService regionalInfection) {
        this.plugin = plugin;
        this.config = config;
        this.infections = infections;
        this.zombies = zombies;
        this.recipes = recipes;
        this.traders = traders;
        this.regionalInfection = regionalInfection;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendBrandedMessage(sender, "&6ZApocalypseCore &7| &fMade By @slimexplays");
            sendBrandedMessage(sender, "&7SlimeX Development &8| &bhttps://dsc.gg/slimexdev");
            sendBrandedMessage(sender, "&a/zombie info|about|version|help|status|reload|spawn <variant> [amount]|infection <player> <status|set <stage>|clear>|region <list|status|set|clear>|cure <player>|item <player> <id> [amount]|event status|debug");
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("info")) {
            sendBrandedMessage(sender, "&8&m----------------------------------------");
            sendBrandedMessage(sender, "&6&lZApocalypseCore");
            sendBrandedMessage(sender, "&7A feature-rich zombie survival experience.");
            sendBrandedMessage(sender, "&fCreator: &e slimexplays &8| &fOrganization: &eSlimeX Development");
            sendBrandedMessage(sender, "&fSupport: &bhttps://dsc.gg/slimexdev");
            sendBrandedMessage(sender, "&7Commands: &f/zombie about &8| &f/zombie version &8| &f/zombie help");
            sendBrandedMessage(sender, "&8&m----------------------------------------");
            return true;
        }
        if (sub.equals("about")) {
            sendBrandedMessage(sender, "&6ZApocalypseCore &7— &fMade By @slimexplays");
            sendBrandedMessage(sender, "&7SlimeX Development &8| &bhttps://dsc.gg/slimexdev");
            return true;
        }
        if (sub.equals("version")) {
            sendBrandedMessage(sender, "&6ZApocalypseCore &7v" + plugin.getDescription().getVersion());
            sendBrandedMessage(sender, "&7Server: &f" + Bukkit.getBukkitVersion());
            return true;
        }
        if (sub.equals("status")) {
            sender.sendMessage("&eEnabled: " + config.getBoolean("general.enabled", true)
                    + " &7| tracked custom zombies: " + zombies.trackedCount());
            return true;
        }
        if (sub.equals("event") && args.length == 2 && args[1].equalsIgnoreCase("status")) {
            sender.sendMessage("&eEvent engine: Phase 1 status foundation only; no event is active.");
            return true;
        }
        if (!sender.hasPermission("zombie.admin")) {
            sender.sendMessage("&cYou do not have permission.");
            return true;
        }
        switch (sub) {
            case "reload" -> {
                boolean reloaded = config.reload();
                if (reloaded) {
                    recipes.reload();
                    traders.reload();
                    regionalInfection.reload();
                }
                sender.sendMessage(reloaded ? "&aConfiguration validated and reloaded." : "&cReload rejected; active settings were not changed.");
            }
            case "debug" -> sender.sendMessage("&eDebug diagnostics: " + (plugin.toggleDebug() ? "&aenabled" : "&cdisabled"));
            case "spawn" -> spawn(sender, args);
            case "item" -> giveItem(sender, args);
            case "cure" -> cure(sender, args);
            case "infection" -> infection(sender, args);
            case "region" -> region(sender, args);
            default -> sender.sendMessage("&cUnknown subcommand. Use /zombie help.");
        }
        return true;
    }

    private void spawn(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("&cThis command requires an in-game player.");
            return;
        }
        if (args.length < 2 || args.length > 3) {
            sender.sendMessage("&cUsage: /zombie spawn <variant> [amount]");
            return;
        }
        ZombieVariant variant = ZombieVariant.fromId(args[1]).orElse(null);
        if (variant == null || variant == ZombieVariant.REVENANT) {
            sender.sendMessage("&cUnknown or reserved variant.");
            return;
        }
        int amount = 1;
        if (args.length == 3) {
            try {
                amount = Integer.parseInt(args[2]);
            } catch (NumberFormatException exception) {
                sender.sendMessage("&cAmount must be a whole number.");
                return;
            }
        }
        if (amount < 1 || amount > config.getInt("general.max-spawn-per-command", 10)) {
            sender.sendMessage("&cAmount is outside the configured safe range.");
            return;
        }
        int spawned = 0;
        for (int i = 0; i < amount; i++) {
            if (zombies.spawn(player.getLocation(), variant) == null) {
                break;
            }
            spawned++;
        }
        sender.sendMessage("&aSpawned " + spawned + " " + variant.id() + " zombie(s).");
    }

    private void cure(CommandSender sender, String[] args) {
        Player target = args.length == 2 ? Bukkit.getPlayerExact(args[1]) : null;
        if (target == null) {
            sender.sendMessage("&cUsage: /zombie cure <online-player>");
            return;
        }
        infections.cure(target);
        sender.sendMessage("&aCleared infection for " + target.getName() + ".");
    }

    private void giveItem(CommandSender sender, String[] args) {
        if (args.length < 3 || args.length > 4) {
            sender.sendMessage("&cUsage: /zombie item <online-player> <item-id> [amount]");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        String itemId = args[2].toLowerCase(Locale.ROOT);
        if (target == null || config.getSection("items." + itemId) == null) {
            sender.sendMessage("&cPlayer must be online and item ID must be configured.");
            return;
        }
        int amount = 1;
        if (args.length == 4) {
            try {
                amount = Integer.parseInt(args[3]);
            } catch (NumberFormatException exception) {
                sender.sendMessage("&cAmount must be a whole number.");
                return;
            }
        }
        if (amount < 1 || amount > 64) {
            sender.sendMessage("&cAmount must be between 1 and 64.");
            return;
        }
        int remaining = amount;
        while (remaining > 0) {
            ItemStack item = plugin.items().create(itemId);
            if (item == null) {
                sender.sendMessage("&cCould not create that custom item.");
                return;
            }
            int stackAmount = Math.min(remaining, item.getMaxStackSize());
            item.setAmount(stackAmount);
            remaining -= stackAmount;
            for (ItemStack overflow : target.getInventory().addItem(item).values()) {
                target.getWorld().dropItemNaturally(target.getLocation(), overflow);
            }
        }
        sender.sendMessage("&aGave " + amount + " " + itemId + " to " + target.getName() + ".");
    }

    private void infection(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("&cUsage: /zombie infection <player> <status|clear|set <healthy|infected|critical>>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage("&cPlayer must be online.");
            return;
        }
        String action = args[2].toLowerCase(Locale.ROOT);
        if (action.equals("status")) {
            sender.sendMessage("&e" + target.getName() + " infection: " + infections.getStage(target.getUniqueId()).name());
        } else if (action.equals("clear")) {
            infections.cure(target);
        } else if (action.equals("set") && args.length == 4) {
            try {
                infections.setStage(target, InfectionStage.valueOf(args[3].toUpperCase(Locale.ROOT)));
                sender.sendMessage("&aUpdated infection for " + target.getName() + ".");
            } catch (IllegalArgumentException exception) {
                sender.sendMessage("&cStage must be healthy, infected, or critical.");
            }
        } else {
            sender.sendMessage("&cUsage: /zombie infection <player> <status|clear|set <stage>>");
        }
    }

    private void region(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("&cUsage: /zombie region <list|status <id>|set <id> <0-100>|clear <id>>");
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("list") && args.length == 2) {
            List<RegionalInfectionService.RegionStatus> regions = regionalInfection.regions();
            if (regions.isEmpty()) {
                sender.sendMessage("&eNo regional infection regions are configured.");
                return;
            }
            for (RegionalInfectionService.RegionStatus status : regions) {
                sender.sendMessage("&e" + status.id() + " &7(" + status.world() + "): "
                        + String.format(Locale.ROOT, "%.1f", status.level()) + " &7- " + status.type());
            }
            return;
        }
        if (action.equals("status") && args.length == 3) {
            sendRegionStatus(sender, args[2]);
            return;
        }
        if (action.equals("set") && args.length == 4) {
            double level;
            try {
                level = Double.parseDouble(args[3]);
            } catch (NumberFormatException exception) {
                sender.sendMessage("&cRegion level must be a number from 0 to 100.");
                return;
            }
            if (!Double.isFinite(level) || level < 0 || level > 100) {
                sender.sendMessage("&cRegion level must be between 0 and 100.");
                return;
            }
            if (!regionalInfection.setLevel(args[2], level)) {
                sender.sendMessage("&cUnknown region or regional infection data is still loading.");
                return;
            }
            sender.sendMessage("&aUpdated regional infection for " + args[2] + ".");
            sendRegionStatus(sender, args[2]);
            return;
        }
        if (action.equals("clear") && args.length == 3) {
            if (!regionalInfection.setLevel(args[2], 0)) {
                sender.sendMessage("&cUnknown region or regional infection data is still loading.");
                return;
            }
            sender.sendMessage("&aCleared regional infection for " + args[2] + ".");
            sendRegionStatus(sender, args[2]);
            return;
        }
        sender.sendMessage("&cUsage: /zombie region <list|status <id>|set <id> <0-100>|clear <id>>");
    }

    private void sendRegionStatus(CommandSender sender, String id) {
        RegionalInfectionService.RegionStatus status = regionalInfection.region(id);
        if (status == null) {
            sender.sendMessage("&cUnknown configured region: " + id);
            return;
        }
        sender.sendMessage("&e" + status.id() + " &7(" + status.world() + "): "
                + String.format(Locale.ROOT, "%.1f", status.level()) + " &7- " + status.type());
    }

    private void sendBrandedMessage(CommandSender sender, String message) {
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("info", "about", "version", "help", "status", "reload", "spawn", "infection", "region", "cure", "item", "event", "debug"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("spawn")) {
            return filter(java.util.Arrays.stream(ZombieVariant.values()).filter(variant -> variant != ZombieVariant.REVENANT)
                    .map(ZombieVariant::id).toList(), args[1]);
        }
        if (args.length == 2 && List.of("cure", "infection").contains(args[0].toLowerCase(Locale.ROOT))) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("item")) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("item")) {
            var section = config.getSection("items");
            return section == null ? List.of() : filter(new ArrayList<>(section.getKeys(false)), args[2]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("infection")) {
            return filter(List.of("status", "set", "clear"), args[2]);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("infection") && args[2].equalsIgnoreCase("set")) {
            return filter(List.of("healthy", "infected", "critical"), args[3]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("event")) {
            return filter(List.of("status"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("region")) {
            return filter(List.of("list", "status", "set", "clear"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("region")
                && List.of("status", "set", "clear").contains(args[1].toLowerCase(Locale.ROOT))) {
            return filter(regionalInfection.regions().stream().map(RegionalInfectionService.RegionStatus::id).toList(), args[2]);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("region") && args[1].equalsIgnoreCase("set")) {
            return filter(List.of("0", "25", "50", "75", "100"), args[3]);
        }
        return new ArrayList<>();
    }

    private List<String> filter(List<String> values, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower)).collect(Collectors.toList());
    }
}