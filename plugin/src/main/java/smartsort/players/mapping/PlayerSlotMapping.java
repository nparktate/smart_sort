package smartsort.players.mapping;

/**
 * Centralizes all player inventory slot constants and mapping utilities.
 * Extracted from PlayerInventoryResponseParser to make these constants more broadly available.
 */
public class PlayerSlotMapping {

    // Armor slot constants
    public static final String SLOT_HELMET = "HELMET";
    public static final String SLOT_CHESTPLATE = "CHESTPLATE";
    public static final String SLOT_LEGGINGS = "LEGGINGS";
    public static final String SLOT_BOOTS = "BOOTS";
    public static final String SLOT_OFFHAND = "OFFHAND";
    
    // Hotbar prefix
    public static final String HOTBAR_PREFIX = "HOTBAR_";
    
    // Inventory prefix
    public static final String INVENTORY_PREFIX = "INVENTORY_";
    
    /**
     * Checks if the given slot name is an armor slot
     */
    public static boolean isArmorSlot(String slotName) {
        return slotName.equals(SLOT_HELMET) ||
               slotName.equals(SLOT_CHESTPLATE) ||
               slotName.equals(SLOT_LEGGINGS) ||
               slotName.equals(SLOT_BOOTS) ||
               slotName.equals(SLOT_OFFHAND);
    }
    
    /**
     * Checks if the given slot name is a hotbar slot
     */
    public static boolean isHotbarSlot(String slotName) {
        return slotName.startsWith(HOTBAR_PREFIX);
    }
    
    /**
     * Checks if the given slot name is a main inventory slot
     */
    public static boolean isInventorySlot(String slotName) {
        return slotName.startsWith(INVENTORY_PREFIX);
    }
    
    /**
     * Gets the numeric index from a hotbar slot name
     * @param slotName The slot name (e.g., "HOTBAR_3")
     * @return The index (e.g., 3) or -1 if invalid
     */
    public static int getHotbarIndex(String slotName) {
        if (!isHotbarSlot(slotName)) return -1;
        try {
            return Integer.parseInt(slotName.substring(HOTBAR_PREFIX.length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
    
    /**
     * Gets the numeric index from an inventory slot name
     * @param slotName The slot name (e.g., "INVENTORY_12")
     * @return The index (e.g., 12) or -1 if invalid
     */
    public static int getInventoryIndex(String slotName) {
        if (!isInventorySlot(slotName)) return -1;
        try {
            return Integer.parseInt(slotName.substring(INVENTORY_PREFIX.length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
    
    /**
     * Converts a hotbar index to the actual inventory index
     * @param hotbarIndex The hotbar index (0-8)
     * @return The corresponding inventory index
     */
    public static int hotbarToInventoryIndex(int hotbarIndex) {
        return hotbarIndex; // In Bukkit, hotbar slots are 0-8
    }
    
    /**
     * Converts an inventory slot index to the actual inventory index
     * @param inventoryIndex The inventory index (0-26)
     * @return The corresponding inventory index (9-35)
     */
    public static int inventoryToContainerIndex(int inventoryIndex) {
        return inventoryIndex + 9; // Inventory slots start after hotbar
    }
}