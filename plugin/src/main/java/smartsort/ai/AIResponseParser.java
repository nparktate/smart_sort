package smartsort.ai;

import java.util.*;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

public class AIResponseParser {

    /**
     * Parse an AI response into a sorted list of ItemStacks
     * @param response The AI response string containing item descriptions
     * @param originalItems The original items to source from
     * @return A sorted list of ItemStacks based on the AI response
     */
    public List<ItemStack> parseResponse(
        String response,
        List<ItemStack> originalItems
    ) {
        // Create a map to store original items by material
        Map<Material, Queue<ItemStack>> stash = new HashMap<>();
        originalItems.forEach(item -> {
            if (item != null && item.getType() != Material.AIR) {
                stash
                    .computeIfAbsent(item.getType(), k -> new LinkedList<>())
                    .add(item.clone());
            }
        });

        // Parse the response and build the result list
        List<ItemStack> result = new ArrayList<>();
        for (String line : response.split("\n")) {
            line = line.trim().toUpperCase();
            // Match pattern like "12xSTONE" or "12 x STONE" with case-insensitive 'x'
            if (!line.matches("(?i)\\d+\\s*[xX]\\s*[A-Z0-9_]+")) continue;

            // Split on x with optional whitespace
            String[] parts = line.split("(?i)\\s*[xX]\\s*");

            int amount = Integer.parseInt(parts[0]);
            Material material = Material.matchMaterial(parts[1]);

            // Skip invalid materials
            if (material == null || material == Material.AIR) continue;

            Queue<ItemStack> queue = stash.get(material);
            // Extract items from the queue until we've satisfied the amount
            while (amount > 0 && queue != null && !queue.isEmpty()) {
                ItemStack item = queue.poll();
                int take = Math.min(amount, item.getAmount());

                ItemStack slice = item.clone();
                slice.setAmount(take);
                result.add(slice);

                amount -= take;

                // If we didn't use all of the item, put the remainder back
                if (item.getAmount() > take) {
                    item.setAmount(item.getAmount() - take);
                    queue.add(item);
                }
            }
        }

        // Add any remaining items from the stash
        stash.values().forEach(queue -> queue.forEach(result::add));

        return result;
    }
}
