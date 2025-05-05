package smartsort.players.inventory;

import java.util.*;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import smartsort.api.BaseResponseParser;
import smartsort.players.mapping.PlayerSlotMapping;
import smartsort.util.DebugLogger;

/**
 * Parses AI responses for player inventory organization.
 */
public class PlayerInventoryResponseParser extends BaseResponseParser {

    public PlayerInventoryResponseParser(DebugLogger debug) {
        super(debug);
    }

    /**
     * Parse the AI response into a map of slot names to items
     */
    public Map<String, ItemStack> parseResponse(
        String response,
        List<ItemStack> originalItems
    ) {
        Map<String, ItemStack> slotMap = new HashMap<>();

        // Create material map from original items
        Map<Material, Queue<ItemStack>> availableItems = createMaterialMap(originalItems);

        debug.console(
            "[PlayerInvParser] Original inventory contains " +
            originalItems.size() +
            " items"
        );

        // Parse the AI response into slot assignments
        List<AISlotAssignment> assignments = new ArrayList<>();

        for (String line : response.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;

            // Parse assignment line (format: "12xSTONE:SLOT_3")
            if (
                !line.matches(
                    "(?i)\\d+\\s*[xX]\\s*[A-Z0-9_]+\\s*:\\s*[A-Z0-9_]+"
                )
            ) {
                debug.console(
                    "[PlayerInvParser] Skipping invalid line: " + line
                );
                continue;
            }

            try {
                // Split into item and slot parts
                String[] mainParts = line.split("\\s*:\\s*");
                if (mainParts.length != 2) continue;
                
                // Parse the item part
                String itemPart = mainParts[0].trim();
                Object[] parsed = parseResponseLine(itemPart);
                if (parsed == null) continue;
                
                int requestedAmount = (int) parsed[0];
                Material material = (Material) parsed[1];
                String slotName = mainParts[1].trim();

                // Add to assignments list with priority based on slot type
                int priority = getSlotPriority(slotName);
                assignments.add(
                    new AISlotAssignment(
                        material,
                        requestedAmount,
                        slotName,
                        priority
                    )
                );
            } catch (Exception e) {
                debug.console(
                    "[PlayerInvParser] Error parsing line: " +
                    line +
                    " - " +
                    e.getMessage()
                );
            }
        }

        // Sort assignments by priority (armor first, then hotbar, then inventory)
        assignments.sort(Comparator.comparingInt(a -> a.priority));

        // Process assignments in priority order
        Set<String> usedSlots = new HashSet<>();

        for (AISlotAssignment assignment : assignments) {
            // Skip if slot already used
            if (usedSlots.contains(assignment.slotName)) {
                debug.console(
                    "[PlayerInvParser] Slot " +
                    assignment.slotName +
                    " already assigned, skipping"
                );
                continue;
            }

            Queue<ItemStack> queue = availableItems.get(assignment.material);
            if (queue == null || queue.isEmpty()) {
                debug.console(
                    "[PlayerInvParser] No available items of type " +
                    assignment.material
                );
                continue;
            }

            // For armor slots, we want exactly one item
            if (isArmorSlot(assignment.slotName)) {
                // Take one item from the queue
                List<ItemStack> taken = takeItemsFromQueue(queue, 1);
                if (!taken.isEmpty()) {
                    slotMap.put(assignment.slotName, taken.get(0));
                    usedSlots.add(assignment.slotName);
                }
            }
            // For regular slots, try to match the requested amount
            else {
                // Take items for this slot
                List<ItemStack> taken = takeItemsFromQueue(queue, assignment.requestedAmount);
                if (taken.isEmpty()) continue;
                
                // Combine items for stacking
                List<ItemStack> stacks = combineItems(taken);

                // Add first stack to the requested slot
                if (!stacks.isEmpty()) {
                    slotMap.put(assignment.slotName, stacks.get(0));
                    usedSlots.add(assignment.slotName);

                    // Put any additional stacks in available slots
                    for (int i = 1; i < stacks.size(); i++) {
                        String availableSlot = findAvailableSlot(usedSlots);
                        if (availableSlot != null) {
                            slotMap.put(availableSlot, stacks.get(i));
                            usedSlots.add(availableSlot);
                        } else {
                            debug.console(
                                "[PlayerInvParser] No available slot for extra stack of " +
                                stacks.get(i).getType()
                            );
                            break;
                        }
                    }
                }
            }
        }

        // Place remaining items in available slots
        for (Queue<ItemStack> queue : availableItems.values()) {
            for (ItemStack item : queue) {
                String availableSlot = findAvailableSlot(usedSlots);
                if (availableSlot != null) {
                    slotMap.put(availableSlot, item);
                    usedSlots.add(availableSlot);
                } else {
                    debug.console(
                        "[PlayerInvParser] WARNING: No available slot for item " +
                        item.getType()
                    );
                }
            }
        }

        return slotMap;
    }

    /**
     * Combine items into optimal stacks
     */
    private List<ItemStack> combineItems(List<ItemStack> items) {
        if (items.isEmpty()) return items;

        Material material = items.get(0).getType();
        int maxStackSize = material.getMaxStackSize();
        int totalAmount = 0;

        // Calculate total amount
        for (ItemStack item : items) {
            totalAmount += item.getAmount();
        }

        // Create optimal stacks
        List<ItemStack> result = new ArrayList<>();
        while (totalAmount > 0) {
            int stackSize = Math.min(totalAmount, maxStackSize);
            ItemStack stack = new ItemStack(material, stackSize);
            result.add(stack);
            totalAmount -= stackSize;
        }

        return result;
    }

    /**
     * Check if a slot name is an armor slot
     */
    private boolean isArmorSlot(String slotName) {
        return (
            slotName.equals(PlayerSlotMapping.SLOT_HELMET) ||
            slotName.equals(PlayerSlotMapping.SLOT_CHESTPLATE) ||
            slotName.equals(PlayerSlotMapping.SLOT_LEGGINGS) ||
            slotName.equals(PlayerSlotMapping.SLOT_BOOTS) ||
            slotName.equals(PlayerSlotMapping.SLOT_OFFHAND)
        );
    }

    /**
     * Get priority for a slot (lower number = higher priority)
     */
    private int getSlotPriority(String slotName) {
        // Armor slots have highest priority
        if (isArmorSlot(slotName)) {
            return 0;
        }

        // Hotbar slots have next priority
        if (slotName.startsWith("HOTBAR_")) {
            return 10;
        }

        // Regular inventory slots have lowest priority
        return 20;
    }

    /**
     * Find an available slot (not in the used slots set)
     */
    private String findAvailableSlot(Set<String> usedSlots) {
        // Try hotbar slots first
        for (int i = 0; i < 9; i++) {
            String slot = "HOTBAR_" + i;
            if (!usedSlots.contains(slot)) {
                return slot;
            }
        }

        // Then try inventory slots
        for (int i = 0; i < 27; i++) {
            String slot = "INVENTORY_" + i;
            if (!usedSlots.contains(slot)) {
                return slot;
            }
        }

        return null;
    }

    /**
     * Helper class to store AI slot assignments with priority
     */
    private static class AISlotAssignment {

        final Material material;
        final int requestedAmount;
        final String slotName;
        final int priority;

        AISlotAssignment(
            Material material,
            int requestedAmount,
            String slotName,
            int priority
        ) {
            this.material = material;
            this.requestedAmount = requestedAmount;
            this.slotName = slotName;
            this.priority = priority;
        }
    }
}