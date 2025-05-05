package smartsort.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import smartsort.util.DebugLogger;

/**
 * Base class for AI response parsing, providing common functionality for
 * both container and player inventory parsing.
 */
public abstract class BaseResponseParser {

    protected final DebugLogger debug;

    public BaseResponseParser(DebugLogger debug) {
        this.debug = debug;
    }

    /**
     * Creates a map of material -> queue of ItemStacks from original items
     * @param originalItems The original items to process
     * @return A map of material to queue of ItemStacks
     */
    protected Map<Material, Queue<ItemStack>> createMaterialMap(List<ItemStack> originalItems) {
        Map<Material, Queue<ItemStack>> stash = new HashMap<>();
        originalItems.forEach(item -> {
            if (item != null && !item.getType().isAir()) {
                stash
                    .computeIfAbsent(item.getType(), k -> new LinkedList<>())
                    .add(item.clone());
            }
        });
        return stash;
    }
    
    /**
     * Parses a line from an AI response to extract amount and material
     * @param line The line to parse
     * @return Array with [amount, material] or null if invalid
     */
    protected Object[] parseResponseLine(String line) {
        line = line.trim().toUpperCase();
        // Match pattern like "12xSTONE" or "12 x STONE" with case-insensitive 'x'
        if (!line.matches("(?i)\\d+\\s*[xX]\\s*[A-Z0-9_]+")) {
            return null;
        }

        // Split on x with optional whitespace
        String[] parts = line.split("(?i)\\s*[xX]\\s*");

        try {
            int amount = Integer.parseInt(parts[0]);
            Material material = Material.matchMaterial(parts[1]);

            // Skip invalid materials
            if (material == null || material.isAir()) {
                return null;
            }
            
            return new Object[] {amount, material};
        } catch (Exception e) {
            debug.console("[BaseResponseParser] Error parsing line: " + line + " - " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Takes items from the material queue up to the requested amount
     * @param queue The queue of items
     * @param requestedAmount The amount to take
     * @return List of ItemStacks taken
     */
    protected List<ItemStack> takeItemsFromQueue(Queue<ItemStack> queue, int requestedAmount) {
        List<ItemStack> result = new ArrayList<>();
        int remainingAmount = requestedAmount;
        
        while (remainingAmount > 0 && !queue.isEmpty()) {
            ItemStack item = queue.poll();
            int take = Math.min(remainingAmount, item.getAmount());

            ItemStack slice = item.clone();
            slice.setAmount(take);
            result.add(slice);

            remainingAmount -= take;

            // If we didn't use all of the item, put the remainder back
            if (item.getAmount() > take) {
                item.setAmount(item.getAmount() - take);
                queue.add(item);
            }
        }
        
        return result;
    }
    
    /**
     * Validates that the item counts are consistent between original and sorted
     * @param original Original item list
     * @param sorted Sorted item list
     * @return true if counts match, false otherwise
     */
    protected boolean validateItemCounts(List<ItemStack> original, List<ItemStack> sorted) {
        Map<Material, Integer> originalCounts = countItems(original);
        Map<Material, Integer> sortedCounts = countItems(sorted);

        // Compare counts for each material
        for (Material material : originalCounts.keySet()) {
            int originalCount = originalCounts.get(material);
            int sortedCount = sortedCounts.getOrDefault(material, 0);

            if (originalCount != sortedCount) {
                debug.console(
                    "[BaseResponseParser] Count mismatch for " +
                    material +
                    ": original=" +
                    originalCount +
                    ", sorted=" +
                    sortedCount
                );
                return false;
            }
        }

        return true;
    }
    
    /**
     * Counts items by material
     * @param items List of items to count
     * @return Map of material to count
     */
    private Map<Material, Integer> countItems(List<ItemStack> items) {
        Map<Material, Integer> counts = new HashMap<>();
        for (ItemStack item : items) {
            if (item != null && !item.getType().isAir()) {
                counts.merge(
                    item.getType(),
                    item.getAmount(),
                    Integer::sum
                );
            }
        }
        return counts;
    }
}