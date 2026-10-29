package dev.kodari.zombieapocalypse.command;

import dev.kodari.zombieapocalypse.ZombieApocalypsePlugin;
import dev.kodari.zombieapocalypse.config.ConfigManager;
import dev.kodari.zombieapocalypse.device.PowerCctvService;
import dev.kodari.zombieapocalypse.infection.InfectionManager;
import dev.kodari.zombieapocalypse.infection.InfectionStage;
import dev.kodari.zombieapocalypse.npc.SurvivorNpcService;
import dev.kodari.zombieapocalypse.quest.QuestService;
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
import org.bukkit.block.Block;
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
            sendBrandedMessage(sender, "&a/zombie info|about|version|help|status|reload|spawn <variant> [amount]|infection <player> <status|set <stage>|clear>|region <list|status|set|clear>|cure <player>|item <player> <id> [amount]|event <status|list|start <id>|stop>|device|power|cctv|npc|quest|debug");
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("info")) {
            sendBrandedMessage(sender, "&8&m----------------------------------------");
            sendBrandedMessage(sender, "&6&lZApocalypseCore");
            sendBrandedMessage(sender, "&7A feature-rich zombie survival experience.");
            sendBrandedMessage(sender, "&fCreator: &e slimexplays &8| &fOrganization: &eSlimeX Development");
            sendBrandedMessage(sender, "&fSupport: &bhttps://dsc.gg/slimexdev");
            sendBrandedMessage(sender, "&7Commands: &f/zombie event status|list|start|stop &8| &f/zombie about &8| &f/zombie version");
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
        if (sub.equals("event")) {
            event(sender, args);
            return true;
        }
        if (sub.equals("cctv")) {
            cctv(sender, args);
            return true;
        }
        if (sub.equals("quest")) {
            quest(sender, args);
            return true;
        }
        if (sub.equals("power") && args.length == 2 && args[1].equalsIgnoreCase("status")) {
            powerStatus(sender);
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
            case "device" -> device(sender, args);
            case "npc" -> npc(sender, args);
            case "power" -> powerStatus(sender);
            default -> sender.sendMessage("&cUnknown subcommand. Use /zombie help.");
        }
        return true;
    }

    private void event(CommandSender sender, String[] args) {
        if (args.length == 2 && args[1].equalsIgnoreCase("status")) {
            sendBrandedMessage(sender, plugin.events().status());
            return;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("list")) {
            sendBrandedMessage(sender, "&eAvailable events: &f" + String.join(", ", plugin.events().eventIds()));
            return;
        }
        if (!sender.hasPermission("zombie.admin")) {
            sendBrandedMessage(sender, "&cYou do not have permission to start or stop events.");
            return;
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("start")) {
            boolean started = plugin.events().startEvent(args[2]);
            sendBrandedMessage(sender, started ? "&aEvent started." : "&cEvent could not start: active event, cooldown, disabled setting, or missing target.");
            return;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("stop")) {
            sendBrandedMessage(sender, plugin.events().stopEvent() ? "&eEvent stopped." : "&cNo event is active.");
            return;
        }
        sendBrandedMessage(sender, "&cUsage: /zombie event <status|list|start <id>|stop>");
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

    private void device(CommandSender sender, String[] args) {
        PowerCctvService devices = plugin.powerCctv();
        if (args.length == 2 && args[1].equalsIgnoreCase("list")) {
            if (devices.devices().isEmpty()) {
                sender.sendMessage("&eNo CCTV/power devices are registered.");
                return;
            }
            for (PowerCctvService.Device device : devices.devices()) {
                String status = switch (device.type()) {
                    case "camera", "charger" -> devices.isPowered(device) ? "&aONLINE" : "&cOFFLINE";
                    case "generator" -> "&e" + device.fuelTicks() / 20 + "s fuel";
                    case "battery" -> "&b" + device.fuelTicks() / 20 + "s stored";
                    case "breaker" -> devices.isBreakerEnabled(device) ? "&aCLOSED" : "&cOPEN";
                    case "secure_door" -> devices.isPowered(device) ? "&aPOWERED LOCK" : "&cFAIL-CLOSED";
                    default -> "&7CONNECTED";
                };
                sender.sendMessage("&f" + device.id() + " &7(" + device.type() + ", " + device.world() + " "
                        + device.x() + " " + device.y() + " " + device.z() + ") " + status);
            }
            return;
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("remove")) {
            sender.sendMessage(devices.remove(args[2]) ? "&aRemoved device " + args[2] + "." : "&cUnknown device ID.");
            return;
        }
        if (args.length != 4 || !args[1].equalsIgnoreCase("place") || !(sender instanceof Player player)) {
            sender.sendMessage("&cUsage: /zombie device <list|place <camera|generator|battery|cable|connector|breaker|charger|monitor|terminal|laptop|secure_door> <id>|remove <id>>");
            return;
        }
        String type = args[2].toLowerCase(Locale.ROOT);
        String id = args[3].toLowerCase(Locale.ROOT);
        if (!List.of("camera", "generator", "battery", "cable", "connector", "breaker", "charger", "monitor", "terminal", "laptop", "secure_door").contains(type)
                || !id.matches("[a-z0-9_-]{1,32}")) {
            sender.sendMessage("&cUse a valid device type and an ID of 1-32 lowercase letters, digits, _ or -.");
            return;
        }
        Block target = player.getTargetBlockExact(8);
        if (target == null) {
            sender.sendMessage("&cLook at a block within 8 blocks to place the device.");
            return;
        }
        if (!devices.place(type, id, target.getLocation(), player.getLocation().getYaw(), player.getLocation().getPitch())) {
            sender.sendMessage("&cCould not place device: ID/location occupied, device limit reached, or devices disabled.");
            return;
        }
        sender.sendMessage("&aPlaced " + type + " &f" + id + " &aat " + target.getX() + ", " + target.getY() + ", " + target.getZ() + ".");
    }

    private void cctv(CommandSender sender, String[] args) {
        if (!sender.hasPermission("zombie.cctv")) {
            sender.sendMessage("&cYou do not have permission to use CCTV.");
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("&cCCTV commands require an in-game player.");
            return;
        }
        PowerCctvService devices = plugin.powerCctv();
        if (args.length == 2 && args[1].equalsIgnoreCase("list")) {
            List<PowerCctvService.Device> cameras = devices.devices().stream()
                    .filter(device -> device.type().equals("camera")).toList();
            if (cameras.isEmpty()) {
                sender.sendMessage("&eNo CCTV cameras are registered.");
                return;
            }
            for (PowerCctvService.Device camera : cameras) {
                sender.sendMessage("&f" + camera.id() + " &7- " + (devices.isPowered(camera) ? "&aONLINE" : "&cOFFLINE"));
            }
            return;
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("view")) {
            devices.view(player, args[2]);
            return;
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("log")) {
            List<String> records = devices.motionLog(args[2]);
            if (devices.find(args[2]) == null || !devices.find(args[2]).type().equals("camera")) {
                sender.sendMessage("&cUnknown CCTV camera.");
                return;
            }
            sender.sendMessage("&8--- &eMotion log: " + args[2] + " &8---");
            if (records.isEmpty()) {
                sender.sendMessage("&7No motion has been recorded.");
            } else {
                records.forEach(record -> sender.sendMessage("&7" + record));
            }
            return;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("exit")) {
            if (!devices.stopViewing(player)) {
                sender.sendMessage("&cYou are not viewing a CCTV camera.");
            }
            return;
        }
        sender.sendMessage("&cUsage: /zombie cctv <list|view <camera-id>|log <camera-id>|exit>");
    }

    private void powerStatus(CommandSender sender) {
        List<PowerCctvService.Device> devices = plugin.powerCctv().devices();
        long camerasOnline = devices.stream().filter(device -> device.type().equals("camera")
                && plugin.powerCctv().isPowered(device)).count();
        long generatorsFueled = devices.stream().filter(device -> device.type().equals("generator")
                && device.fuelTicks() > 0).count();
        long chargersOnline = devices.stream().filter(device -> device.type().equals("charger")
                && plugin.powerCctv().isPowered(device)).count();
        long batteriesCharged = devices.stream().filter(device -> device.type().equals("battery")
                && device.fuelTicks() > 0).count();
        sender.sendMessage("&ePower: &f" + generatorsFueled + " fueled generator(s), &f" + camerasOnline
                + " powered camera(s), &f" + chargersOnline + " charger(s), &f" + batteriesCharged + " charged battery(s).");
        if (sender.hasPermission("zombie.admin")) {
            sender.sendMessage("&7Refuel a generator by right-clicking it while holding coal or charcoal.");
        }
    }

    private void quest(CommandSender sender, String[] args) {
        if (!sender.hasPermission("zombie.quest")) {
            sendBrandedMessage(sender, "&cYou do not have permission to use quests.");
            return;
        }
        if (!(sender instanceof Player player)) {
            sendBrandedMessage(sender, "&cQuest commands require an in-game player.");
            return;
        }
        QuestService quests = plugin.quests();
        if (args.length == 2 && args[1].equalsIgnoreCase("list")) {
            quests.show(player);
        } else if (args.length == 2 && args[1].equalsIgnoreCase("status")) {
            quests.status(player);
        } else if (args.length == 2 && args[1].equalsIgnoreCase("reputation")) {
            quests.showReputation(player);
        } else if (args.length == 3 && args[1].equalsIgnoreCase("accept")) {
            quests.accept(player, args[2]);
        } else if (args.length == 3 && args[1].equalsIgnoreCase("claim")) {
            quests.claim(player, args[2]);
        } else {
            sendBrandedMessage(sender, "&cUsage: /zombie quest <list|status|reputation|accept <id>|claim <id>>");
        }
    }

    private void npc(CommandSender sender, String[] args) {
        SurvivorNpcService npcs = plugin.survivorNpcs();
        if (args.length == 2 && args[1].equalsIgnoreCase("list")) {
            List<SurvivorNpcService.NpcInfo> entries = npcs.npcs();
            if (entries.isEmpty()) {
                sendBrandedMessage(sender, "&eNo survivor NPCs are currently loaded.");
                return;
            }
            for (SurvivorNpcService.NpcInfo entry : entries) {
                sendBrandedMessage(sender, "&f" + entry.id() + " &7(" + entry.role() + ") in " + entry.world()
                        + " &8- &f" + entry.name());
            }
            return;
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("remove")) {
            sendBrandedMessage(sender, npcs.remove(args[2]) ? "&aRemoved NPC " + args[2] + "." : "&cNPC not found in loaded chunks.");
            return;
        }
        if (args.length < 4 || !args[1].equalsIgnoreCase("spawn") || !(sender instanceof Player player)) {
            sendBrandedMessage(sender, "&cUsage: /zombie npc <list|spawn <role> <id> [name...]|remove <id>>");
            return;
        }
        String role = args[2].toLowerCase(Locale.ROOT);
        String id = args[3].toLowerCase(Locale.ROOT);
        if (!npcs.isValidRole(role) || !id.matches("[a-z0-9_-]{1,32}")) {
            sendBrandedMessage(sender, "&cInvalid role or ID. Roles: trader, questgiver, doctor, engineer, guard, survivor.");
            return;
        }
        Block target = player.getTargetBlockExact(8);
        if (target == null) {
            sendBrandedMessage(sender, "&cLook at a block within 8 blocks to place the NPC.");
            return;
        }
        String name = args.length > 4 ? String.join(" ", java.util.Arrays.copyOfRange(args, 4, args.length)) : role + " " + id;
        org.bukkit.Location spawnLocation = target.getLocation().add(0.5, 1.0, 0.5);
        spawnLocation.setYaw(player.getLocation().getYaw());
        if (npcs.spawn(role, id, name, spawnLocation) == null) {
            sendBrandedMessage(sender, "&cNPCs are disabled or the NPC definition is invalid.");
            return;
        }
        sendBrandedMessage(sender, "&aSpawned &f" + role + " &aNPC &f" + id + "&a. Sneak-right-click it or use /zombie npc remove " + id + ".");
    }

    private void sendBrandedMessage(CommandSender sender, String message) {
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("info", "about", "version", "help", "status", "reload", "spawn", "infection", "region", "cure", "item", "event", "device", "power", "cctv", "npc", "quest", "debug"), args[0]);
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
            return filter(List.of("status", "list", "start", "stop"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("event") && args[1].equalsIgnoreCase("start")) {
            return filter(plugin.events().eventIds(), args[2]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("region")) {
            return filter(List.of("list", "status", "set", "clear"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("device")) {
            return filter(List.of("list", "place", "remove"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("device") && args[1].equalsIgnoreCase("place")) {
            return filter(List.of("camera", "generator", "battery", "cable", "connector", "breaker", "charger",
                    "monitor", "terminal", "laptop"), args[2]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("cctv")) {
            return filter(List.of("list", "view", "log", "exit"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("cctv") && args[1].equalsIgnoreCase("view")) {
            return filter(plugin.powerCctv().devices().stream().filter(device -> device.type().equals("camera"))
                    .map(PowerCctvService.Device::id).toList(), args[2]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("npc")) {
            return filter(List.of("list", "spawn", "remove"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("npc") && args[1].equalsIgnoreCase("spawn")) {
            return filter(List.of("trader", "questgiver", "doctor", "engineer", "guard", "survivor"), args[2]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("quest")) {
            return filter(List.of("list", "status", "reputation", "accept", "claim"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("quest")
                && List.of("accept", "claim").contains(args[1].toLowerCase(Locale.ROOT))) {
            return filter(plugin.quests().quests().stream().map(QuestService.Quest::id).toList(), args[2]);
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