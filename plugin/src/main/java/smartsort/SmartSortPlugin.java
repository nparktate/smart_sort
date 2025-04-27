package smartsort;

import org.bukkit.plugin.java.JavaPlugin;
import smartsort.ai.AIService;
import smartsort.command.*;
import smartsort.sorting.InventorySorter;
import smartsort.util.DebugLogger;
import smartsort.util.TickSoundManager;

public final class SmartSortPlugin extends JavaPlugin {

    private static SmartSortPlugin instance;
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

        debug = new DebugLogger(this);
        aiService = new AIService(this, debug);
        tickSoundManager = new TickSoundManager(this);
        sorter = new InventorySorter(this, aiService, tickSoundManager, debug);

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
        tickSoundManager.shutdown();
        aiService.shutdown();
        instance = null;
    }
}
