package dev.kodari.zombieapocalypse.listener;

import com.cryptomorin.xseries.XPotion;
import dev.kodari.zombieapocalypse.ZombieApocalypsePlugin;
import dev.kodari.zombieapocalypse.condition.SurvivalConditionManager;
import dev.kodari.zombieapocalypse.config.ConfigManager;
import dev.kodari.zombieapocalypse.infection.InfectionManager;
import dev.kodari.zombieapocalypse.item.CustomItemService;
import dev.kodari.zombieapocalypse.item.FirearmService;
import dev.kodari.zombieapocalypse.item.SurvivalItemService;
import dev.kodari.zombieapocalypse.noise.NoiseService;
import dev.kodari.zombieapocalypse.protection.ProtectionService;
import dev.kodari.zombieapocalypse.regional.RegionalInfectionService;
import dev.kodari.zombieapocalypse.storage.StorageService;
import dev.kodari.zombieapocalypse.zombie.ZombieService;
import dev.kodari.zombieapocalypse.zombie.ZombieVariant;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.concurrent.ThreadLocalRandom;

public final class GameplayListener implements Listener {
    private final ZombieApocalypsePlugin plugin;
    private final ConfigManager config;
    private final InfectionManager infections;
    private final SurvivalConditionManager conditions;
    private final ZombieService zombies;
    private final CustomItemService items;
    private final FirearmService firearms;
    private final SurvivalItemService survivalItems;
    private final ProtectionService protection;
    private final StorageService storages;
    private final NoiseService noise;
    private final RegionalInfectionService regionalInfection;

    public GameplayListener(ZombieApocalypsePlugin plugin, ConfigManager config, InfectionManager infections,
                            SurvivalConditionManager conditions, ZombieService zombies, CustomItemService items,
                            FirearmService firearms,
                            SurvivalItemService survivalItems, ProtectionService protection, StorageService storages,
                            NoiseService noise, RegionalInfectionService regionalInfection) {
        this.plugin = plugin;
        this.config = config;
        this.infections = infections;
        this.conditions = conditions;
        this.zombies = zombies;
        this.items = items;
        this.firearms = firearms;
        this.survivalItems = survivalItems;
        this.protection = protection;
        this.storages = storages;
        this.noise = noise;
        this.regionalInfection = regionalInfection;
    }

    @EventHandler(ignoreCancelled = true)
    public void onZombieHit(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player attacker
                && firearms.isGun(items.getItemId(attacker.getInventory().getItemInMainHand()))
                && !firearms.isApplyingGunDamage(attacker)) {
            event.setCancelled(true);
            return;
        }
        if (!(event.getDamager() instanceof Zombie zombie) || !(event.getEntity() instanceof Player player)
                || !config.isWorldEnabled(player.getWorld())) {
            return;
        }
        if (config.getBoolean("infection.enabled", true)
                && ThreadLocalRandom.current().nextDouble()
                < Math.min(1, protection.infectionChance(player, config.getDouble("infection.bite-probability", 0.12)
                * regionalInfection.infectionChanceMultiplier(player.getLocation())))) {
            infections.infect(player);
        }
        if (zombies.getVariant(zombie) == ZombieVariant.TOXIC && !protection.protects(player, "toxic")) {
            XPotion.matchXPotion("POISON").map(potion -> potion.buildPotionEffect(80, 0)).ifPresent(player::addPotionEffect);
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() != null && event.getFrom().getBlockX() != event.getTo().getBlockX()
                || event.getTo() != null && event.getFrom().getBlockZ() != event.getTo().getBlockZ()) {
            noise.movement(event.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        noise.blockBreak(event.getPlayer());
    }

    @EventHandler
    public void onTarget(EntityTargetLivingEntityEvent event) {
        zombies.onTarget(event);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        for (org.bukkit.entity.Entity entity : event.getChunk().getEntities()) {
            zombies.trackIfCustom(entity);
        }
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        zombies.onChunkUnload(event.getChunk());
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof Zombie zombie) {
            ZombieVariant variant = zombies.getVariant(zombie);
            if (variant != null) {
                items.rollZombieDrops(zombie, variant);
                zombies.onEntityDeath(zombie);
            }
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        conditions.onDeath(event.getEntity());
        if (infections.consumeInfectionDeath(event.getEntity())) {
            zombies.spawnRevenant(event.getEntity().getLocation());
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        conditions.onJoin(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        firearms.cancelPlayer(event.getPlayer());
    }

    @EventHandler
    public void onPlayerAnimation(PlayerAnimationEvent event) {
        firearms.handleSwing(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onSprintToggle(PlayerToggleSprintEvent event) {
        if (event.isSprinting() && !conditions.canSprint(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (!firearms.handleInteract(event) && !storages.handleInteract(event)) {
            survivalItems.handleInteract(event);
        }
    }
}