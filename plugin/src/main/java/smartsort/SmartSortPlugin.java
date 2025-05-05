package smartsort;

import java.util.List;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import smartsort.api.openai.OpenAIResponseParser;
import smartsort.api.openai.OpenAIService;
import smartsort.commands.*;
import smartsort.containers.ContainerExtractor;
import smartsort.containers.ContainerSortApplier;
import smartsort.containers.ContainerSorter;
import smartsort.players.PlayerInventorySorter;
import smartsort.players.inventory.PlayerInventoryApplier;
import smartsort.players.inventory.PlayerInventoryExtractor;
import smartsort.players.inventory.PlayerInventoryResponseParser;
import smartsort.players.ui.SortButtonManager;
import smartsort.util.AsyncTaskManager;
import smartsort.util.DebugLogger;
import smartsort.util.InventoryChangeTracker;
import smartsort.util.PlayerPreferenceManager;
import smartsort.util.TickSoundManager;
import smartsort.util.VersionedCache;

public final class SmartSortPlugin extends JavaPlugin {

    private static SmartSortPlugin instance;
    private ServiceContainer services;
    private OpenAIService aiService;
    private ContainerSorter sorter;
    private PlayerInventorySorter playerInventoryService;
    private TickSoundManager tickSoundManager;
    private DebugLogger debug;
    private PlayerPreferenceManager preferenceManager;
    private InventoryChangeTracker changeTracker;
    private VersionedCache<String, List<ItemStack>> versionedCache;
    private AsyncTaskManager asyncTaskManager;

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

        aiService = new OpenAIService(this, debug);
        services.register(OpenAIService.class, aiService);

        tickSoundManager = new TickSoundManager(this);
        services.register(TickSoundManager.class, tickSoundManager);

        // Initialize player preference manager
        preferenceManager = new PlayerPreferenceManager(this);
        preferenceManager.loadAllPreferences();
        services.register(PlayerPreferenceManager.class, preferenceManager);

        // Initialize inventory change tracker
        changeTracker = new InventoryChangeTracker(debug);
        services.register(InventoryChangeTracker.class, changeTracker);
        getServer().getPluginManager().registerEvents(changeTracker, this);

        // Initialize versioned cache
        versionedCache = new VersionedCache<>(
            getConfig().getInt("performance.cache_size", 500),
            debug
        );
        services.register(VersionedCache.class, versionedCache);

        // Initialize async task manager
        asyncTaskManager = new AsyncTaskManager(
            this,
            debug,
            getConfig().getInt("performance.async_thread_pool_size", 2),
            getConfig().getInt("performance.batch_period_millis", 250)
        );
        services.register(AsyncTaskManager.class, asyncTaskManager);

        // Initialize container components
        ContainerExtractor containerExtractor = new ContainerExtractor(debug);
        ContainerSortApplier containerSortApplier = new ContainerSortApplier(
            debug,
            tickSoundManager
        );

        // Create inventory sorter with new dependencies
        sorter = new ContainerSorter(
            this,
            aiService,
            tickSoundManager,
            debug,
            changeTracker,
            versionedCache,
            containerExtractor,
            containerSortApplier
        );
        services.register(ContainerSorter.class, sorter);

        // Initialize AIResponseParser
        OpenAIResponseParser responseParser = new OpenAIResponseParser(debug);
        services.register(OpenAIResponseParser.class, responseParser);

        // Initialize player inventory components
        PlayerInventoryExtractor playerExtractor = new PlayerInventoryExtractor(
            debug
        );
        PlayerInventoryResponseParser playerResponseParser =
            new PlayerInventoryResponseParser(debug);
        PlayerInventoryApplier playerApplier = new PlayerInventoryApplier(
            debug
        );
        SortButtonManager uiManager = new SortButtonManager(
            this,
            debug,
            preferenceManager
        );

        // Initialize player inventory service with new components
        playerInventoryService = new PlayerInventorySorter(
            this,
            aiService,
            tickSoundManager,
            debug,
            preferenceManager,
            playerExtractor,
            playerResponseParser,
            playerApplier,
            uiManager,
            changeTracker
        );
        services.register(PlayerInventorySorter.class, playerInventoryService);

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
        asyncTaskManager.shutdown(); // Shutdown async task manager
        versionedCache.shutdown(); // Shutdown versioned cache

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

        // Add performance settings if missing
        if (!getConfig().contains("performance.async_thread_pool_size")) {
            getConfig().set("performance.async_thread_pool_size", 2);
        }
        if (!getConfig().contains("performance.batch_period_millis")) {
            getConfig().set("performance.batch_period_millis", 250);
        }
        if (
            !getConfig().contains("performance.cache_cleanup_interval_minutes")
        ) {
            getConfig().set("performance.cache_cleanup_interval_minutes", 30);
        }

        // Save if any changes were made
        saveConfig();
    }
}
