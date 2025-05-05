package smartsort.players.inventory;

import java.util.HashMap;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import smartsort.players.mapping.PlayerSlotMapping;
import smartsort.players.inventory.PlayerInventoryExtractor.PlayerArmorSnapshot;
import smartsort.util.DebugLogger;

/**
 * Applies sorted items to player inventory.
 */
public class PlayerInventoryApplier {

    private final DebugLogger debug;

    public PlayerInventoryApplier(DebugLogger debug) {
        this.debug = debug;
    }

    /**
     * Apply the slot map to the player's inventory
     */
    public void applySlotMap(
        Player player,
        Map<String, ItemStack> slotMap,
        PlayerArmorSnapshot originalArmor
    ) {
        debug.console(
            "==== STARTING INVENTORY APPLICATION FOR " +
            player.getName() +
            " ===="
        );
        PlayerInventory inv = player.getInventory();

        // STEP 1: Extract armor assignments from slot map
        ItemStack newHelmet = slotMap.remove(PlayerSlotMapping.SLOT_HELMET);
        ItemStack newChestplate = slotMap.remove(PlayerSlotMapping.SLOT_CHESTPLATE);
        ItemStack newLeggings = slotMap.remove(PlayerSlotMapping.SLOT_LEGGINGS);
        ItemStack newBoots = slotMap.remove(PlayerSlotMapping.SLOT_BOOTS);
        ItemStack newOffhand = slotMap.remove(PlayerSlotMapping.SLOT_OFFHAND);

        logArmorChange("Helmet", originalArmor.getHelmet(), newHelmet);
        logArmorChange(
            "Chestplate",
            originalArmor.getChestplate(),
            newChestplate
        );
        logArmorChange("Leggings", originalArmor.getLeggings(), newLeggings);
        logArmorChange("Boots", originalArmor.getBoots(), newBoots);
        logArmorChange("Offhand", originalArmor.getOffhand(), newOffhand);

        // STEP 2: COMPLETELY CLEAR the player's inventory (including armor)
        inv.clear();

        // Save a copy of what's in the slot map before applying
        Map<String, ItemStack> originalSlotMap = new HashMap<>();
        slotMap.forEach((slot, item) -> originalSlotMap.put(slot, item.clone()));

        // STEP 3: Apply armor items first
        try {
            if (newHelmet != null) {
                debug.console("Setting helmet to: " + newHelmet.getType());
                inv.setHelmet(newHelmet);
            } else if (originalArmor.getHelmet() != null) {
                debug.console(
                    "Keeping current helmet: " +
                    originalArmor.getHelmet().getType()
                );
                inv.setHelmet(originalArmor.getHelmet());
            }

            if (newChestplate != null) {
                debug.console(
                    "Setting chestplate to: " + newChestplate.getType()
                );
                inv.setChestplate(newChestplate);
            } else if (originalArmor.getChestplate() != null) {
                debug.console(
                    "Keeping current chestplate: " +
                    originalArmor.getChestplate().getType()
                );
                inv.setChestplate(originalArmor.getChestplate());
            }

            if (newLeggings != null) {
                debug.console("Setting leggings to: " + newLeggings.getType());
                inv.setLeggings(newLeggings);
            } else if (originalArmor.getLeggings() != null) {
                debug.console(
                    "Keeping current leggings: " +
                    originalArmor.getLeggings().getType()
                );
                inv.setLeggings(originalArmor.getLeggings());
            }

            if (newBoots != null) {
                debug.console("Setting boots to: " + newBoots.getType());
                inv.setBoots(newBoots);
            } else if (originalArmor.getBoots() != null) {
                debug.console(
                    "Keeping current boots: " +
                    originalArmor.getBoots().getType()
                );
                inv.setBoots(originalArmor.getBoots());
            }

            if (newOffhand != null) {
                debug.console("Setting offhand to: " + newOffhand.getType());
                inv.setItemInOffHand(newOffhand);
            } else if (originalArmor.getOffhand() != null) {
                debug.console(
                    "Restoring original offhand: " +
                    originalArmor.getOffhand().getType()
                );
                inv.setItemInOffHand(originalArmor.getOffhand());
            }
        } catch (Exception e) {
            debug.console("Error setting armor: " + e.getMessage());
        }

        // STEP 4: Apply the rest of the items to the inventory slots
        for (Map.Entry<String, ItemStack> entry : slotMap.entrySet()) {
            String slotName = entry.getKey();
            ItemStack item = entry.getValue();

            if (item == null || item.getType().isAir()) continue;

            try {
                // Handle hotbar slots
                if (slotName.startsWith(PlayerSlotMapping.HOTBAR_PREFIX)) {
                    int slotIndex = PlayerSlotMapping.getHotbarIndex(slotName);
                    if (slotIndex >= 0 && slotIndex <= 8) {
                        inv.setItem(slotIndex, item);
                        debug.console(
                            "Set hotbar slot " +
                            slotIndex +
                            " to " +
                            item.getType() +
                            " x" +
                            item.getAmount()
                        );
                    }
                }
                // Handle main inventory slots
                else if (slotName.startsWith(PlayerSlotMapping.INVENTORY_PREFIX)) {
                    int slotIndex = PlayerSlotMapping.getInventoryIndex(slotName);
                    if (slotIndex >= 0 && slotIndex <= 26) {
                        inv.setItem(slotIndex + 9, item); // +9 to account for hotbar slots
                        debug.console(
                            "Set inventory slot " +
                            (slotIndex + 9) +
                            " to " +
                            item.getType() +
                            " x" +
                            item.getAmount()
                        );
                    }
                }
                // Any leftovers - put in any available slot
                else {
                    inv.addItem(item);
                    debug.console(
                        "Added leftover item " +
                        item.getType() +
                        " x" +
                        item.getAmount()
                    );
                }
            } catch (Exception e) {
                debug.console(
                    "Error setting slot " + slotName + ": " + e.getMessage()
                );
                // Try to add the item to any available slot
                inv.addItem(item);
            }
        }

        // STEP 5: Log final inventory state
        debug.console("\n=== INVENTORY AFTER SORTING (Player: " + player.getName() + ") ===");
        debug.console("--- HOTBAR SLOTS (0-8) ---");
        for (int i = 0; i < 9; i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && !item.getType().isAir()) {
                debug.console("HOTBAR_" + i + ": " + item.getType() + " x" + item.getAmount());
            } else {
                debug.console("HOTBAR_" + i + ": [empty]");
            }
        }
        
        debug.console("--- INVENTORY SLOTS (9-35) ---");
        for (int i = 9; i < 36; i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && !item.getType().isAir()) {
                debug.console("INVENTORY_" + (i-9) + ": " + item.getType() + " x" + item.getAmount());
            } else {
                debug.console("INVENTORY_" + (i-9) + ": [empty]");
            }
        }
        
        // Compare with original slot assignments
        debug.console("\n--- SLOT ASSIGNMENT COMPARISON ---");
        for (Map.Entry<String, ItemStack> entry : originalSlotMap.entrySet()) {
            String slot = entry.getKey();
            ItemStack expected = entry.getValue();
            
            // Check if this item is in the expected place
            if (slot.startsWith(PlayerSlotMapping.HOTBAR_PREFIX)) {
                int idx = PlayerSlotMapping.getHotbarIndex(slot);
                ItemStack actual = inv.getItem(idx);
                if (actual == null || actual.getType() != expected.getType()) {
                    debug.console("MISMATCH at " + slot + ": Expected " + expected.getType() + 
                            " but found " + (actual == null ? "empty" : actual.getType()));
                }
            } else if (slot.startsWith(PlayerSlotMapping.INVENTORY_PREFIX)) {
                int idx = PlayerSlotMapping.getInventoryIndex(slot) + 9;
                ItemStack actual = inv.getItem(idx);
                if (actual == null || actual.getType() != expected.getType()) {
                    debug.console("MISMATCH at " + slot + ": Expected " + expected.getType() + 
                            " but found " + (actual == null ? "empty" : actual.getType()));
                }
            }
        }

        // STEP 6: Log final armor state
        debug.console("\n--- FINAL ARMOR STATE ---");
        debug.console(
            "Helmet: " +
            (inv.getHelmet() == null ? "AIR" : inv.getHelmet().getType())
        );
        debug.console(
            "Chestplate: " +
            (inv.getChestplate() == null
                    ? "AIR"
                    : inv.getChestplate().getType())
        );
        debug.console(
            "Leggings: " +
            (inv.getLeggings() == null ? "AIR" : inv.getLeggings().getType())
        );
        debug.console(
            "Boots: " +
            (inv.getBoots() == null ? "AIR" : inv.getBoots().getType())
        );
        debug.console(
            "Offhand: " +
            (inv.getItemInOffHand() == null
                    ? "AIR"
                    : inv.getItemInOffHand().getType())
        );

        // Compare inventory contents before and after
        Map<Material, Integer> finalItemCounts = new HashMap<>();
        // Count inventory items
        for (int i = 0; i < 36; i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && !item.getType().isAir()) {
                finalItemCounts.merge(item.getType(), item.getAmount(), Integer::sum);
            }
        }
        // Add armor
        if (inv.getHelmet() != null && !inv.getHelmet().getType().isAir()) {
            finalItemCounts.merge(inv.getHelmet().getType(), inv.getHelmet().getAmount(), Integer::sum);
        }
        if (inv.getChestplate() != null && !inv.getChestplate().getType().isAir()) {
            finalItemCounts.merge(inv.getChestplate().getType(), inv.getChestplate().getAmount(), Integer::sum);
        }
        if (inv.getLeggings() != null && !inv.getLeggings().getType().isAir()) {
            finalItemCounts.merge(inv.getLeggings().getType(), inv.getLeggings().getAmount(), Integer::sum);
        }
        if (inv.getBoots() != null && !inv.getBoots().getType().isAir()) {
            finalItemCounts.merge(inv.getBoots().getType(), inv.getBoots().getAmount(), Integer::sum);
        }
        // Add offhand
        if (inv.getItemInOffHand() != null && !inv.getItemInOffHand().getType().isAir()) {
            finalItemCounts.merge(inv.getItemInOffHand().getType(), inv.getItemInOffHand().getAmount(), Integer::sum);
        }
        
        debug.console("--- AFTER SORTING ITEM COUNT SUMMARY ---");
        finalItemCounts.forEach((material, count) -> 
            debug.console(material + ": " + count + " total")
        );
        debug.console("Total unique items: " + finalItemCounts.size());
        int totalItems = finalItemCounts.values().stream().mapToInt(Integer::intValue).sum();
        debug.console("Total items: " + totalItems);        
        debug.console("==== FINISHED INVENTORY APPLICATION FOR " +
            player.getName() +
            " ===="
        );
    }

    /**
     * Helper method to log armor changes
     */
    private void logArmorChange(
        String armorPiece,
        ItemStack original,
        ItemStack newItem
    ) {
        String originalName = original == null
            ? "NONE"
            : original.getType().toString();
        String newName = newItem == null
            ? "NONE"
            : newItem.getType().toString();
        debug.console("Original " + armorPiece + ": " + originalName);
        debug.console("New " + armorPiece + ": " + newName);
    }
}
