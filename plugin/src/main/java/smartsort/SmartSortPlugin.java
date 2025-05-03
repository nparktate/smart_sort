package smartsort;

import org.bukkit.plugin.java.JavaPlugin;
import smartsort.ai.AIService;
import smartsort.command.*;
import smartsort.sorting.InventorySorter;
import smartsort.sorting.PlayerInventoryService;
import smartsort.util.DebugLogger;
import smartsort.util.PlayerPreferenceManager;
import smartsort.util.TickSoundManager;

public final class SmartSortPlugin extends JavaPlugin {

    private static SmartSortPlugin instance;
    private ServiceContainer services;
    private AIService aiService;
    private InventorySorter sorter;
    private PlayerInventoryService playerInventoryService;
    private TickSoundManager tickSoundManager;
    private DebugLogger debug;
    private PlayerPreferenceManager preferenceManager;

    public static SmartSortPlugin get() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        migrateConfig();

        // Create service container
        services = new ServiceContainer();

        // Initialize and register components
        debug = new DebugLogger(this);
        debug.initialize(); // Start message queue processing
        services.register(DebugLogger.class, debug);

        aiService = new AIService(this, debug);
        services.register(AIService.class, aiService);

        tickSoundManager = new TickSoundManager(this);
        services.register(TickSoundManager.class, tickSoundManager);

        // Initialize player preference manager
        preferenceManager = new PlayerPreferenceManager(this);
        preferenceManager.loadAllPreferences();
        services.register(PlayerPreferenceManager.class, preferenceManager);

        // Create remaining components
        sorter = new InventorySorter(this, aiService, tickSoundManager, debug);
        services.register(InventorySorter.class, sorter);

        // Initialize player inventory service
        playerInventoryService = new PlayerInventoryService(
            this,
            aiService,
            tickSoundManager,
            debug,
            preferenceManager
        );
        services.register(PlayerInventoryService.class, playerInventoryService);

        // listeners
        getServer().getPluginManager().registerEvents(sorter, this);
        getServer().getPluginManager().registerEvents(tickSoundManager, this);
        getServer()
            .getPluginManager()
            .registerEvents(playerInventoryService, this);

        // commands - unified under SmartSortCommand
        new SmartSortCommand(
            this,
            aiService,
            debug,
            playerInventoryService
        ).register();

        @SuppressWarnings("deprecation")
        String version = getDescription().getVersion();
        getLogger().info("SmartSort " + version + " enabled");
    }

    @Override
    public void onDisable() {
        debug.shutdown(); // Add shutdown call to properly cancel processing task
        tickSoundManager.shutdown();
        aiService.shutdown();

        if (playerInventoryService != null) {
            playerInventoryService.shutdown();
        }

        if (preferenceManager != null) {
            preferenceManager.saveAllPreferences();
        }

        instance = null;
    }

    // Add access method for services
    public <T> T getService(Class<T> type) {
        return services.get(type);
    }

    private void migrateConfig() {
        int currentVersion = getConfig().getInt("config_version", 1);
        if (currentVersion < 2) {
            // Add new config options with defaults
            getConfig().set("smart_sort.player_inventory_delay_seconds", 30);
            getConfig().set("smart_sort.auto_sort_player_inventory", false);
            getConfig().set("config_version", 2);
            saveConfig();
            getLogger().info("Updated config to version 2");
        }
    }
}
