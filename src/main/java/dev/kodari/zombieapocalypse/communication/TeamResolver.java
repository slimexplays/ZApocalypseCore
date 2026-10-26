package dev.kodari.zombieapocalypse.communication;

import org.bukkit.entity.Player;

@FunctionalInterface
public interface TeamResolver {
    boolean areTeammates(Player first, Player second);
}