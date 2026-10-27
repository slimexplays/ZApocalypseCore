package dev.kodari.zombieapocalypse.command;

import dev.kodari.zombieapocalypse.team.TeamService;
import dev.kodari.zombieapocalypse.team.TeamService.Result;
import dev.kodari.zombieapocalypse.team.TeamService.Team;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

public final class TeamCommand implements CommandExecutor, TabCompleter {
    private final TeamService teams;

    public TeamCommand(TeamService teams) {
        this.teams = teams;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, "&cThis command requires an in-game player.");
            return true;
        }
        if (args.length == 0) {
            send(sender, "&e/zteam create <name>|invite <player>|accept|leave|info|kick <player>|disband");
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        Result result;
        switch (action) {
            case "create" -> {
                if (args.length != 2) {
                    send(sender, "&cUsage: /zteam create <name>");
                    return true;
                }
                result = teams.create(player, args[1]);
            }
            case "invite" -> {
                if (args.length != 2) {
                    send(sender, "&cUsage: /zteam invite <online-player>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    result = Result.TARGET_NOT_ONLINE;
                } else {
                    result = teams.invite(player, target);
                }
            }
            case "accept" -> {
                if (args.length != 1) {
                    send(sender, "&cUsage: /zteam accept");
                    return true;
                }
                result = teams.accept(player);
            }
            case "leave" -> {
                result = teams.leave(player);
            }
            case "info" -> {
                showInfo(player);
                return true;
            }
            case "kick" -> {
                if (args.length != 2) {
                    send(sender, "&cUsage: /zteam kick <online-player>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    result = Result.TARGET_NOT_ONLINE;
                } else {
                    result = teams.kick(player, target);
                }
            }
            case "disband" -> result = teams.disband(player);
            default -> {
                send(sender, "&cUnknown action. Use /zteam for help.");
                return true;
            }
        }
        sendResult(sender, result);
        return true;
    }

    private void showInfo(Player player) {
        Team team = teams.teamOf(player);
        if (team == null) {
            send(player, "&eYou are not in a team.");
            return;
        }
        send(player, "&6Team &f" + team.name() + " &8| &eLeader: &f" + playerName(team.leader()));
        for (UUID member : team.members()) {
            send(player, (member.equals(team.leader()) ? "&6★ " : "&7- ") + "&f" + playerName(member));
        }
    }

    private String playerName(UUID playerId) {
        Player online = Bukkit.getPlayer(playerId);
        if (online != null) {
            return online.getName();
        }
        String name = Bukkit.getOfflinePlayer(playerId).getName();
        return name == null ? playerId.toString().substring(0, 8) : name;
    }

    private void sendResult(CommandSender sender, Result result) {
        String message = switch (result) {
            case SUCCESS -> "&aTeam action completed.";
            case DISABLED -> "&cTeams are disabled.";
            case INVALID_NAME -> "&cTeam names must be 3-16 letters, digits, _ or -.";
            case TEAM_EXISTS -> "&cA team with that name already exists.";
            case ALREADY_IN_TEAM -> "&cYou are already in a team.";
            case NOT_IN_TEAM -> "&cYou are not in a team.";
            case NOT_LEADER -> "&cOnly the team leader can do that.";
            case TARGET_NOT_ONLINE -> "&cThat player must be online.";
            case TARGET_IN_TEAM -> "&cThat player is already in a team or is not on your team.";
            case TEAM_FULL -> "&cThat team has reached its member limit.";
            case NO_INVITE -> "&cYou have no pending team invitation.";
            case INVITE_EXPIRED -> "&cThat team invitation has expired.";
            case CANNOT_TARGET_SELF -> "&cYou cannot invite yourself.";
            case CANNOT_KICK_LEADER -> "&cThe team leader cannot be kicked.";
        };
        send(sender, message);
    }

    private void send(CommandSender sender, String message) {
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("create", "invite", "accept", "leave", "info", "kick", "disband"), args[0]);
        }
        if (args.length == 2 && List.of("invite", "kick").contains(args[0].toLowerCase(Locale.ROOT))) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        }
        return List.of();
    }

    private List<String> filter(List<String> values, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower)).collect(Collectors.toList());
    }
}