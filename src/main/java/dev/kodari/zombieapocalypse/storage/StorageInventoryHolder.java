package dev.kodari.zombieapocalypse.storage;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

public final class StorageInventoryHolder implements InventoryHolder {
    private final UUID storageId;
    private Inventory inventory;

    public StorageInventoryHolder(UUID storageId) {
        this.storageId = storageId;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public UUID storageId() {
        return storageId;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }
}