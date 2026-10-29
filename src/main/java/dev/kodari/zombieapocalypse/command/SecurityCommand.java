package dev.kodari.zombieapocalypse.command;

import dev.kodari.zombieapocalypse.security.SecurityService;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;

public final class SecurityCommand implements CommandExecutor {
    private final SecurityService security;

    public SecurityCommand(SecurityService security) {
        this.security = security;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "This command requires an in-game player.");
            return true;
        }
        if (args.length == 0) {
            help(player);
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "lock" -> {
                if (args.length != 3) {
                    usage(player, "/zsecurity lock <id> <4-8 digit PIN>");
                } else {
                    security.lock(player, args[1], args[2]);
                }
            }
            case "allow", "revoke" -> {
                if (args.length != 3) {
                    usage(player, "/zsecurity allow|revoke <door-id> <player>");
                } else {
                    security.allow(player, args[1], args[2], action.equals("allow"));
                }
            }
            case "keycard" -> {
                if (args.length != 3) {
                    usage(player, "/zsecurity keycard <door-id> <online-player>");
                } else {
                    security.issueKeycard(player, args[1], args[2]);
                }
            }
            case "pin" -> {
                if (args.length != 3) {
                    usage(player, "/zsecurity pin <door-id> <PIN>");
                } else {
                    security.unlockWithPin(player, args[1], args[2]);
                }
            }
            case "setpin" -> {
                if (args.length != 3) {
                    usage(player, "/zsecurity setpin <door-id> <4-8 digit PIN>");
                } else {
                    security.setPin(player, args[1], args[2]);
                }
            }
            case "remove", "status" -> {
                if (args.length != 2) {
                    usage(player, "/zsecurity " + action + " <door-id>");
                } else if (action.equals("remove")) {
                    security.remove(player, args[1]);
                } else {
                    security.status(player, args[1]);
                }
            }
            default -> help(player);
        }
        return true;
    }

    private void help(Player player) {
        player.sendMessage(ChatColor.YELLOW + "/zsecurity lock <id> <PIN> | setpin <id> <PIN> | pin <id> <PIN>");
        player.sendMessage(ChatColor.YELLOW + "/zsecurity allow|revoke <id> <player> | keycard <id> <online-player>");
        player.sendMessage(ChatColor.YELLOW + "/zsecurity status|remove <id>");
    }

    private void usage(Player player, String usage) {
        player.sendMessage(ChatColor.YELLOW + "Usage: " + usage);
    }
}