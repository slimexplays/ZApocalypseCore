package dev.kodari.zombieapocalypse.navigation;

import dev.kodari.zombieapocalypse.ZombieApocalypsePlugin;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

public final class WaypointService {
    private final ZombieApocalypsePlugin plugin;
    private final NamespacedKey waypointsKey;

    public WaypointService(ZombieApocalypsePlugin plugin) {
        this.plugin = plugin;
        this.waypointsKey = new NamespacedKey("ultra_advanced_zombie_apocalypse", "survival_waypoints");
    }

    public boolean save(Player player, String id, Location location) {
        if (location.getWorld() == null || id == null || !id.toLowerCase(Locale.ROOT).matches("[a-z0-9_-]{1,24}")) {
            return false;
        }
        List<Waypoint> waypoints = read(player);
        String normalizedId = id.toLowerCase(Locale.ROOT);
        boolean replacing = waypoints.removeIf(waypoint -> waypoint.id().equals(normalizedId));
        if (!replacing && waypoints.size() >= 20) {
            return false;
        }
        waypoints.add(new Waypoint(normalizedId, location.getWorld().getName(), location.getBlockX(),
                location.getBlockY(), location.getBlockZ()));
        write(player, waypoints);
        return true;
    }

    public boolean remove(Player player, String id) {
        String normalizedId = id.toLowerCase(Locale.ROOT);
        List<Waypoint> waypoints = read(player);
        boolean removed = waypoints.removeIf(waypoint -> waypoint.id().equals(normalizedId));
        if (removed) {
            write(player, waypoints);
        }
        return removed;
    }

    public List<Waypoint> list(Player player) {
        return List.copyOf(read(player));
    }

    public void showLocation(Player player, Location location) {
        if (location.getWorld() != null) {
            player.sendMessage(ChatColor.AQUA + location.getWorld().getName() + ChatColor.GRAY + " ["
                    + location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ() + "]");
        }
    }

    private List<Waypoint> read(Player player) {
        String serialized = player.getPersistentDataContainer().get(waypointsKey, PersistentDataType.STRING);
        List<Waypoint> waypoints = new ArrayList<>();
        if (serialized == null || serialized.isBlank()) {
            return waypoints;
        }
        for (String line : serialized.split("\\n")) {
            String[] fields = line.split("\\|", -1);
            if (fields.length != 5) {
                continue;
            }
            try {
                String world = new String(Base64.getUrlDecoder().decode(fields[1]), StandardCharsets.UTF_8);
                waypoints.add(new Waypoint(fields[0], world, Integer.parseInt(fields[2]),
                        Integer.parseInt(fields[3]), Integer.parseInt(fields[4])));
            } catch (IllegalArgumentException ignored) {
                continue;
            }
        }
        return waypoints;
    }

    private void write(Player player, List<Waypoint> waypoints) {
        String serialized = waypoints.stream().map(waypoint -> waypoint.id() + "|"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(waypoint.world().getBytes(StandardCharsets.UTF_8))
                + "|" + waypoint.x() + "|" + waypoint.y() + "|" + waypoint.z()).reduce((first, second) -> first + "\n" + second).orElse("");
        if (serialized.isEmpty()) {
            player.getPersistentDataContainer().remove(waypointsKey);
        } else {
            player.getPersistentDataContainer().set(waypointsKey, PersistentDataType.STRING, serialized);
        }
    }
}