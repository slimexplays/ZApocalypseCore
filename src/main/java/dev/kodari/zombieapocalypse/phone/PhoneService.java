package dev.kodari.zombieapocalypse.phone;

import com.cryptomorin.xseries.XMaterial;
import dev.kodari.zombieapocalypse.ZombieApocalypsePlugin;
import dev.kodari.zombieapocalypse.communication.RadioService;
import dev.kodari.zombieapocalypse.config.ConfigManager;
import dev.kodari.zombieapocalypse.item.CustomItemService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public final class PhoneService implements Listener, CommandExecutor, TabCompleter {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
            .withZone(ZoneId.systemDefault());
    private final ZombieApocalypsePlugin plugin;
    private final ConfigManager config;
    private final CustomItemService items;
    private final RadioService radios;
    private final File file;
    private final NamespacedKey ownerKey;
    private final NamespacedKey phoneIdKey;
    private final Map<UUID, Long> lastMessageAt = new LinkedHashMap<>();
    private final YamlConfiguration data;
    private final BukkitTask batteryTask;
    private boolean batteryDataDirty;

    public PhoneService(ZombieApocalypsePlugin plugin, ConfigManager config, CustomItemService items, RadioService radios) {
        this.plugin = plugin;
        this.config = config;
        this.items = items;
        this.radios = radios;
        this.file = new File(plugin.getDataFolder(), "phones.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
        this.ownerKey = new NamespacedKey(plugin, "phone_owner");
        this.phoneIdKey = new NamespacedKey(plugin, "phone_id");
        this.batteryTask = Bukkit.getScheduler().runTaskTimer(plugin, this::drainOnlineBatteries, 1200L, 1200L);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        ensureAccount(player.getUniqueId());
        ensurePhoneItem(player);
        long lastOnline = data.getLong(playerPath(player.getUniqueId()) + ".last-online", 0L);
        int unread = unreadMessages(player.getUniqueId(), lastOnline);
        data.set(playerPath(player.getUniqueId()) + ".last-online", System.currentTimeMillis());
        save();
        if (unread > 0 && notificationsEnabled(player.getUniqueId())) {
            player.sendMessage(color("&e[Phone] You have " + unread + " unread message(s). Use /zphone history <player>."));
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            ensureAccount(event.getPlayer().getUniqueId());
            ensurePhoneItem(event.getPlayer());
        });
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        ItemStack held = event.getItem();
        if (!isPhoneItem(held)) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!config.getBoolean("phone.enabled", true)) {
            message(player, "&cPhones are disabled.");
            return;
        }
        ensureAccount(player.getUniqueId());
        ensurePhoneItem(player);
        if (!hasPhone(player)) {
            message(player, "&cThis phone is not registered to you.");
            return;
        }
        openPhone(player);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof PhoneHolder holder) {
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player) || !holder.owner.equals(player.getUniqueId())) {
                return;
            }
            if (event.getRawSlot() != 22 && !canUse(player)) {
                return;
            }
            if (event.getRawSlot() == 22) {
                player.closeInventory();
            } else if (event.getRawSlot() == 10) {
                showInbox(player);
            } else if (event.getRawSlot() == 12) {
                showContacts(player);
            } else if (event.getRawSlot() == 14) {
                showGroups(player);
            } else if (event.getRawSlot() == 16) {
                sendSos(player, "");
            }
            return;
        }
        if (isPhoneItem(event.getCurrentItem()) || isPhoneItem(event.getCursor())) {
            event.setCancelled(true);
            return;
        }
        if (event.getHotbarButton() >= 0 && event.getWhoClicked() instanceof Player player
                && isPhoneItem(player.getInventory().getItem(event.getHotbarButton()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof PhoneHolder
                || isPhoneItem(event.getOldCursor())
                || event.getNewItems().values().stream().anyMatch(this::isPhoneItem)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (isPhoneItem(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent event) {
        ItemStack item = event.getItem().getItemStack();
        if (!isPhoneItem(item)) {
            return;
        }
        Entity entity = event.getEntity();
        if (!(entity instanceof Player player) || !ownsPhone(item, player.getUniqueId()) || hasPhone(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(this::isPhoneItem);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            message(sender, "&cThis command requires an in-game player.");
            return true;
        }
        if (!config.getBoolean("phone.enabled", true)) {
            message(player, "&cPhones are disabled.");
            return true;
        }
        ensureAccount(player.getUniqueId());
        ensurePhoneItem(player);
        if (!hasPhone(player)) {
            message(player, "&cYour registered phone is not in your inventory.");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("open")) {
            openPhone(player);
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "msg" -> commandMessage(player, args);
            case "history" -> commandHistory(player, args);
            case "contact" -> commandContact(player, args);
            case "block", "unblock", "mute", "unmute" -> commandRelationship(player, args, action);
            case "group" -> commandGroup(player, args);
            case "sos" -> sendSos(player, join(args, 1));
            case "settings" -> commandSettings(player, args);
            case "battery" -> message(player, "&bPhone battery: &f" + battery(player.getUniqueId()) + "%");
            case "recharge" -> recharge(player);
            default -> showHelp(player);
        }
        return true;
    }

    private void commandMessage(Player sender, String[] args) {
        if (args.length < 3) {
            message(sender, "&eUsage: /zphone msg <player> <message>");
            return;
        }
        UUID target = resolvePlayer(args[1]);
        if (target == null) {
            message(sender, "&cThat player has not joined this server.");
            return;
        }
        String body = cleanMessage(join(args, 2));
        if (body == null) {
            message(sender, "&cMessage is empty or exceeds the configured length.");
            return;
        }
        sendDirect(sender, target, body);
    }

    private void sendDirect(Player sender, UUID target, String body) {
        UUID senderId = sender.getUniqueId();
        if (target.equals(senderId)) {
            message(sender, "&cYou cannot message yourself.");
            return;
        }
        if (isBlocked(target, senderId)) {
            message(sender, "&cThat player is not accepting your messages.");
            return;
        }
        if (data.getBoolean(playerPath(target) + ".settings.contacts-only", config.getBoolean("phone.contacts-only", false))
                && !isContact(target, senderId)) {
            message(sender, "&cThat player only accepts messages from contacts.");
            return;
        }
        if (!allowTransmission(sender)) {
            return;
        }
        if (!spendBattery(sender, config.getInt("phone.battery.message-cost", 1))) {
            return;
        }
        long now = System.currentTimeMillis();
        appendDirectHistory(senderId, target, sender.getName(), body, now);
        OfflinePlayer targetPlayer = Bukkit.getOfflinePlayer(target);
        Player online = Bukkit.getPlayer(target);
        message(sender, "&b[Phone → " + displayName(targetPlayer) + "] &f" + body);
        if (online != null && notificationsEnabled(target) && !isMuted(target, senderId)) {
            message(online, "&b[Phone ← " + sender.getName() + "] &f" + body);
        }
        save();
    }

    private void commandHistory(Player player, String[] args) {
        if (args.length != 2) {
            message(player, "&eUsage: /zphone history <player>");
            return;
        }
        UUID target = resolvePlayer(args[1]);
        if (target == null) {
            message(player, "&cThat player has not joined this server.");
            return;
        }
        List<Map<?, ?>> messages = data.getMapList(playerPath(player.getUniqueId()) + ".history." + target);
        message(player, "&8--- &bPhone history: " + displayName(Bukkit.getOfflinePlayer(target)) + " &8---");
        int start = Math.max(0, messages.size() - 10);
        for (int i = start; i < messages.size(); i++) {
            Map<?, ?> record = messages.get(i);
            message(player, "&7[" + time(record) + "] &f" + (record.get("sender_name") == null ? "Unknown" : record.get("sender_name")) + ": "
                    + (record.get("text") == null ? "" : record.get("text")));
        }
        data.set(playerPath(player.getUniqueId()) + ".last-online", System.currentTimeMillis());
        save();
    }

    private void commandContact(Player player, String[] args) {
        String path = playerPath(player.getUniqueId());
        if (args.length == 1 || args[1].equalsIgnoreCase("list")) {
            showContacts(player);
            return;
        }
        if (args.length != 3 || !(args[1].equalsIgnoreCase("add") || args[1].equalsIgnoreCase("remove"))) {
            message(player, "&eUsage: /zphone contact add|remove|list <player>");
            return;
        }
        UUID target = resolvePlayer(args[2]);
        if (target == null || target.equals(player.getUniqueId())) {
            message(player, "&cChoose a player who has joined this server.");
            return;
        }
        List<String> contacts = new ArrayList<>(data.getStringList(path + ".contacts"));
        String targetId = target.toString();
        if (args[1].equalsIgnoreCase("add")) {
            if (!contacts.contains(targetId)) {
                contacts.add(targetId);
            }
            data.set(path + ".contact-names." + targetId, displayName(Bukkit.getOfflinePlayer(target)));
            message(player, "&aAdded " + displayName(Bukkit.getOfflinePlayer(target)) + " to your contacts.");
        } else {
            contacts.remove(targetId);
            data.set(path + ".contact-names." + targetId, null);
            message(player, "&eRemoved that contact.");
        }
        data.set(path + ".contacts", contacts);
        save();
    }

    private void commandRelationship(Player player, String[] args, String action) {
        if (args.length != 2) {
            message(player, "&eUsage: /zphone " + action + " <player>");
            return;
        }
        UUID target = resolvePlayer(args[1]);
        if (target == null || target.equals(player.getUniqueId())) {
            message(player, "&cChoose another player who has joined this server.");
            return;
        }
        String path = playerPath(player.getUniqueId());
        String listName = action.equals("block") || action.equals("unblock") ? "blocked" : "muted";
        List<String> values = new ArrayList<>(data.getStringList(path + "." + listName));
        boolean add = action.equals("block") || action.equals("mute");
        if (add && !values.contains(target.toString())) {
            values.add(target.toString());
        } else if (!add) {
            values.remove(target.toString());
        }
        data.set(path + "." + listName, values);
        save();
        message(player, add ? "&a" + displayName(Bukkit.getOfflinePlayer(target)) + " added to your " + listName + " list."
                : "&e" + displayName(Bukkit.getOfflinePlayer(target)) + " removed from your " + listName + " list.");
    }

    private void commandGroup(Player player, String[] args) {
        if (args.length < 2) {
            message(player, "&e/zphone group create|invite|accept|leave|list|msg ...");
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        String path = playerPath(player.getUniqueId());
        switch (action) {
            case "create" -> {
                if (args.length != 3 || !args[2].matches("[A-Za-z0-9_-]{3,16}")) {
                    message(player, "&eUsage: /zphone group create <3-16 character name>");
                    return;
                }
                String id = UUID.randomUUID().toString();
                String groupPath = "groups." + id;
                data.set(groupPath + ".name", args[2]);
                data.set(groupPath + ".owner", player.getUniqueId().toString());
                data.set(groupPath + ".members", List.of(player.getUniqueId().toString()));
                message(player, "&aGroup created. Its ID is &f" + id + "&a; share it only with invited players.");
                save();
            }
            case "invite" -> {
                if (args.length != 4) {
                    message(player, "&eUsage: /zphone group invite <group-id> <player>");
                    return;
                }
                String groupPath = "groups." + args[2];
                if (!validGroupId(args[2]) || !player.getUniqueId().toString().equals(data.getString(groupPath + ".owner"))) {
                    message(player, "&cOnly a group's owner can invite players.");
                    return;
                }
                UUID target = resolvePlayer(args[3]);
                if (target == null) {
                    message(player, "&cThat player has not joined this server.");
                    return;
                }
                List<String> invites = new ArrayList<>(data.getStringList(playerPath(target) + ".group-invites"));
                if (!invites.contains(args[2])) {
                    invites.add(args[2]);
                }
                data.set(playerPath(target) + ".group-invites", invites);
                save();
                message(player, "&aGroup invitation sent to " + displayName(Bukkit.getOfflinePlayer(target)) + ".");
                Player online = Bukkit.getPlayer(target);
                if (online != null && notificationsEnabled(target)) {
                    message(online, "&b[Phone] Group invitation from " + player.getName() + ". Use /zphone group accept " + args[2]);
                }
            }
            case "accept" -> acceptGroup(player, args);
            case "leave" -> leaveGroup(player, args);
            case "list" -> showGroups(player);
            case "msg" -> groupMessage(player, args);
            case "history" -> groupHistory(player, args);
            default -> message(player, "&e/zphone group create|invite|accept|leave|list|msg ...");
        }
    }

    private void acceptGroup(Player player, String[] args) {
        if (args.length != 3 || !validGroupId(args[2])) {
            message(player, "&eUsage: /zphone group accept <group-id>");
            return;
        }
        String id = args[2];
        String invitePath = playerPath(player.getUniqueId()) + ".group-invites";
        List<String> invites = new ArrayList<>(data.getStringList(invitePath));
        String groupPath = "groups." + id;
        if (!invites.remove(id) || !data.contains(groupPath + ".owner")) {
            message(player, "&cYou do not have a valid invitation for that group.");
            return;
        }
        List<String> members = new ArrayList<>(data.getStringList(groupPath + ".members"));
        if (!members.contains(player.getUniqueId().toString())) {
            members.add(player.getUniqueId().toString());
        }
        data.set(groupPath + ".members", members);
        data.set(invitePath, invites);
        save();
        message(player, "&aJoined group " + data.getString(groupPath + ".name", id) + ".");
    }

    private void leaveGroup(Player player, String[] args) {
        if (args.length != 3 || !validGroupId(args[2])) {
            message(player, "&eUsage: /zphone group leave <group-id>");
            return;
        }
        String groupPath = "groups." + args[2];
        List<String> members = new ArrayList<>(data.getStringList(groupPath + ".members"));
        if (!members.remove(player.getUniqueId().toString())) {
            message(player, "&cYou are not in that group.");
            return;
        }
        data.set(groupPath + ".members", members);
        if (members.isEmpty()) {
            data.set(groupPath, null);
        }
        save();
        message(player, "&eYou left the group.");
    }

    private void groupMessage(Player sender, String[] args) {
        if (args.length < 4 || !validGroupId(args[2])) {
            message(sender, "&eUsage: /zphone group msg <group-id> <message>");
            return;
        }
        String body = cleanMessage(join(args, 3));
        if (body == null) {
            message(sender, "&cMessage is empty or exceeds the configured length.");
            return;
        }
        String groupPath = "groups." + args[2];
        List<String> members = data.getStringList(groupPath + ".members");
        if (!members.contains(sender.getUniqueId().toString())) {
            message(sender, "&cYou are not a member of that group.");
            return;
        }
        if (!allowTransmission(sender)) {
            return;
        }
        if (!spendBattery(sender, config.getInt("phone.battery.message-cost", 1))) {
            return;
        }
        long now = System.currentTimeMillis();
        List<Map<?, ?>> history = new ArrayList<>(data.getMapList(groupPath + ".messages"));
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("sender", sender.getUniqueId().toString());
        record.put("sender_name", sender.getName());
        record.put("text", body);
        record.put("timestamp", now);
        history.add(record);
        trimHistory(history);
        data.set(groupPath + ".messages", history);
        for (String memberId : members) {
            UUID member;
            try {
                member = UUID.fromString(memberId);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            Player online = Bukkit.getPlayer(member);
            if (online != null && !member.equals(sender.getUniqueId()) && notificationsEnabled(member)
                    && !isMuted(member, sender.getUniqueId())) {
                message(online, "&b[Group " + data.getString(groupPath + ".name", "Chat") + "] &f"
                        + sender.getName() + ": " + body);
            }
        }
        save();
        message(sender, "&aGroup message sent.");
    }

    private void groupHistory(Player player, String[] args) {
        if (args.length != 3 || !validGroupId(args[2])) {
            message(player, "&eUsage: /zphone group history <group-id>");
            return;
        }
        String path = "groups." + args[2];
        if (!data.getStringList(path + ".members").contains(player.getUniqueId().toString())) {
            message(player, "&cYou are not a member of that group.");
            return;
        }
        List<Map<?, ?>> history = data.getMapList(path + ".messages");
        message(player, "&8--- &aGroup history: " + data.getString(path + ".name", "Group") + " &8---");
        for (int i = Math.max(0, history.size() - 10); i < history.size(); i++) {
            Map<?, ?> record = history.get(i);
            message(player, "&7[" + time(record) + "] &f" + (record.get("sender_name") == null ? "Unknown" : record.get("sender_name")) + ": "
                    + (record.get("text") == null ? "" : record.get("text")));
        }
    }

    private void sendSos(Player player, String details) {
        if (!config.getBoolean("phone.enabled", true)) {
            return;
        }
        if (!allowTransmission(player) || !spendBattery(player, config.getInt("phone.battery.sos-cost", 5))) {
            return;
        }
        int delivered = radios.broadcast(player, "distress");
        if (delivered == 0) {
            message(player, "&cSOS could not be transmitted; the configured radio network is unavailable.");
            return;
        }
        String extra = cleanMessage(details);
        if (extra != null) {
            message(player, "&7Transmitted distress details: " + extra);
        }
        message(player, "&aSOS sent to " + (delivered - 1) + " other survivor(s).");
        save();
    }

    private void commandSettings(Player player, String[] args) {
        if (args.length != 3 || !(args[2].equalsIgnoreCase("on") || args[2].equalsIgnoreCase("off"))) {
            message(player, "&eUsage: /zphone settings notifications|contacts-only <on|off>");
            return;
        }
        String key;
        if (args[1].equalsIgnoreCase("notifications")) {
            key = "notifications";
        } else if (args[1].equalsIgnoreCase("contacts-only")) {
            key = "contacts-only";
        } else {
            message(player, "&cUnknown setting.");
            return;
        }
        boolean enabled = args[2].equalsIgnoreCase("on");
        data.set(playerPath(player.getUniqueId()) + ".settings." + key, enabled);
        save();
        message(player, "&a" + key + " set to " + (enabled ? "on" : "off") + ".");
    }

    private void recharge(Player player) {
        String itemId = config.getString("phone.battery.recharge-item", "components");
        int slot = -1;
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int i = 0; i < contents.length; i++) {
            if (itemId.equals(items.getItemId(contents[i]))) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            message(player, "&cYou need one " + itemId + " to recharge your phone.");
            return;
        }
        int current = battery(player.getUniqueId());
        int max = config.getInt("phone.battery.max", 100);
        if (current >= max) {
            message(player, "&eYour phone battery is already full.");
            return;
        }
        ItemStack cell = contents[slot];
        if (cell.getAmount() == 1) {
            contents[slot] = null;
        } else {
            cell.setAmount(cell.getAmount() - 1);
        }
        player.getInventory().setStorageContents(contents);
        int amount = config.getInt("phone.battery.recharge-amount", 25);
        setBattery(player.getUniqueId(), Math.min(max, current + amount));
        save();
        message(player, "&aPhone recharged to " + battery(player.getUniqueId()) + "%.");
    }

    private void openPhone(Player player) {
        if (!canUse(player)) {
            return;
        }
        PhoneHolder holder = new PhoneHolder(player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(holder, 27, color("&8Survivor Phone"));
        holder.inventory = inventory;
        setCard(inventory, 10, "BOOK", "&bMessages", List.of("&7View recent private messages", "&8/zphone msg <player> <message>"));
        setCard(inventory, 12, "PAPER", "&eContacts", List.of("&7Manage trusted contacts", "&8/zphone contact add <player>"));
        setCard(inventory, 14, "MAP", "&aGroup Chats", List.of("&7Create or access group chats", "&8/zphone group list"));
        setCard(inventory, 16, "REDSTONE_TORCH", "&cSOS", List.of("&7Transmit an emergency radio alert", "&8Battery cost: " + config.getInt("phone.battery.sos-cost", 5)));
        setCard(inventory, 4, "REDSTONE", "&bBattery: " + battery(player.getUniqueId()) + "%", List.of("&7Use /zphone recharge to restore charge."));
        setCard(inventory, 22, "BARRIER", "&7Close", List.of());
        player.openInventory(inventory);
    }

    private void showInbox(Player player) {
        if (!canUse(player)) {
            return;
        }
        String path = playerPath(player.getUniqueId()) + ".history";
        var threads = data.getConfigurationSection(path);
        message(player, "&8--- &bRecent Phone Messages &8---");
        if (threads == null) {
            message(player, "&7No message history yet.");
            return;
        }
        List<Map<?, ?>> latest = new ArrayList<>();
        for (String thread : threads.getKeys(false)) {
            latest.addAll(data.getMapList(path + "." + thread));
        }
        latest.sort((first, second) -> Long.compare(number(second.get("timestamp")), number(first.get("timestamp"))));
        for (int i = 0; i < Math.min(8, latest.size()); i++) {
            Map<?, ?> record = latest.get(i);
            message(player, "&7[" + time(record) + "] &f" + (record.get("sender_name") == null ? "Unknown" : record.get("sender_name")) + ": "
                    + (record.get("text") == null ? "" : record.get("text")));
        }
        data.set(playerPath(player.getUniqueId()) + ".last-online", System.currentTimeMillis());
        save();
    }

    private void showContacts(Player player) {
        message(player, "&8--- &ePhone Contacts &8---");
        List<String> contacts = data.getStringList(playerPath(player.getUniqueId()) + ".contacts");
        if (contacts.isEmpty()) {
            message(player, "&7No contacts. Use /zphone contact add <player>.");
            return;
        }
        for (String contact : contacts) {
            String name = data.getString(playerPath(player.getUniqueId()) + ".contact-names." + contact, contact);
            message(player, "&7- &f" + name);
        }
    }

    private void showGroups(Player player) {
        message(player, "&8--- &aPhone Groups &8---");
        String playerId = player.getUniqueId().toString();
        var groups = data.getConfigurationSection("groups");
        boolean found = false;
        if (groups != null) {
            for (String id : groups.getKeys(false)) {
                String path = "groups." + id;
                if (data.getStringList(path + ".members").contains(playerId)) {
                    message(player, "&7" + data.getString(path + ".name", "Group") + " &8ID: &f" + id);
                    found = true;
                }
            }
        }
        if (!found) {
            message(player, "&7No groups. Use /zphone group create <name>.");
        }
    }

    private void appendDirectHistory(UUID sender, UUID recipient, String senderName, String body, long timestamp) {
        appendHistory(sender, recipient, sender, senderName, body, timestamp);
        appendHistory(recipient, sender, sender, senderName, body, timestamp);
        data.set(playerPath(sender) + ".contact-names." + recipient, displayName(Bukkit.getOfflinePlayer(recipient)));
        data.set(playerPath(recipient) + ".contact-names." + sender, senderName);
    }

    private void appendHistory(UUID owner, UUID thread, UUID sender, String senderName, String body, long timestamp) {
        String path = playerPath(owner) + ".history." + thread;
        List<Map<?, ?>> history = new ArrayList<>(data.getMapList(path));
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("sender", sender.toString());
        record.put("sender_name", senderName);
        record.put("text", body);
        record.put("timestamp", timestamp);
        history.add(record);
        trimHistory(history);
        data.set(path, history);
    }

    private void trimHistory(List<Map<?, ?>> history) {
        int limit = config.getInt("phone.history-limit", 100);
        while (history.size() > limit) {
            history.remove(0);
        }
    }

    private int unreadMessages(UUID player, long after) {
        int unread = 0;
        String path = playerPath(player) + ".history";
        var threads = data.getConfigurationSection(path);
        if (threads != null) {
            for (String thread : threads.getKeys(false)) {
                for (Map<?, ?> record : data.getMapList(path + "." + thread)) {
                    if (number(record.get("timestamp")) > after && !player.toString().equals(record.get("sender"))) {
                        unread++;
                    }
                }
            }
        }
        var groups = data.getConfigurationSection("groups");
        if (groups != null) {
            for (String id : groups.getKeys(false)) {
                String groupPath = "groups." + id;
                if (!data.getStringList(groupPath + ".members").contains(player.toString())) {
                    continue;
                }
                for (Map<?, ?> record : data.getMapList(groupPath + ".messages")) {
                    if (number(record.get("timestamp")) > after && !player.toString().equals(record.get("sender"))) {
                        unread++;
                    }
                }
            }
        }
        return unread;
    }

    private void ensureAccount(UUID player) {
        String path = playerPath(player);
        if (!data.contains(path + ".phone-id")) {
            data.set(path + ".phone-id", UUID.randomUUID().toString());
            data.set(path + ".battery", config.getInt("phone.battery.max", 100));
            data.set(path + ".settings.notifications", config.getBoolean("phone.notifications", true));
            data.set(path + ".settings.contacts-only", config.getBoolean("phone.contacts-only", false));
            save();
        }
    }

    private void ensurePhoneItem(Player player) {
        UUID owner = player.getUniqueId();
        String phoneId = data.getString(playerPath(owner) + ".phone-id");
        if (phoneId == null) {
            return;
        }
        int valid = 0;
        ItemStack[] storage = player.getInventory().getStorageContents();
        for (int slot = 0; slot < storage.length; slot++) {
            ItemStack item = storage[slot];
            if (!isPhoneItem(item)) {
                continue;
            }
            if (!ownsPhone(item, owner) || valid > 0) {
                storage[slot] = null;
            } else {
                valid++;
            }
        }
        player.getInventory().setStorageContents(storage);
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (isPhoneItem(offhand)) {
            if (!ownsPhone(offhand, owner) || valid > 0) {
                player.getInventory().setItemInOffHand(null);
            } else {
                valid++;
            }
        }
        if (valid == 0) {
            ItemStack phone = items.create("phone");
            if (phone == null) {
                plugin.getLogger().warning("Could not create the configured phone item for " + player.getName());
                return;
            }
            phone.setAmount(1);
            ItemMeta meta = phone.getItemMeta();
            meta.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, owner.toString());
            meta.getPersistentDataContainer().set(phoneIdKey, PersistentDataType.STRING, phoneId);
            phone.setItemMeta(meta);
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(phone);
            overflow.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
        }
    }

    private boolean ownsPhone(ItemStack item, UUID owner) {
        if (!isPhoneItem(item) || !item.hasItemMeta()) {
            return false;
        }
        String path = playerPath(owner);
        String actualOwner = item.getItemMeta().getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        String actualId = item.getItemMeta().getPersistentDataContainer().get(phoneIdKey, PersistentDataType.STRING);
        return owner.toString().equals(actualOwner) && actualId != null
                && actualId.equals(data.getString(path + ".phone-id"));
    }

    private boolean isPhoneItem(ItemStack item) {
        return item != null && !item.getType().isAir() && "phone".equals(items.getItemId(item));
    }

    private boolean hasPhone(Player player) {
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (ownsPhone(item, player.getUniqueId())) {
                return true;
            }
        }
        return ownsPhone(player.getInventory().getItemInOffHand(), player.getUniqueId());
    }

    private boolean canUse(Player player) {
        if (!hasPhone(player)) {
            message(player, "&cYou need your registered phone in your inventory.");
            return false;
        }
        if (battery(player.getUniqueId()) <= 0) {
            message(player, "&cYour phone battery is empty. Use /zphone recharge.");
            return false;
        }
        return true;
    }

    private boolean spendBattery(Player player, int cost) {
        if (!canUse(player)) {
            return false;
        }
        int current = battery(player.getUniqueId());
        if (current < cost) {
            message(player, "&cNot enough battery. Recharge your phone.");
            return false;
        }
        setBattery(player.getUniqueId(), current - cost);
        save();
        return true;
    }

    private boolean allowTransmission(Player player) {
        long now = System.currentTimeMillis();
        Long previous = lastMessageAt.get(player.getUniqueId());
        if (previous != null && now - previous < 1000L) {
            message(player, "&cWait a moment before transmitting again.");
            return false;
        }
        lastMessageAt.put(player.getUniqueId(), now);
        return true;
    }

    private void drainOnlineBatteries() {
        int drain = config.getInt("phone.battery.drain-per-minute", 1);
        if (drain > 0) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!hasPhone(player)) {
                    continue;
                }
                int current = battery(player.getUniqueId());
                if (current > 0) {
                    setBattery(player.getUniqueId(), Math.max(0, current - drain));
                    if (battery(player.getUniqueId()) == 0) {
                        message(player, "&cYour phone battery is empty; phone functions are offline.");
                    }
                }
            }
        }
        if (batteryDataDirty) {
            save();
        }
    }

    private int battery(UUID player) {
        return data.getInt(playerPath(player) + ".battery", config.getInt("phone.battery.max", 100));
    }

    private void setBattery(UUID player, int value) {
        data.set(playerPath(player) + ".battery", value);
        batteryDataDirty = true;
    }

    public boolean chargeOnline(Player player, int amount) {
        if (!config.getBoolean("phone.enabled", true) || amount <= 0 || !hasPhone(player)) {
            return false;
        }
        int current = battery(player.getUniqueId());
        int charged = Math.min(config.getInt("phone.battery.max", 100), current + amount);
        if (charged == current) {
            return false;
        }
        setBattery(player.getUniqueId(), charged);
        return true;
    }

    private UUID resolvePlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        if (!offline.hasPlayedBefore() && !data.contains(playerPath(offline.getUniqueId()) + ".phone-id")) {
            return null;
        }
        return offline.getUniqueId();
    }

    private boolean isBlocked(UUID recipient, UUID sender) {
        return data.getStringList(playerPath(recipient) + ".blocked").contains(sender.toString());
    }

    private boolean isMuted(UUID recipient, UUID sender) {
        return data.getStringList(playerPath(recipient) + ".muted").contains(sender.toString());
    }

    private boolean isContact(UUID recipient, UUID sender) {
        return data.getStringList(playerPath(recipient) + ".contacts").contains(sender.toString());
    }

    private boolean notificationsEnabled(UUID player) {
        return data.getBoolean(playerPath(player) + ".settings.notifications", config.getBoolean("phone.notifications", true));
    }

    private boolean validGroupId(String id) {
        try {
            UUID.fromString(id);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private String cleanMessage(String message) {
        String clean = message.replace('\n', ' ').replace('\r', ' ').trim();
        int maxLength = config.getInt("phone.max-message-length", 200);
        return clean.isEmpty() || clean.length() > maxLength ? null : clean;
    }

    private String playerPath(UUID player) {
        return "players." + player;
    }

    private String displayName(OfflinePlayer player) {
        return player.getName() == null ? player.getUniqueId().toString().substring(0, 8) : player.getName();
    }

    private String time(Map<?, ?> record) {
        return TIME_FORMAT.format(Instant.ofEpochMilli(number(record.get("timestamp"))));
    }

    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private String join(String[] args, int start) {
        return String.join(" ", java.util.Arrays.copyOfRange(args, start, args.length));
    }

    private void showHelp(Player player) {
        message(player, "&e/zphone [open|battery|recharge]");
        message(player, "&e/zphone msg <player> <message> | history <player> | contact add|remove|list <player>");
        message(player, "&e/zphone group create|invite|accept|leave|list|msg ... | block|unblock|mute|unmute <player>");
        message(player, "&e/zphone sos [details] | settings notifications|contacts-only <on|off>");
    }

    private void setCard(Inventory inventory, int slot, String material, String name, List<String> lore) {
        ItemStack item = XMaterial.matchXMaterial(material).map(XMaterial::parseItem)
                .orElseGet(() -> XMaterial.matchXMaterial("PAPER").map(XMaterial::parseItem).orElse(null));
        if (item == null) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));
            meta.setLore(lore.stream().map(this::color).collect(Collectors.toList()));
            item.setItemMeta(meta);
        }
        inventory.setItem(slot, item);
    }

    private void message(CommandSender sender, String text) {
        sender.sendMessage(color(text));
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private void save() {
        try {
            data.save(file);
            batteryDataDirty = false;
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not save phone data: " + exception.getMessage());
        }
    }

    public void close() {
        batteryTask.cancel();
        save();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("open", "msg", "history", "contact", "block", "unblock", "mute", "unmute",
                    "group", "sos", "settings", "battery", "recharge"), args[0]);
        }
        if (args.length == 2 && List.of("msg", "history", "block", "unblock", "mute", "unmute").contains(args[0].toLowerCase(Locale.ROOT))) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("contact") && args[1].equalsIgnoreCase("add")) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[2]);
        }
        return List.of();
    }

    private List<String> filter(List<String> values, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower)).collect(Collectors.toList());
    }

    private static final class PhoneHolder implements InventoryHolder {
        private final UUID owner;
        private Inventory inventory;

        private PhoneHolder(UUID owner) {
            this.owner = owner;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}