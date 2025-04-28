package smartsort.sorting;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public class InventoryAnalyzer {

    /**
     * Creates a unique signature string for an inventory based on its contents.
     * The signature represents all item types and their quantities in a deterministic order.
     *
     * @param inv The inventory to analyze
     * @return A string signature of the inventory contents
     */
    public String createInventorySignature(Inventory inv) {
        if (inv == null) return "";

        // Create a map of material to total amount
        Map<Material, Integer> materialCounts = new HashMap<>();

        // Process all non-empty inventory slots
        for (ItemStack item : inv.getContents()) {
            if (item != null && item.getType() != Material.AIR) {
                materialCounts.merge(
                    item.getType(),
                    item.getAmount(),
                    Integer::sum
                );
            }
        }

        // Build signature string with consistent ordering
        StringBuilder signature = new StringBuilder();
        materialCounts
            .entrySet()
            .stream()
            .sorted(
                Map.Entry.comparingByKey(Comparator.comparing(Material::name))
            )
            .forEach(entry ->
                signature
                    .append(entry.getValue())
                    .append("x")
                    .append(entry.getKey().name())
                    .append(",")
            );

        // Remove trailing comma if present
        if (signature.length() > 0) {
            signature.setLength(signature.length() - 1);
        }

        return signature.toString();
    }
}
