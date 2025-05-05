package smartsort.containers;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import smartsort.util.DebugLogger;

/**
 * Extracts items from container inventories
 */
public class ContainerExtractor {

    private final DebugLogger debug;

    public ContainerExtractor(DebugLogger debug) {
        this.debug = debug;
    }

    /**
     * Extract all items from a container inventory
     */
    public List<ItemStack> extractItems(Inventory inventory) {
        List<ItemStack> items = new ArrayList<>();

        if (inventory == null) return items;

        // Extract all non-empty slots
        for (ItemStack item : inventory.getContents()) {
            if (item != null && item.getType() != Material.AIR) {
                items.add(item.clone());
            }
        }

        Location location = inventory.getLocation();
        String locationString = location != null
            ? location.getWorld().getName() +
            ":" +
            location.getBlockX() +
            ":" +
            location.getBlockY() +
            ":" +
            location.getBlockZ()
            : "unknown";

        debug.console(
            "[ContainerExtractor] Extracted " +
            items.size() +
            " items from container at " +
            locationString
        );

        return items;
    }

    /**
     * Get a container key from its location
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
}
