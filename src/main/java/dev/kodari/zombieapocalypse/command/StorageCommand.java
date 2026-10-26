package dev.kodari.zombieapocalypse.command;

import dev.kodari.zombieapocalypse.storage.StorageAccessMode;
import dev.kodari.zombieapocalypse.storage.StorageService;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class StorageCommand implements CommandExecutor, TabCompleter {
    private final StorageService storages;

    public StorageCommand(StorageService storages) {
        this.storages = storages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "This command requires a player holding a storage item.");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("info")) {
            StorageAccessMode mode = storages.getAccessMode(player.getInventory().getItemInMainHand());
            player.sendMessage(mode == null ? ChatColor.RED + "Hold and open your storage first."
                    : ChatColor.YELLOW + "Storage access: " + mode.name().toLowerCase(Locale.ROOT));
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("mode")) {
            StorageAccessMode mode;
            try {
                mode = StorageAccessMode.valueOf(args[1].toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                player.sendMessage(ChatColor.RED + "Mode must be private, team, or public.");
                return true;
            }
            if (storages.setAccessMode(player, player.getInventory().getItemInMainHand(), mode)) {
                player.sendMessage(ChatColor.GREEN + "Storage access set to " + mode.name().toLowerCase(Locale.ROOT) + ".");
            } else {
                player.sendMessage(ChatColor.RED + "You must own a valid storage item and its data must be loaded.");
            }
            return true;
        }
        player.sendMessage(ChatColor.YELLOW + "/zstorage info|mode <private|team|public> (hold the storage item)");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("info", "mode").stream().filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("mode")) {
            return Arrays.stream(StorageAccessMode.values()).map(mode -> mode.name().toLowerCase(Locale.ROOT))
                    .filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }
}