package smartsort;

import org.bukkit.plugin.java.JavaPlugin;
import smartsort.ai.AIService;
import smartsort.command.*;
import smartsort.sorting.InventorySorter;
import smartsort.util.DebugLogger;
import smartsort.util.TickSoundManager;

public final class SmartSortPlugin extends JavaPlugin {

    private static SmartSortPlugin instance;
    private ServiceContainer services;
    private AIService aiService;
    private InventorySorter sorter;
    private TickSoundManager tickSoundManager;
    private DebugLogger debug;

    public static SmartSortPlugin get() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

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

        // Create remaining components
        sorter = new InventorySorter(this, aiService, tickSoundManager, debug);
        services.register(InventorySorter.class, sorter);

        // listeners
        getServer().getPluginManager().registerEvents(sorter, this);
        getServer().getPluginManager().registerEvents(tickSoundManager, this);

        // commands
        new SmartSortCommand(this, debug).register();
        new TestChestCommand(this, aiService, debug).register();

        @SuppressWarnings("deprecation")
        String version = getDescription().getVersion();
        getLogger().info("SmartSort " + version + " enabled");
    }

    @Override
    public void onDisable() {
        debug.shutdown(); // Add shutdown call to properly cancel processing task
        tickSoundManager.shutdown();
        aiService.shutdown();
        instance = null;
    }

    // Add access method for services
    public <T> T getService(Class<T> type) {
        return services.get(type);
    }
}
