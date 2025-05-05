package smartsort.api.openai;

import java.util.*;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import smartsort.api.BaseResponseParser;
import smartsort.util.DebugLogger;

public class OpenAIResponseParser extends BaseResponseParser {

    public OpenAIResponseParser(DebugLogger debug) {
        super(debug);
    }

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
        Map<Material, Queue<ItemStack>> stash = createMaterialMap(originalItems);

        // Parse the response and build the result list
        List<ItemStack> result = new ArrayList<>();
        for (String line : response.split("\n")) {
            Object[] parsed = parseResponseLine(line);
            if (parsed == null) continue;
            
            int amount = (int) parsed[0];
            Material material = (Material) parsed[1];

            Queue<ItemStack> queue = stash.get(material);
            if (queue == null || queue.isEmpty()) continue;
            
            // Extract items from the queue until we've satisfied the amount
            List<ItemStack> taken = takeItemsFromQueue(queue, amount);
            result.addAll(taken);
        }

        // Add any remaining items from the stash
        stash.values().forEach(queue -> queue.forEach(result::add));

        return result;
    }
}
