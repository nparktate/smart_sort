package smartsort.util;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.Inventory;

/**
 * Tracks inventory changes to prevent duplication bugs and ensure
 * sorting operations don't conflict with user inventory modifications.
 */
public class InventoryChangeTracker implements Listener {

    private final Map<UUID, Long> playerInventoryChangeTimes =
        new ConcurrentHashMap<>();
    private final Map<String, Long> containerChangeTimes =
        new ConcurrentHashMap<>();
    private final DebugLogger debug;

    public InventoryChangeTracker(DebugLogger debug) {
        this.debug = debug;
    }

    /**
     * Records when a player's inventory changes
     */
    public void recordPlayerInventoryChange(UUID playerId) {
        playerInventoryChangeTimes.put(playerId, System.currentTimeMillis());
        debug.console(
            "[ChangeTracker] Recorded player inventory change for " + playerId
        );
    }

    /**
     * Records when a container's inventory changes
     */
    public void recordContainerChange(String containerKey) {
        containerChangeTimes.put(containerKey, System.currentTimeMillis());
        debug.console(
            "[ChangeTracker] Recorded container change for " + containerKey
        );
    }

    /**
     * Checks if player inventory has changed since the given timestamp
     */
    public boolean hasPlayerInventoryChangedSince(
        UUID playerId,
        long timestamp
    ) {
        Long lastChange = playerInventoryChangeTimes.getOrDefault(playerId, 0L);
        return lastChange > timestamp;
    }

    /**
     * Checks if container inventory has changed since the given timestamp
     */
    public boolean hasContainerChangedSince(
        String containerKey,
        long timestamp
    ) {
        Long lastChange = containerChangeTimes.getOrDefault(containerKey, 0L);
        return lastChange > timestamp;
    }

    /**
     * Gets container key from inventory location
     */
    public String getContainerKey(Inventory inventory) {
        if (inventory == null || inventory.getLocation() == null) return null;
        Location loc = inventory.getLocation();
        return (
            loc.getWorld().getName() +
            ":" +
            loc.getBlockX() +
            ":" +
            loc.getBlockY() +
            ":" +
            loc.getBlockZ()
        );
    }

    /**
     * Event listeners to automatically track inventory changes
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        recordPlayerInventoryChange(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerPickupItem(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            recordPlayerInventoryChange(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        // Record player inventory change
        if (event.getWhoClicked() instanceof Player player) {
            recordPlayerInventoryChange(player.getUniqueId());
        }

        // Record container change if applicable
        Inventory clickedInventory = event.getClickedInventory();
        if (clickedInventory != null) {
            String containerKey = getContainerKey(clickedInventory);
            if (containerKey != null) {
                recordContainerChange(containerKey);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        // Record container change
        Inventory inventory = event.getInventory();
        String containerKey = getContainerKey(inventory);
        if (containerKey != null) {
            recordContainerChange(containerKey);
        }
    }

    /**
     * Clean up resources
     */
    public void cleanup(UUID playerId) {
        playerInventoryChangeTimes.remove(playerId);
    }
}
