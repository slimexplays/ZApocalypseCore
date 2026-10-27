package dev.kodari.zombieapocalypse.team;

import dev.kodari.zombieapocalypse.ZombieApocalypsePlugin;
import dev.kodari.zombieapocalypse.communication.TeamResolver;
import dev.kodari.zombieapocalypse.config.ConfigManager;
import dev.kodari.zombieapocalypse.storage.StorageTeamResolver;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class TeamService implements TeamResolver, StorageTeamResolver, AutoCloseable {
    public enum Result {
        SUCCESS,
        DISABLED,
        INVALID_NAME,
        TEAM_EXISTS,
        ALREADY_IN_TEAM,
        NOT_IN_TEAM,
        NOT_LEADER,
        TARGET_NOT_ONLINE,
        TARGET_IN_TEAM,
        TEAM_FULL,
        NO_INVITE,
        INVITE_EXPIRED,
        CANNOT_TARGET_SELF,
        CANNOT_KICK_LEADER
    }

    private final ZombieApocalypsePlugin plugin;
    private final ConfigManager config;
    private final File dataFile;
    private final Map<String, Team> teams = new LinkedHashMap<>();
    private final Map<UUID, String> membership = new LinkedHashMap<>();
    private final Map<UUID, Invitation> invitations = new LinkedHashMap<>();

    public TeamService(ZombieApocalypsePlugin plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
        this.dataFile = new File(plugin.getDataFolder(), "teams.yml");
        load();
    }

    public Result create(Player leader, String name) {
        if (!config.getBoolean("teams.enabled", true)) {
            return Result.DISABLED;
        }
        if (!name.matches("[A-Za-z0-9_-]{3,16}")) {
            return Result.INVALID_NAME;
        }
        if (membership.containsKey(leader.getUniqueId())) {
            return Result.ALREADY_IN_TEAM;
        }
        String id = name.toLowerCase(Locale.ROOT);
        if (teams.containsKey(id)) {
            return Result.TEAM_EXISTS;
        }
        Team team = new Team(id, name, leader.getUniqueId(), Set.of(leader.getUniqueId()));
        teams.put(id, team);
        membership.put(leader.getUniqueId(), id);
        save();
        return Result.SUCCESS;
    }

    public Result invite(Player inviter, Player target) {
        if (!config.getBoolean("teams.enabled", true)) {
            return Result.DISABLED;
        }
        if (inviter.equals(target)) {
            return Result.CANNOT_TARGET_SELF;
        }
        Team team = teamOf(inviter.getUniqueId());
        if (team == null) {
            return Result.NOT_IN_TEAM;
        }
        if (!team.leader().equals(inviter.getUniqueId())) {
            return Result.NOT_LEADER;
        }
        if (membership.containsKey(target.getUniqueId())) {
            return Result.TARGET_IN_TEAM;
        }
        if (team.members().size() >= config.getInt("teams.max-size", 4)) {
            return Result.TEAM_FULL;
        }
        long expiresAt = System.currentTimeMillis() + config.getInt("teams.invite-expiry-seconds", 120) * 1000L;
        invitations.put(target.getUniqueId(), new Invitation(team.id(), expiresAt));
        target.sendMessage(color("&eYou were invited to team &f" + team.name() + "&e by &f" + inviter.getName()
                + "&e. Use &f/zteam accept&e."));
        return Result.SUCCESS;
    }

    public Result accept(Player player) {
        if (!config.getBoolean("teams.enabled", true)) {
            return Result.DISABLED;
        }
        if (membership.containsKey(player.getUniqueId())) {
            return Result.ALREADY_IN_TEAM;
        }
        Invitation invitation = invitations.get(player.getUniqueId());
        if (invitation == null) {
            return Result.NO_INVITE;
        }
        if (invitation.expiresAt() <= System.currentTimeMillis()) {
            invitations.remove(player.getUniqueId());
            return Result.INVITE_EXPIRED;
        }
        Team team = teams.get(invitation.teamId());
        if (team == null) {
            invitations.remove(player.getUniqueId());
            return Result.NO_INVITE;
        }
        if (team.members().size() >= config.getInt("teams.max-size", 4)) {
            return Result.TEAM_FULL;
        }
        LinkedHashSet<UUID> members = new LinkedHashSet<>(team.members());
        members.add(player.getUniqueId());
        teams.put(team.id(), new Team(team.id(), team.name(), team.leader(), members));
        membership.put(player.getUniqueId(), team.id());
        invitations.remove(player.getUniqueId());
        save();
        Player leader = Bukkit.getPlayer(team.leader());
        if (leader != null) {
            leader.sendMessage(color("&a" + player.getName() + " joined your team."));
        }
        return Result.SUCCESS;
    }

    public Result leave(Player player) {
        Team team = teamOf(player.getUniqueId());
        if (team == null) {
            return Result.NOT_IN_TEAM;
        }
        LinkedHashSet<UUID> members = new LinkedHashSet<>(team.members());
        members.remove(player.getUniqueId());
        membership.remove(player.getUniqueId());
        invitations.remove(player.getUniqueId());
        if (members.isEmpty()) {
            teams.remove(team.id());
        } else {
            UUID leader = team.leader().equals(player.getUniqueId()) ? members.iterator().next() : team.leader();
            teams.put(team.id(), new Team(team.id(), team.name(), leader, members));
        }
        save();
        return Result.SUCCESS;
    }

    public Result kick(Player leader, Player target) {
        Team team = teamOf(leader.getUniqueId());
        if (team == null) {
            return Result.NOT_IN_TEAM;
        }
        if (!team.leader().equals(leader.getUniqueId())) {
            return Result.NOT_LEADER;
        }
        if (target.equals(leader)) {
            return Result.CANNOT_KICK_LEADER;
        }
        if (!team.id().equals(membership.get(target.getUniqueId()))) {
            return Result.TARGET_IN_TEAM;
        }
        LinkedHashSet<UUID> members = new LinkedHashSet<>(team.members());
        members.remove(target.getUniqueId());
        teams.put(team.id(), new Team(team.id(), team.name(), team.leader(), members));
        membership.remove(target.getUniqueId());
        save();
        target.sendMessage(color("&cYou were removed from team &f" + team.name() + "&c."));
        return Result.SUCCESS;
    }

    public Result disband(Player leader) {
        Team team = teamOf(leader.getUniqueId());
        if (team == null) {
            return Result.NOT_IN_TEAM;
        }
        if (!team.leader().equals(leader.getUniqueId())) {
            return Result.NOT_LEADER;
        }
        teams.remove(team.id());
        for (UUID member : team.members()) {
            membership.remove(member);
            Player online = Bukkit.getPlayer(member);
            if (online != null && !member.equals(leader.getUniqueId())) {
                online.sendMessage(color("&cTeam &f" + team.name() + " &cwas disbanded."));
            }
        }
        save();
        return Result.SUCCESS;
    }

    public Team teamOf(UUID playerId) {
        String teamId = membership.get(playerId);
        return teamId == null ? null : teams.get(teamId);
    }

    public Team teamOf(Player player) {
        return teamOf(player.getUniqueId());
    }

    public List<Team> teams() {
        return List.copyOf(teams.values());
    }

    @Override
    public boolean areTeammates(Player first, Player second) {
        return areTeammates(first.getUniqueId(), second.getUniqueId());
    }

    @Override
    public boolean areTeammates(UUID ownerId, UUID playerId) {
        if (ownerId.equals(playerId)) {
            return true;
        }
        String teamId = membership.get(ownerId);
        return teamId != null && teamId.equals(membership.get(playerId));
    }

    private void load() {
        if (!dataFile.exists()) {
            return;
        }
        YamlConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection entries = data.getConfigurationSection("teams");
        if (entries == null) {
            return;
        }
        int maxSize = config.getInt("teams.max-size", 4);
        for (String id : entries.getKeys(false)) {
            ConfigurationSection entry = entries.getConfigurationSection(id);
            if (entry == null || !id.matches("[a-z0-9_-]{3,16}")) {
                continue;
            }
            UUID leader;
            try {
                leader = UUID.fromString(entry.getString("leader", ""));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Ignoring team with invalid leader UUID: " + id);
                continue;
            }
            LinkedHashSet<UUID> members = new LinkedHashSet<>();
            boolean valid = true;
            for (String rawMember : entry.getStringList("members")) {
                try {
                    UUID member = UUID.fromString(rawMember);
                    if (membership.containsKey(member)) {
                        valid = false;
                        break;
                    }
                    members.add(member);
                } catch (IllegalArgumentException exception) {
                    valid = false;
                    break;
                }
            }
            if (!valid || members.isEmpty() || members.size() > maxSize || !members.contains(leader)
                    || teams.containsKey(id)) {
                plugin.getLogger().warning("Ignoring invalid or conflicting persisted team: " + id);
                continue;
            }
            Team team = new Team(id, entry.getString("name", id), leader, members);
            teams.put(id, team);
            for (UUID member : members) {
                membership.put(member, id);
            }
        }
    }

    private void save() {
        YamlConfiguration data = new YamlConfiguration();
        data.set("schema", 1);
        for (Team team : teams.values()) {
            String path = "teams." + team.id() + ".";
            data.set(path + "name", team.name());
            data.set(path + "leader", team.leader().toString());
            data.set(path + "members", team.members().stream().map(UUID::toString).toList());
        }
        try {
            dataFile.getParentFile().mkdirs();
            data.save(dataFile);
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not save teams: " + exception.getMessage());
        }
    }

    private String color(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    @Override
    public void close() {
        save();
    }

    public record Team(String id, String name, UUID leader, Set<UUID> members) {
        public Team {
            members = Collections.unmodifiableSet(new LinkedHashSet<>(members));
        }
    }

    private record Invitation(String teamId, long expiresAt) {
    }
}