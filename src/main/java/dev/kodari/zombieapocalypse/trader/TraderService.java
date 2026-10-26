package dev.kodari.zombieapocalypse.trader;

import dev.kodari.zombieapocalypse.ZombieApocalypsePlugin;
import dev.kodari.zombieapocalypse.config.ConfigManager;
import dev.kodari.zombieapocalypse.item.CustomItemService;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class TraderService {
    public enum TradeResult {
        SUCCESS,
        DISABLED,
        UNKNOWN_OFFER,
        INVALID_AMOUNT,
        OUT_OF_STOCK,
        NO_ECONOMY_ADAPTER,
        INSUFFICIENT_FUNDS,
        NOT_SELLABLE,
        NOT_ENOUGH_ITEMS,
        INVALID_ITEM
    }

    private static final class StockState {
        private int available;
        private long lastRestockMillis;

        private StockState(int available, long lastRestockMillis) {
            this.available = available;
            this.lastRestockMillis = lastRestockMillis;
        }
    }

    private final ZombieApocalypsePlugin plugin;
    private final ConfigManager config;
    private final CustomItemService items;
    private volatile Map<String, TraderOffer> offers = Map.of();
    private final Map<String, StockState> stock = new LinkedHashMap<>();
    private EconomyAdapter economy;

    public TraderService(ZombieApocalypsePlugin plugin, ConfigManager config, CustomItemService items) {
        this.plugin = plugin;
        this.config = config;
        this.items = items;
        reload();
    }

    public void setEconomyAdapter(EconomyAdapter economy) {
        this.economy = economy;
    }

    public boolean hasEconomyAdapter() {
        return economy != null;
    }

    public void reload() {
        Map<String, TraderOffer> candidate = new LinkedHashMap<>();
        ConfigurationSection section = config.getSection("trader.offers");
        if (section != null) {
            for (String rawItemId : section.getKeys(false)) {
                String itemId = rawItemId.toLowerCase(Locale.ROOT);
                ConfigurationSection offer = section.getConfigurationSection(rawItemId);
                if (offer == null || config.getSection("items." + itemId) == null) {
                    continue;
                }
                double buy = offer.getDouble("buy-price", 0);
                double sell = offer.getDouble("sell-price", 0);
                int maxStock = offer.getInt("stock", -1);
                long restock = offer.getLong("restock-seconds", 0);
                if (!Double.isFinite(buy) || !Double.isFinite(sell) || buy < 0 || sell < 0 || maxStock < -1 || restock < 0) {
                    plugin.getLogger().warning("Skipping invalid trader offer for '" + itemId + "'.");
                    continue;
                }
                candidate.put(itemId, new TraderOffer(itemId, buy, sell, maxStock, restock));
                stock.computeIfAbsent(itemId, id -> new StockState(maxStock, System.currentTimeMillis()));
            }
        }
        offers = Collections.unmodifiableMap(candidate);
    }

    public List<TraderOffer> offers() {
        return List.copyOf(offers.values());
    }

    public TradeResult buy(Player player, String itemId, int amount) {
        if (!config.getBoolean("trader.enabled", false)) {
            return TradeResult.DISABLED;
        }
        if (amount < 1 || amount > 64) {
            return TradeResult.INVALID_AMOUNT;
        }
        TraderOffer offer = offers.get(itemId.toLowerCase(Locale.ROOT));
        if (offer == null) {
            return TradeResult.UNKNOWN_OFFER;
        }
        ItemStack result = items.create(offer.itemId());
        if (result == null) {
            return TradeResult.INVALID_ITEM;
        }
        List<ItemStack> results = new ArrayList<>();
        results.add(result);
        boolean storageItem = config.getInt("items." + offer.itemId() + ".storage-size", 0) > 0;
        if (storageItem) {
            for (int index = 1; index < amount; index++) {
                ItemStack additional = items.create(offer.itemId());
                if (additional == null) {
                    return TradeResult.INVALID_ITEM;
                }
                results.add(additional);
            }
        }
        if (economy == null) {
            return TradeResult.NO_ECONOMY_ADAPTER;
        }
        StockState state = stock.computeIfAbsent(offer.itemId(), id -> new StockState(offer.stock(), System.currentTimeMillis()));
        restock(offer, state);
        if (offer.stock() >= 0 && state.available < amount) {
            return TradeResult.OUT_OF_STOCK;
        }
        if (offer.buyPrice() <= 0 || !economy.withdraw(player, offer.buyPrice() * amount)) {
            return TradeResult.INSUFFICIENT_FUNDS;
        }
        if (offer.stock() >= 0) {
            state.available -= amount;
        }
        if (!storageItem) {
            result.setAmount(amount);
        }
        results.forEach(item -> give(player, item));
        return TradeResult.SUCCESS;
    }

    public TradeResult sell(Player player, String itemId, int amount) {
        if (!config.getBoolean("trader.enabled", false)) {
            return TradeResult.DISABLED;
        }
        if (amount < 1 || amount > 64) {
            return TradeResult.INVALID_AMOUNT;
        }
        TraderOffer offer = offers.get(itemId.toLowerCase(Locale.ROOT));
        if (offer == null) {
            return TradeResult.UNKNOWN_OFFER;
        }
        if (offer.sellPrice() <= 0) {
            return TradeResult.NOT_SELLABLE;
        }
        if (config.getInt("items." + offer.itemId() + ".storage-size", 0) > 0) {
            return TradeResult.NOT_SELLABLE;
        }
        if (economy == null) {
            return TradeResult.NO_ECONOMY_ADAPTER;
        }
        if (count(player, offer.itemId()) < amount) {
            return TradeResult.NOT_ENOUGH_ITEMS;
        }
        if (!economy.deposit(player, offer.sellPrice() * amount)) {
            return TradeResult.INVALID_ITEM;
        }
        remove(player, offer.itemId(), amount);
        StockState state = stock.computeIfAbsent(offer.itemId(), id -> new StockState(offer.stock(), System.currentTimeMillis()));
        restock(offer, state);
        if (offer.stock() >= 0) {
            state.available = Math.min(offer.stock(), state.available + amount);
        }
        return TradeResult.SUCCESS;
    }

    private void restock(TraderOffer offer, StockState state) {
        if (offer.stock() < 0 || offer.restockSeconds() <= 0 || state.available >= offer.stock()) {
            return;
        }
        long intervalMillis = offer.restockSeconds() * 1000L;
        long intervals = (System.currentTimeMillis() - state.lastRestockMillis) / intervalMillis;
        if (intervals > 0) {
            state.available = offer.stock();
            state.lastRestockMillis += intervals * intervalMillis;
        }
    }

    private int count(Player player, String itemId) {
        int total = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && itemId.equals(items.getItemId(item))) {
                total += item.getAmount();
            }
        }
        return total;
    }

    private void remove(Player player, String itemId, int amount) {
        ItemStack[] contents = player.getInventory().getStorageContents();
        int remaining = amount;
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (item == null || !itemId.equals(items.getItemId(item))) {
                continue;
            }
            int removed = Math.min(remaining, item.getAmount());
            remaining -= removed;
            if (removed == item.getAmount()) {
                player.getInventory().setItem(slot, null);
            } else {
                item.setAmount(item.getAmount() - removed);
            }
        }
    }

    private void give(Player player, ItemStack item) {
        for (ItemStack overflow : player.getInventory().addItem(item).values()) {
            Location location = player.getLocation();
            location.getWorld().dropItemNaturally(location, overflow);
        }
    }
}