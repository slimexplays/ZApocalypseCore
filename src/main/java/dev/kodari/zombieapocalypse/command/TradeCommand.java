package dev.kodari.zombieapocalypse.command;

import dev.kodari.zombieapocalypse.trader.TraderOffer;
import dev.kodari.zombieapocalypse.trader.TraderService;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

public final class TradeCommand implements CommandExecutor, TabCompleter {
    private final TraderService traders;

    public TradeCommand(TraderService traders) {
        this.traders = traders;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            for (TraderOffer offer : traders.offers()) {
                sender.sendMessage(ChatColor.YELLOW + offer.itemId() + ChatColor.GRAY + " buy=" + offer.buyPrice()
                        + " sell=" + offer.sellPrice() + " stock=" + (offer.stock() < 0 ? "unlimited" : offer.stock()));
            }
            if (!traders.hasEconomyAdapter()) {
                sender.sendMessage(ChatColor.GRAY + "Trading is disabled until an EconomyAdapter is registered.");
            }
            return true;
        }
        if (!(sender instanceof Player player) || args.length < 2 || args.length > 3
                || !(args[0].equalsIgnoreCase("buy") || args[0].equalsIgnoreCase("sell"))) {
            sender.sendMessage(ChatColor.RED + "Usage: /ztrade list|buy <item> [amount]|sell <item> [amount]");
            return true;
        }
        int amount = 1;
        if (args.length == 3) {
            try {
                amount = Integer.parseInt(args[2]);
            } catch (NumberFormatException exception) {
                sender.sendMessage(ChatColor.RED + "Amount must be a whole number.");
                return true;
            }
        }
        TraderService.TradeResult result = args[0].equalsIgnoreCase("buy")
                ? traders.buy(player, args[1], amount) : traders.sell(player, args[1], amount);
        sender.sendMessage(switch (result) {
            case SUCCESS -> ChatColor.GREEN + "Trade completed. ";
            case DISABLED -> ChatColor.RED + "Trading is disabled.";
            case UNKNOWN_OFFER -> ChatColor.RED + "No offer exists for that item.";
            case INVALID_AMOUNT -> ChatColor.RED + "Amount must be between 1 and 64.";
            case OUT_OF_STOCK -> ChatColor.RED + "That item is out of stock.";
            case NO_ECONOMY_ADAPTER -> ChatColor.RED + "No economy adapter is registered.";
            case INSUFFICIENT_FUNDS -> ChatColor.RED + "Payment failed or the buy price is zero.";
            case NOT_SELLABLE -> ChatColor.RED + "That item cannot be sold.";
            case NOT_ENOUGH_ITEMS -> ChatColor.RED + "You do not have enough of that custom item.";
            case INVALID_ITEM -> ChatColor.RED + "The configured item or currency transaction is invalid.";
        });
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("list", "buy", "sell").stream().filter(value -> value.startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("buy") || args[0].equalsIgnoreCase("sell"))) {
            return traders.offers().stream().map(TraderOffer::itemId)
                    .filter(value -> value.startsWith(args[1].toLowerCase())).toList();
        }
        return List.of();
    }
}