package dev.kodari.zombieapocalypse;

import dev.kodari.zombieapocalypse.command.ZombieCommand;
import dev.kodari.zombieapocalypse.command.RecipeCommand;
import dev.kodari.zombieapocalypse.communication.RadioService;
import dev.kodari.zombieapocalypse.condition.SurvivalConditionManager;
import dev.kodari.zombieapocalypse.config.ConfigManager;
import dev.kodari.zombieapocalypse.device.PowerCctvService;
import dev.kodari.zombieapocalypse.environment.AcidRainService;
import dev.kodari.zombieapocalypse.infection.InfectionManager;
import dev.kodari.zombieapocalypse.item.CustomItemService;
import dev.kodari.zombieapocalypse.item.FirearmService;
import dev.kodari.zombieapocalypse.item.LootSearchService;
import dev.kodari.zombieapocalypse.item.SurvivalItemService;
import dev.kodari.zombieapocalypse.listener.GameplayListener;
import dev.kodari.zombieapocalypse.noise.NoiseService;
import dev.kodari.zombieapocalypse.navigation.WaypointService;
import dev.kodari.zombieapocalypse.npc.SurvivorNpcService;
import dev.kodari.zombieapocalypse.protection.ProtectionService;
import dev.kodari.zombieapocalypse.quest.QuestService;
import dev.kodari.zombieapocalypse.trader.TraderService;
import dev.kodari.zombieapocalypse.command.TradeCommand;
import dev.kodari.zombieapocalypse.command.StorageCommand;
import dev.kodari.zombieapocalypse.command.TeamCommand;
import dev.kodari.zombieapocalypse.breach.BreachService;
import dev.kodari.zombieapocalypse.command.DashboardCommand;
import dev.kodari.zombieapocalypse.recipe.RecipeRegistry;
import dev.kodari.zombieapocalypse.regional.RegionalInfectionService;
import dev.kodari.zombieapocalypse.storage.InfectionStore;
import dev.kodari.zombieapocalypse.storage.RegionalInfectionStore;
import dev.kodari.zombieapocalypse.storage.StorageService;
import dev.kodari.zombieapocalypse.storage.StorageStore;
import dev.kodari.zombieapocalypse.storage.SurvivalConditionStore;
import dev.kodari.zombieapocalypse.team.TeamService;
import dev.kodari.zombieapocalypse.zombie.ZombieService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

public final class ZombieApocalypsePlugin extends JavaPlugin {
    private ConfigManager configManager;
    private InfectionStore infectionStore;
    private SurvivalConditionStore survivalConditionStore;
    private InfectionManager infectionManager;
    private SurvivalConditionManager survivalConditions;
    private ZombieService zombieService;
    private CustomItemService itemService;
    private FirearmService firearmService;
    private LootSearchService lootSearchService;
    private SurvivalItemService survivalItemService;
    private RecipeRegistry recipeRegistry;
    private NoiseService noiseService;
    private RadioService radioService;
    private WaypointService waypointService;
    private ProtectionService protectionService;
    private TraderService traderService;
    private StorageStore storageStore;
    private StorageService storageService;
    private RegionalInfectionStore regionalInfectionStore;
    private RegionalInfectionService regionalInfectionService;
    private AcidRainService acidRainService;
    private PowerCctvService powerCctvService;
    private QuestService questService;
    private SurvivorNpcService survivorNpcService;
    private TeamService teamService;
    private BreachService breachService;
    private volatile boolean debugOverride;

    @Override
    public void onEnable() {
        migrateLegacyDataFolders();
        printStartupBanner();
        saveDefaultConfig();
        configManager = new ConfigManager(this);
        if (!configManager.reload()) {
            getLogger().severe("Configuration is invalid; refusing to start with unsafe settings.");
            getLogger().severe("Startup status: FAILED (plugin disabled).");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        infectionStore = new InfectionStore(this);
        infectionManager = new InfectionManager(this, configManager, infectionStore);
        survivalConditionStore = new SurvivalConditionStore(this);
        survivalConditions = new SurvivalConditionManager(this, configManager, survivalConditionStore);
        zombieService = new ZombieService(this, configManager);
        itemService = new CustomItemService(this, configManager);
        teamService = new TeamService(this, configManager);
        breachService = new BreachService(this, configManager, itemService);
        lootSearchService = new LootSearchService(this, configManager, itemService);
        storageStore = new StorageStore(this);
        storageService = new StorageService(this, configManager, itemService, storageStore);
        storageService.setTeamResolver(teamService);
        noiseService = new NoiseService(configManager, zombieService);
        firearmService = new FirearmService(this, configManager, itemService, noiseService, zombieService);
        radioService = new RadioService(configManager, noiseService, itemService);
        radioService.setTeamResolver(teamService);
        waypointService = new WaypointService(this);
        protectionService = new ProtectionService(configManager, itemService);
        acidRainService = new AcidRainService(this, configManager, protectionService, infectionManager);
        acidRainService.start();
        powerCctvService = new PowerCctvService(this, configManager);
        traderService = new TraderService(this, configManager, itemService);
        questService = new QuestService(this, configManager, itemService);
        survivorNpcService = new SurvivorNpcService(this, configManager, traderService, questService);
        regionalInfectionStore = new RegionalInfectionStore(this);
        regionalInfectionService = new RegionalInfectionService(this, configManager, regionalInfectionStore);
        survivalItemService = new SurvivalItemService(this, configManager, infectionManager, survivalConditions,
                itemService, radioService, waypointService);
        recipeRegistry = new RecipeRegistry(this, configManager, itemService);
        recipeRegistry.reload();

        GameplayListener listener = new GameplayListener(this, configManager, infectionManager, survivalConditions, zombieService,
                itemService, firearmService, survivalItemService, protectionService, storageService, noiseService, regionalInfectionService);
        Bukkit.getPluginManager().registerEvents(listener, this);
        Bukkit.getPluginManager().registerEvents(powerCctvService, this);
        Bukkit.getPluginManager().registerEvents(breachService, this);
        Bukkit.getPluginManager().registerEvents(questService, this);
        Bukkit.getPluginManager().registerEvents(survivorNpcService, this);
        Bukkit.getPluginManager().registerEvents(lootSearchService, this);
        Bukkit.getPluginManager().registerEvents(storageService, this);
        zombieService.start();

        ZombieCommand command = new ZombieCommand(this, configManager, infectionManager, zombieService, recipeRegistry, traderService,
                regionalInfectionService);
        getCommand("zombie").setExecutor(command);
        getCommand("zombie").setTabCompleter(command);

        RecipeCommand recipeCommand = new RecipeCommand(configManager, itemService, recipeRegistry);
        Bukkit.getPluginManager().registerEvents(recipeCommand, this);
        getCommand("zrecipe").setExecutor(recipeCommand);
        getCommand("zrecipe").setTabCompleter(recipeCommand);

        TradeCommand tradeCommand = new TradeCommand(traderService);
        getCommand("ztrade").setExecutor(tradeCommand);
        getCommand("ztrade").setTabCompleter(tradeCommand);

        StorageCommand storageCommand = new StorageCommand(storageService);
        getCommand("zstorage").setExecutor(storageCommand);
        getCommand("zstorage").setTabCompleter(storageCommand);

        TeamCommand teamCommand = new TeamCommand(teamService);
        getCommand("zteam").setExecutor(teamCommand);
        getCommand("zteam").setTabCompleter(teamCommand);

        DashboardCommand dashboardCommand = new DashboardCommand(this);
        Bukkit.getPluginManager().registerEvents(dashboardCommand, this);
        getCommand("zdashboard").setExecutor(dashboardCommand);

        if (Bukkit.getPluginManager().getPlugin("WorldGuard") != null) {
            getLogger().warning("WorldGuard is present; breaching relies on BlockBreakEvent protection checks, so verify region denials on your server.");
        }
        logStartupSummary();
    }

    private void printStartupBanner() {
        var console = Bukkit.getConsoleSender();
        console.sendMessage(ChatColor.DARK_GREEN + "+----------------------------------------------------------+");
        console.sendMessage(ChatColor.GOLD + "| ZApocalypseCore                                          |");
        console.sendMessage(ChatColor.YELLOW + "| Made By @slimexplays                                     |");
        console.sendMessage(ChatColor.YELLOW + "| SlimeX Development                                       |");
        console.sendMessage(ChatColor.AQUA + "| https://dsc.gg/slimexdev                                 |");
        console.sendMessage(ChatColor.GRAY + "| Minecraft: 26.3                 Java: 25                 |");
        console.sendMessage(ChatColor.DARK_GREEN + "+----------------------------------------------------------+");
        console.sendMessage(ChatColor.YELLOW + "Status: Initializing...");
    }

    private void migrateLegacyDataFolders() {
        File dataFolder = getDataFolder();
        File parentFolder = dataFolder.getParentFile();
        if (parentFolder == null) {
            return;
        }
        Path destinationRoot = dataFolder.toPath();
        for (String legacyName : new String[]{"Ultra Advanced Zombie Apocalypse", "ZombieApocalypse"}) {
            Path sourceRoot = new File(parentFolder, legacyName).toPath();
            if (!Files.isDirectory(sourceRoot) || sourceRoot.equals(destinationRoot)) {
                continue;
            }
            try (Stream<Path> sourcePaths = Files.walk(sourceRoot)) {
                for (Path source : (Iterable<Path>) sourcePaths::iterator) {
                    Path destination = destinationRoot.resolve(sourceRoot.relativize(source));
                    if (Files.isDirectory(source)) {
                        Files.createDirectories(destination);
                    } else if (!Files.exists(destination)) {
                        Files.createDirectories(destination.getParent());
                        Files.copy(source, destination);
                    }
                }
                getLogger().info("Copied legacy data from " + sourceRoot + " without overwriting existing files.");
            } catch (IOException exception) {
                getLogger().warning("Could not fully migrate legacy data from " + sourceRoot + ": " + exception.getMessage());
            }
        }
    }

    private void logStartupSummary() {
        getLogger().info("Plugin version: " + getDescription().getVersion());
        getLogger().info("Detected server: " + Bukkit.getVersion());
        getLogger().info("Detected server/API version: " + Bukkit.getBukkitVersion());
        getLogger().info("Java runtime: " + Runtime.version().feature());
        getLogger().info("Initialized systems: gameplay listeners, zombie service task, and command handlers.");
        getLogger().info("Data services: infection, survival conditions, regional infection, and storage are loading asynchronously.");
        if (Bukkit.getPluginManager().getPlugin("WorldGuard") != null) {
            getLogger().info("Optional integrations: WorldGuard detected; breaching delegates protection checks through BlockBreakEvent.");
        } else {
            getLogger().info("Optional integrations: WorldGuard not detected; no optional integration active.");
        }
        getLogger().info("Startup status: Initialization completed; asynchronous data loading is in progress.");
    }

    @Override
    public void onDisable() {
        if (infectionManager != null) {
            infectionManager.shutdown();
        }
        if (survivalConditions != null) {
            survivalConditions.shutdown();
        }
        if (storageService != null) {
            storageService.shutdown();
        }
        if (zombieService != null) {
            zombieService.shutdown();
        }
        if (firearmService != null) {
            firearmService.close();
        }
        if (lootSearchService != null) {
            lootSearchService.close();
        }
        if (regionalInfectionService != null) {
            regionalInfectionService.close();
        }
        if (acidRainService != null) {
            acidRainService.close();
        }
        if (powerCctvService != null) {
            powerCctvService.close();
        }
        if (questService != null) {
            questService.close();
        }
        if (teamService != null) {
            teamService.close();
        }
        if (breachService != null) {
            breachService.close();
        }
        if (infectionStore != null) {
            infectionStore.close();
        }
        if (survivalConditionStore != null) {
            survivalConditionStore.close();
        }
        if (storageStore != null) {
            storageStore.close();
        }
        if (regionalInfectionStore != null) {
            regionalInfectionStore.close();
        }
    }

    public ConfigManager config() {
        return configManager;
    }

    public InfectionManager infections() {
        return infectionManager;
    }

    public SurvivalConditionManager conditions() {
        return survivalConditions;
    }

    public ZombieService zombies() {
        return zombieService;
    }

    public CustomItemService items() {
        return itemService;
    }

    public SurvivalItemService survivalItems() {
        return survivalItemService;
    }

    public RecipeRegistry recipes() {
        return recipeRegistry;
    }

    public RadioService radios() {
        return radioService;
    }

    public NoiseService noise() {
        return noiseService;
    }

    public WaypointService waypoints() {
        return waypointService;
    }

    public ProtectionService protection() {
        return protectionService;
    }

    public TraderService traders() {
        return traderService;
    }

    public StorageService storages() {
        return storageService;
    }

    public RegionalInfectionService regionalInfection() {
        return regionalInfectionService;
    }

    public PowerCctvService powerCctv() {
        return powerCctvService;
    }

    public QuestService quests() {
        return questService;
    }

    public SurvivorNpcService survivorNpcs() {
        return survivorNpcService;
    }

    public TeamService teams() {
        return teamService;
    }

    public boolean isDebugEnabled() {
        return debugOverride || configManager.getBoolean("general.debug", false);
    }

    public boolean toggleDebug() {
        debugOverride = !isDebugEnabled();
        return isDebugEnabled();
    }
}