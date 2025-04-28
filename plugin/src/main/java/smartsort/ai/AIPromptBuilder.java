package smartsort.ai;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

public class AIPromptBuilder {

    public String buildSortingPrompt(List<ItemStack> items) {
        // Create inventory signature for prompt
        String signature = createItemSignature(items);

        return (
            "[SMARTSORT v4] Inventory: " +
            signature +
            "\n" +
            "RULES:\n" +
            "1. You're a Minecraft expert organizing inventory in the most intuitive way possible\n" +
            "2. Group similar items together (blocks with blocks, tools with tools)\n" +
            "3. Put most commonly used items at the top/beginning of inventory\n" +
            "4. Stack to maximum amounts first\n" +
            "5. For tools and weapons, sort by material quality (wood→stone→iron→gold→diamond)\n" +
            "6. Output ONLY lines like \"12xSTONE\" with no comments or explanations\n" +
            "7. Consider what an experienced Minecraft player would expect"
        );
    }

    private String createItemSignature(List<ItemStack> items) {
        // Generate a signature of the inventory contents
        Map<Material, Integer> map = new HashMap<>();
        items.forEach(i -> {
            if (i != null && i.getType() != Material.AIR) {
                map.merge(i.getType(), i.getAmount(), Integer::sum);
            }
        });

        StringBuilder sb = new StringBuilder();
        map
            .entrySet()
            .stream()
            .sorted((a, b) -> a.getKey().name().compareTo(b.getKey().name()))
            .forEach(e ->
                sb
                    .append(e.getValue())
                    .append("x")
                    .append(e.getKey())
                    .append(", ")
            );

        return sb.length() > 2 ? sb.substring(0, sb.length() - 2) : "";
    }
}
