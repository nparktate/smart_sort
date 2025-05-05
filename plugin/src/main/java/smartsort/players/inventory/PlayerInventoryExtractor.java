package smartsort.players.inventory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import smartsort.util.DebugLogger;

/**
 * Responsible for extracting items from player inventory.
 */
public class PlayerInventoryExtractor {

    private final DebugLogger debug;

    public PlayerInventoryExtractor(DebugLogger debug) {
        this.debug = debug;
    }

    /**
     * Extract all items from a player's inventory
     */
    public List<ItemStack> extractPlayerItems(Player player) {
        List<ItemStack> items = new ArrayList<>();
        PlayerInventory inv = player.getInventory();

        debug.console("\n=== INVENTORY BEFORE SORTING (Player: " + player.getName() + ") ===");
        
        // Main inventory (0-35) - avoiding iteration to be more explicit
        debug.console("--- HOTBAR SLOTS (0-8) ---");
        for (int i = 0; i < 9; i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && !item.getType().isAir()) {
                items.add(item.clone());
                debug.console("HOTBAR_" + i + ": " + item.getType() + " x" + item.getAmount());
            } else {
                debug.console("HOTBAR_" + i + ": [empty]");
            }
        }
        
        debug.console("--- INVENTORY SLOTS (9-35) ---");
        for (int i = 9; i < 36; i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && !item.getType().isAir()) {
                items.add(item.clone());
                debug.console("INVENTORY_" + (i-9) + ": " + item.getType() + " x" + item.getAmount());
            } else {
                debug.console("INVENTORY_" + (i-9) + ": [empty]");
            }
        }

        // Handle armor individually to be explicit
        debug.console("--- ARMOR SLOTS ---");
        ItemStack helmet = inv.getHelmet();
        if (helmet != null && !helmet.getType().isAir()) {
            items.add(helmet.clone());
            debug.console(
                "HELMET: " +
                helmet.getType() +
                " x" +
                helmet.getAmount()
            );
        } else {
            debug.console("HELMET: [empty]");
        }

        ItemStack chestplate = inv.getChestplate();
        if (chestplate != null && !chestplate.getType().isAir()) {
            items.add(chestplate.clone());
            debug.console(
                "CHESTPLATE: " +
                chestplate.getType() +
                " x" +
                chestplate.getAmount()
            );
        } else {
            debug.console("CHESTPLATE: [empty]");
        }

        ItemStack leggings = inv.getLeggings();
        if (leggings != null && !leggings.getType().isAir()) {
            items.add(leggings.clone());
            debug.console(
                "LEGGINGS: " +
                leggings.getType() +
                " x" +
                leggings.getAmount()
            );
        } else {
            debug.console("LEGGINGS: [empty]");
        }

        ItemStack boots = inv.getBoots();
        if (boots != null && !boots.getType().isAir()) {
            items.add(boots.clone());
            debug.console(
                "BOOTS: " +
                boots.getType() +
                " x" +
                boots.getAmount()
            );
        } else {
            debug.console("BOOTS: [empty]");
        }

        // Offhand
        debug.console("--- OFFHAND SLOT ---");
        ItemStack offhand = inv.getItemInOffHand();
        if (offhand != null && !offhand.getType().isAir()) {
            items.add(offhand.clone());
            debug.console(
                "OFFHAND: " +
                offhand.getType() +
                " x" +
                offhand.getAmount()
            );
        } else {
            debug.console("OFFHAND: [empty]");
        }

        // Generate item count summary
        Map<Material, Integer> itemCounts = new HashMap<>();
        for (ItemStack item : items) {
            itemCounts.merge(item.getType(), item.getAmount(), Integer::sum);
        }
        
        debug.console("--- BEFORE SORTING ITEM COUNT SUMMARY ---");
        itemCounts.forEach((material, count) -> 
            debug.console(material + ": " + count + " total")
        );
        debug.console("Total unique items: " + itemCounts.size());
        debug.console("Total item stacks: " + items.size());
        debug.console("=== END INVENTORY BEFORE SORTING ===");

        return items;
    }

    /**
     * Take a snapshot of the current armor items
     */
    public PlayerArmorSnapshot takeArmorSnapshot(Player player) {
        PlayerInventory inv = player.getInventory();

        ItemStack helmet = inv.getHelmet();
        ItemStack chestplate = inv.getChestplate();
        ItemStack leggings = inv.getLeggings();
        ItemStack boots = inv.getBoots();
        ItemStack offhand = inv.getItemInOffHand();

        return new PlayerArmorSnapshot(
            helmet != null && !helmet.getType().isAir() ? helmet.clone() : null,
            chestplate != null && !chestplate.getType().isAir()
                ? chestplate.clone()
                : null,
            leggings != null && !leggings.getType().isAir()
                ? leggings.clone()
                : null,
            boots != null && !boots.getType().isAir() ? boots.clone() : null,
            offhand != null && !offhand.getType().isAir()
                ? offhand.clone()
                : null
        );
    }

    /**
     * Class representing a snapshot of player's armor
     */
    public static class PlayerArmorSnapshot {

        private final ItemStack helmet;
        private final ItemStack chestplate;
        private final ItemStack leggings;
        private final ItemStack boots;
        private final ItemStack offhand;

        public PlayerArmorSnapshot(
            ItemStack helmet,
            ItemStack chestplate,
            ItemStack leggings,
            ItemStack boots,
            ItemStack offhand
        ) {
            this.helmet = helmet;
            this.chestplate = chestplate;
            this.leggings = leggings;
            this.boots = boots;
            this.offhand = offhand;
        }

        public ItemStack getHelmet() {
            return helmet;
        }

        public ItemStack getChestplate() {
            return chestplate;
        }

        public ItemStack getLeggings() {
            return leggings;
        }

        public ItemStack getBoots() {
            return boots;
        }

        public ItemStack getOffhand() {
            return offhand;
        }

        public String getDebugString() {
            return (
                "Helmet: " +
                (helmet == null ? "NONE" : helmet.getType()) +
                ", Chestplate: " +
                (chestplate == null ? "NONE" : chestplate.getType()) +
                ", Leggings: " +
                (leggings == null ? "NONE" : leggings.getType()) +
                ", Boots: " +
                (boots == null ? "NONE" : boots.getType()) +
                ", Offhand: " +
                (offhand == null ? "NONE" : offhand.getType())
            );
        }
    }
}
