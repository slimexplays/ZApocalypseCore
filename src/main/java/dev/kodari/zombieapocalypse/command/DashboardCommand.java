package dev.kodari.zombieapocalypse.command;

import com.cryptomorin.xseries.XMaterial;
import dev.kodari.zombieapocalypse.ZombieApocalypsePlugin;
import dev.kodari.zombieapocalypse.device.PowerCctvService;
import dev.kodari.zombieapocalypse.team.TeamService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public final class DashboardCommand implements CommandExecutor, Listener {
    private final ZombieApocalypsePlugin plugin;

    public DashboardCommand(ZombieApocalypsePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(color("&cThe dashboard is available in game."));
            return true;
        }
        open(player);
        return true;
    }

    private void open(Player player) {
        DashboardHolder holder = new DashboardHolder();
        Inventory inventory = Bukkit.createInventory(holder, 27, color("&8Survivor Dashboard"));
        holder.setInventory(inventory);

        int trackedZombies = plugin.zombies().trackedCount();
        setCard(inventory, 10, "ROTTEN_FLESH", "&cInfected Threat", List.of(
                "&7Tracked custom zombies: &f" + trackedZombies,
                "&8Count reflects loaded custom variants."));

        String infection = plugin.infections().isLoaded()
                ? plugin.infections().getStage(player.getUniqueId()).name() : "Loading";
        setCard(inventory, 11, "POTION", "&dYour Infection", List.of("&7Current stage: &f" + infection));

        TeamService.Team team = plugin.teams().teamOf(player);
        setCard(inventory, 12, "PLAYER_HEAD", "&bYour Team", List.of(
                team == null ? "&7Not in a team." : "&7Team: &f" + team.name(),
                team == null ? "&7Use /zteam create or accept." : "&7Members: &f" + team.members().size()));

        List<PowerCctvService.Device> devices = plugin.powerCctv().devices();
        long camerasOnline = devices.stream().filter(device -> device.type().equals("camera")
                && plugin.powerCctv().isPowered(device)).count();
        long fueledGenerators = devices.stream().filter(device -> device.type().equals("generator")
                && device.fuelTicks() > 0).count();
        setCard(inventory, 13, "REDSTONE", "&ePower & CCTV", List.of(
                "&7Online cameras: &f" + camerasOnline,
                "&7Fueled generators: &f" + fueledGenerators,
                "&8Use /zombie cctv list to view cameras."));

        setCard(inventory, 14, "BOOK", "&6Survivor Quests", List.of(
                "&7Configured quests: &f" + plugin.quests().quests().size(),
                "&8Use /zombie quest list to track work."));

        long highRiskRegions = plugin.regionalInfection().regions().stream()
                .filter(region -> region.type().equals("high-risk")).count();
        setCard(inventory, 15, "MAP", "&2Regional Infection", List.of(
                "&7Configured regions: &f" + plugin.regionalInfection().regions().size(),
                "&7High-risk regions: &f" + highRiskRegions));

        inventory.setItem(22, item("BARRIER", "&cClose", List.of("&7Click to close this dashboard.")));
        ItemStack filler = item("BLACK_STAINED_GLASS_PANE", " ", List.of());
        if (filler != null) {
            for (int slot = 0; slot < inventory.getSize(); slot++) {
                if (inventory.getItem(slot) == null) {
                    inventory.setItem(slot, filler.clone());
                }
            }
        }
        player.openInventory(inventory);
    }

    private void setCard(Inventory inventory, int slot, String material, String name, List<String> lore) {
        ItemStack item = item(material, name, lore);
        if (item != null) {
            inventory.setItem(slot, item);
        }
    }

    private ItemStack item(String material, String name, List<String> lore) {
        ItemStack item = XMaterial.matchXMaterial(material).map(XMaterial::parseItem)
                .orElseGet(() -> XMaterial.matchXMaterial("PAPER").map(XMaterial::parseItem).orElse(null));
        if (item == null) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.setDisplayName(color(name));
        meta.setLore(lore.stream().map(this::color).collect(Collectors.toList()));
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof DashboardHolder)) {
            return;
        }
        event.setCancelled(true);
        if (event.getRawSlot() == 22 && event.getWhoClicked() instanceof Player player) {
            player.closeInventory();
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof DashboardHolder) {
            event.setCancelled(true);
        }
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private static final class DashboardHolder implements InventoryHolder {
        private Inventory inventory;

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}