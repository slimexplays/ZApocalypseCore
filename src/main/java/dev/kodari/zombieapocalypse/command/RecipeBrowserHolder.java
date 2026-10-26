package dev.kodari.zombieapocalypse.command;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;

public final class RecipeBrowserHolder implements InventoryHolder {
    private final Map<Integer, String> recipes = new HashMap<>();
    private Inventory inventory;
    private String query = "";
    private int page;

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    public Map<Integer, String> recipes() {
        return recipes;
    }

    public String query() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public int page() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }
}