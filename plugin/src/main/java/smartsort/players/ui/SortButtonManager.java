package smartsort.players.ui;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;
import smartsort.SmartSortPlugin;
import smartsort.util.DebugLogger;
import smartsort.util.PlayerPreferenceManager;

/**
 * Manages the UI for player inventory sorting.
 */
public class SortButtonManager implements Listener {

    private final SmartSortPlugin plugin;
    private final DebugLogger debug;
    private final PlayerPreferenceManager preferenceManager;
    private BukkitTask inventoryCheckTask;

    // Sort button click callback
    private SortButtonClickCallback sortButtonClickCallback;

    public SortButtonManager(
        SmartSortPlugin plugin,
        DebugLogger debug,
        PlayerPreferenceManager preferenceManager
    ) {
        this.plugin = plugin;
        this.debug = debug;
        this.preferenceManager = preferenceManager;

        // Register this listener
        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        // Start the inventory checking task
        startInventoryCheckTask();
    }

    /**
     * Starts a task that periodically checks player inventories to add sort button
     */
    private void startInventoryCheckTask() {
        debug.console("[SortButtonManager] Starting inventory check task");
        inventoryCheckTask = plugin
            .getServer()
            .getScheduler()
            .runTaskTimer(
                plugin,
                () -> {
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        // Skip if auto-sort not enabled
                        if (
                            !preferenceManager.isAutoSortEnabled(
                                player.getUniqueId()
                            )
                        ) {
                            continue;
                        }

                        // Check if player has inventory open
                        InventoryView view = player.getOpenInventory();

                        // Only add the indicator for proper inventory views
                        if (view.getType() == InventoryType.CRAFTING) {
                            // Get the top inventory
                            Inventory topInventory = view.getTopInventory();

                            // Check if it's a crafting inventory
                            if (
                                topInventory.getType() == InventoryType.CRAFTING
                            ) {
                                // Skip if there are items in the crafting grid (slots 1-4)
                                boolean craftingInProgress = false;
                                for (int slot = 1; slot <= 4; slot++) {
                                    ItemStack item = topInventory.getItem(slot);
                                    if (
                                        item != null &&
                                        item.getType() != Material.AIR
                                    ) {
                                        craftingInProgress = true;
                                        break;
                                    }
                                }

                                if (craftingInProgress) {
                                    continue;
                                }

                                // Check if output slot is empty
                                ItemStack outputItem = topInventory.getItem(0);
                                if (
                                    outputItem != null &&
                                    outputItem.getType() != Material.AIR
                                ) {
                                    continue;
                                }

                                // Create the sorting indicator
                                ItemStack sortIndicator = createSortIndicator();

                                // Set the indicator in the output slot
                                topInventory.setItem(0, sortIndicator);
                                debug.console("[SortButtonManager] Added sort button to " + player.getName() + "'s inventory");
                            }
                        }
                    }
                },
                10L, // Initial delay
                10L // Run every 10 ticks (0.5 seconds)
            );
    }

    /**
     * Creates a hopper item that serves as the sort button
     */
    private ItemStack createSortIndicator() {
        ItemStack sortIndicator = new ItemStack(Material.HOPPER, 1);
        ItemMeta meta = sortIndicator.getItemMeta();
        if (meta != null) {
            meta.displayName(
                Component.text("Click to Sort Inventory", NamedTextColor.GOLD)
            );

            List<Component> lore = new ArrayList<>();
            lore.add(
                Component.text(
                    "Click to organize your inventory",
                    NamedTextColor.GRAY
                )
            );
            meta.lore(lore);

            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES);
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            sortIndicator.setItemMeta(meta);
        }
        return sortIndicator;
    }

    /**
     * Listens for clicks on the sort button
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onPlayerClickInventory(InventoryClickEvent event) {
        // Skip if not a player
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        // Skip if auto-sort not enabled
        if (!preferenceManager.isAutoSortEnabled(player.getUniqueId())) {
            return;
        }

        // Check if clicking in a crafting inventory
        if (
            event.getClickedInventory() == null ||
            event.getClickedInventory().getType() != InventoryType.CRAFTING
        ) {
            return;
        }

        // Check if clicking the crafting result slot (slot 0)
        if (event.getSlot() != 0) {
            return;
        }

        // Check if the clicked item is our sort indicator
        ItemStack clickedItem = event.getCurrentItem();
        if (clickedItem == null || clickedItem.getType() != Material.HOPPER) {
            return;
        }

        // Verify it's our special hopper item with the right name
        ItemMeta meta = clickedItem.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) {
            return;
        }

        Component displayName = meta.displayName();
        if (
            displayName == null ||
            !displayName.equals(
                Component.text("Click to Sort Inventory", NamedTextColor.GOLD)
            )
        ) {
            return;
        }

        // Cancel the event to prevent picking up the item
        event.setCancelled(true);

        // Trigger the sort event via callback
        if (sortButtonClickCallback != null) {
            sortButtonClickCallback.onSortButtonClick(player);
        }
    }

    /**
     * Cleans up resources
     */
    public void shutdown() {
        if (inventoryCheckTask != null) {
            inventoryCheckTask.cancel();
            inventoryCheckTask = null;
        }
    }

    /**
     * Interface for sort button click callback
     */
    public interface SortButtonClickCallback {
        void onSortButtonClick(Player player);
    }

    /**
     * Sets the sort button click callback
     */
    public void setSortButtonClickCallback(SortButtonClickCallback callback) {
        this.sortButtonClickCallback = callback;
    }
}
