package smartsort.sorting;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import smartsort.util.TickSoundManager;

public class SortResultApplier {

    private final TickSoundManager tickSound;

    public SortResultApplier(TickSoundManager tickSound) {
        this.tickSound = tickSound;
    }

    /**
     * Applies sorted items to an inventory with intelligent stacking
     *
     * @param inv The inventory to apply sorted items to
     * @param sorted The list of sorted ItemStacks
     * @param player The player who initiated the sort
     * @return true if sorting was successful, false if there was an error
     */
    public boolean applySortedItems(
        Inventory inv,
        List<ItemStack> sorted,
        Player player
    ) {
        // Verify inventory has enough space
        if (sorted.size() > inv.getSize()) {
            tickSound.stop(player);
            player.sendMessage(
                Component.text("Too many item stacks to sort - aborting").color(
                    NamedTextColor.RED
                )
            );
            return false;
        }

        // Clear and apply sorted items
        inv.clear();

        // Apply sorted items with stacking
        Map<String, ItemStack> stackMap = new LinkedHashMap<>();

        for (ItemStack item : sorted) {
            if (item == null || item.getType() == Material.AIR) continue;

            // Create key based on material and metadata
            String key = item.getType().toString();
            if (item.hasItemMeta()) key += ":" + item.getItemMeta().hashCode();

            if (stackMap.containsKey(key)) {
                ItemStack existing = stackMap.get(key);
                int maxSize = existing.getType().getMaxStackSize();

                if (existing.getAmount() < maxSize) {
                    int canAdd = maxSize - existing.getAmount();
                    int toAdd = Math.min(canAdd, item.getAmount());

                    existing.setAmount(existing.getAmount() + toAdd);

                    if (toAdd < item.getAmount()) {
                        ItemStack remainder = item.clone();
                        remainder.setAmount(item.getAmount() - toAdd);
                        // Create a new unique key for the remainder
                        stackMap.put(key + ":" + UUID.randomUUID(), remainder);
                    }
                } else {
                    // Stack is full, create new one with unique key
                    stackMap.put(key + ":" + UUID.randomUUID(), item);
                }
            } else {
                stackMap.put(key, item);
            }
        }

        // Place stacked items in inventory
        int slot = 0;
        for (ItemStack item : stackMap.values()) {
            if (slot < inv.getSize()) {
                inv.setItem(slot++, item);
            }
        }

        // Success feedback
        tickSound.stop(player);
        player.playSound(
            player.getLocation(),
            Sound.ENTITY_EXPERIENCE_ORB_PICKUP,
            0.7f,
            1.1f
        );

        return true;
    }
}
