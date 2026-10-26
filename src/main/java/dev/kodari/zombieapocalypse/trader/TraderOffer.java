package dev.kodari.zombieapocalypse.trader;

public record TraderOffer(String itemId, double buyPrice, double sellPrice, int stock, long restockSeconds) {
}