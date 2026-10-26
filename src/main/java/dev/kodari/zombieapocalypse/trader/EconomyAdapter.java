package dev.kodari.zombieapocalypse.trader;

import org.bukkit.entity.Player;

public interface EconomyAdapter {
    boolean withdraw(Player player, double amount);

    boolean deposit(Player player, double amount);
}