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
            "[SMARTSORT v4.5] Inventory: " +
            signature +
            "\n" +
            "RULES:\n" +
            "1. Expert Minecraft inventory organization\n" +
            "2. Group similar items (blocks, tools, resources)\n" +
            "3. Put common items at top/beginning\n" +
            "4. Stack items fully\n" +
            "5. Output ONLY lines like \"12xSTONE\" with no explanations\n" +
            "6. Be quick but thorough"
        );
    }

    public String buildPlayerInventoryPrompt(List<ItemStack> items) {
        // Create inventory signature for prompt
        String signature = createItemSignature(items);

        return (
            "[SMARTSORT PLAYER v2] Inventory: " +
            signature +
            "\n" +
            "RULES:\n" +
            "1. You're a professional Minecraft speedrunner organizing a player inventory\n" +
            "2. Put weapons in hotbar slots 1-2, tools in 3-5, blocks in 6-9\n" +
            "3. Reserve offhand for shield/torch\n" +
            "4. Group similar items, with most important/frequent use items first\n" +
            "5. Place EXACTLY ONE armor item in each appropriate armor slot (HELMET, CHESTPLATE, LEGGINGS, BOOTS)\n" +
            "6. IMPORTANT: Any additional armor items should go in regular inventory slots, NEVER in armor slots\n" +
            "7. Food goes in right side of hotbar\n" +
            "8. Output ONLY lines like \"12xSTONE:SLOT_3\" with no comments\n" +
            "9. Valid slots: HOTBAR_0 through HOTBAR_8, INVENTORY_0 through INVENTORY_26, HELMET, CHESTPLATE, LEGGINGS, BOOTS, OFFHAND\n" +
            "10. NEVER assign more than one item to HELMET, CHESTPLATE, LEGGINGS, or BOOTS slots\n" +
            "11. NEVER assign an item to an armor slot unless it's specifically for that slot (e.g. only helmets in HELMET slot)\n" +
            "12. If you need to put armor items in inventory, put them in INVENTORY slots, never in HOTBAR"
        );
    }

    public String createItemSignature(List<ItemStack> items) {
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
