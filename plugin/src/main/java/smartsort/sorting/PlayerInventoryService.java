package smartsort.sorting;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import smartsort.SmartSortPlugin;
import smartsort.ai.AIPromptBuilder;
import smartsort.ai.AIService;
import smartsort.event.PlayerInventorySortCompletedEvent;
import smartsort.event.PlayerInventorySortEvent;
import smartsort.event.SortFailedEvent;
import smartsort.util.DebugLogger;
import smartsort.util.PlayerPreferenceManager;
import smartsort.util.TickSoundManager;

public class PlayerInventoryService implements Listener {

    private final SmartSortPlugin plugin;
    private final AIService aiService;
    private final TickSoundManager tickSoundManager;
    private final DebugLogger debugLogger;
    private final PlayerPreferenceManager preferenceManager;
    private final AIPromptBuilder promptBuilder;
    private final Map<UUID, Long> lastSortTimes = new ConcurrentHashMap<>();
    private final Set<UUID> sortingInProgress = new HashSet<>();

    // Define inventory slot constants
    public static final String SLOT_HELMET = "HELMET";
    public static final String SLOT_CHESTPLATE = "CHESTPLATE";
    public static final String SLOT_LEGGINGS = "LEGGINGS";
    public static final String SLOT_BOOTS = "BOOTS";
    public static final String SLOT_OFFHAND = "OFFHAND";

    public PlayerInventoryService(
        SmartSortPlugin plugin,
        AIService aiService,
        TickSoundManager tickSoundManager,
        DebugLogger debugLogger,
        PlayerPreferenceManager preferenceManager
    ) {
        this.plugin = plugin;
        this.aiService = aiService;
        this.tickSoundManager = tickSoundManager;
        this.debugLogger = debugLogger;
        this.preferenceManager = preferenceManager;
        this.promptBuilder = new AIPromptBuilder();
    }

    public void shutdown() {
        // Clean up any resources if needed
        sortingInProgress.clear();
        lastSortTimes.clear();
    }

    public void toggleAutoSort(Player player) {
        UUID playerId = player.getUniqueId();
        boolean currentValue = preferenceManager.isAutoSortEnabled(playerId);
        boolean newValue = !currentValue;
        preferenceManager.setAutoSortEnabled(playerId, newValue);

        if (newValue) {
            player.sendMessage(
                Component.text(
                    "Player inventory auto-sorting ENABLED! Your inventory will be organized when you open it."
                ).color(NamedTextColor.GREEN)
            );
            // Sort immediately if enabled
            sortPlayerInventory(player);
        } else {
            player.sendMessage(
                Component.text(
                    "Player inventory auto-sorting DISABLED. Your inventory will no longer be automatically sorted."
                ).color(NamedTextColor.YELLOW)
            );
        }
    }

    @EventHandler
    public void onPlayerInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;

        // Check if this is the player's own inventory
        if (event.getInventory().getType() != InventoryType.CRAFTING) return;

        // Check if auto-sort is enabled for this player
        if (!preferenceManager.isAutoSortEnabled(player.getUniqueId())) {
            debugLogger.console(
                "[PlayerInv] Auto-sort disabled for " + player.getName()
            );
            return;
        }

        // Check if sorting is already in progress
        if (sortingInProgress.contains(player.getUniqueId())) {
            debugLogger.console(
                "[PlayerInv] Sorting already in progress for " +
                player.getName()
            );
            return;
        }

        sortPlayerInventory(player);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Clean up resources
        UUID playerId = event.getPlayer().getUniqueId();
        sortingInProgress.remove(playerId);
        lastSortTimes.remove(playerId);
    }

    /**
     * The main method to sort a player's inventory using AI
     */
    public void sortPlayerInventory(Player player) {
        UUID playerId = player.getUniqueId();

        // Check cooldown
        long now = System.currentTimeMillis();
        long lastSort = lastSortTimes.getOrDefault(playerId, 0L);
        long cooldownMillis =
            plugin
                .getConfig()
                .getInt("smart_sort.player_inventory_delay_seconds", 30) *
            1000L;

        if (now - lastSort < cooldownMillis) {
            long remainingSeconds = (lastSort + cooldownMillis - now) / 1000;
            player.sendMessage(
                Component.text(
                    "Please wait " +
                    remainingSeconds +
                    " seconds before sorting inventory again"
                ).color(NamedTextColor.YELLOW)
            );
            return;
        }

        // Fire cancellable event
        PlayerInventorySortEvent event = new PlayerInventorySortEvent(player);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            debugLogger.console(
                "[PlayerInv] Sort event cancelled for " + player.getName()
            );
            return;
        }

        // Update last sort time and mark sorting in progress
        lastSortTimes.put(playerId, now);
        sortingInProgress.add(playerId);

        // Start feedback
        tickSoundManager.start(player);
        player.sendMessage(
            Component.text("Organizing your inventory...").color(
                NamedTextColor.AQUA
            )
        );

        // Extract player inventory items
        List<ItemStack> allItems = extractPlayerItems(player);

        if (allItems.isEmpty()) {
            tickSoundManager.stop(player);
            sortingInProgress.remove(playerId);
            player.sendMessage(
                Component.text("No items to sort").color(NamedTextColor.YELLOW)
            );
            return;
        }

        // Build prompt for AI
        String prompt = promptBuilder.buildPlayerInventoryPrompt(allItems);
        debugLogger.console(
            "[PlayerInv] Sending prompt for " + player.getName()
        );

        // Call AI service
        String model = plugin
            .getConfig()
            .getString("openai.models.large", "gpt-4o");
        aiService
            .chat(prompt, model)
            .thenAcceptAsync(response ->
                Bukkit.getScheduler()
                    .runTask(plugin, () -> {
                        tickSoundManager.stop(player);

                        if (response.isEmpty()) {
                            handleSortingFailure(player, "Empty AI response");
                            return;
                        }

                        debugLogger.console(
                            "[PlayerInv] Received AI response for " +
                            player.getName()
                        );

                        // Parse response and apply to inventory
                        try {
                            Map<String, ItemStack> slotMap =
                                parsePlayerInventoryResponse(
                                    response,
                                    allItems
                                );
                            applySlotMap(player, slotMap);

                            // Fire completed event
                            PlayerInventorySortCompletedEvent completedEvent =
                                new PlayerInventorySortCompletedEvent(
                                    player,
                                    slotMap
                                );
                            Bukkit.getPluginManager().callEvent(completedEvent);

                            // Success feedback
                            player.playSound(
                                player.getLocation(),
                                Sound.ENTITY_EXPERIENCE_ORB_PICKUP,
                                0.7f,
                                1.1f
                            );
                            player.sendMessage(
                                Component.text(
                                    "Inventory organized for pro gameplay!"
                                ).color(NamedTextColor.GREEN)
                            );
                        } catch (Exception e) {
                            handleSortingFailure(
                                player,
                                "Error parsing response: " + e.getMessage()
                            );
                        } finally {
                            sortingInProgress.remove(playerId);
                        }
                    })
            );
    }

    /**
     * Extract all items from a player's inventory
     */
    private List<ItemStack> extractPlayerItems(Player player) {
        List<ItemStack> items = new ArrayList<>();
        PlayerInventory inv = player.getInventory();

        // Main inventory
        for (ItemStack item : inv.getContents()) {
            if (item != null && !item.getType().isAir()) {
                items.add(item.clone());
            }
        }

        // Armor contents
        for (ItemStack item : inv.getArmorContents()) {
            if (item != null && !item.getType().isAir()) {
                items.add(item.clone());
            }
        }

        // Offhand
        ItemStack offhand = inv.getItemInOffHand();
        if (offhand != null && !offhand.getType().isAir()) {
            items.add(offhand.clone());
        }

        return items;
    }

    /**
     * Parse the AI response into a map of slot names to items
     */
    private Map<String, ItemStack> parsePlayerInventoryResponse(
        String response,
        List<ItemStack> originalItems
    ) {
        Map<String, ItemStack> slotMap = new HashMap<>();
        Map<Material, Queue<ItemStack>> materialMap = new HashMap<>();

        // Group original items by material
        for (ItemStack item : originalItems) {
            if (item != null && !item.getType().isAir()) {
                materialMap
                    .computeIfAbsent(item.getType(), k -> new LinkedList<>())
                    .add(item.clone());
            }
        }

        // Process each line of the response
        for (String line : response.split("\n")) {
            line = line.trim();
            // Skip empty lines
            if (line.isEmpty()) continue;

            // Match pattern like "12xSTONE:SLOT_3" or similar with the slot specifier
            if (
                !line.matches(
                    "(?i)\\d+\\s*[xX]\\s*[A-Z0-9_]+\\s*:\\s*[A-Z0-9_]+"
                )
            ) {
                debugLogger.console(
                    "[PlayerInv] Skipping invalid line: " + line
                );
                continue;
            }

            try {
                // Parse amount, material, and slot
                String[] parts = line.split("(?i)\\s*[xX]\\s*");
                int amount = Integer.parseInt(parts[0]);

                String[] materialAndSlot = parts[1].split("\\s*:\\s*");
                String materialName = materialAndSlot[0].trim();
                String slotName = materialAndSlot[1].trim();

                Material material = Material.matchMaterial(materialName);
                if (material == null || material.isAir()) {
                    debugLogger.console(
                        "[PlayerInv] Invalid material: " + materialName
                    );
                    continue;
                }

                // Get the items of this material from our map
                Queue<ItemStack> itemQueue = materialMap.get(material);
                if (itemQueue == null || itemQueue.isEmpty()) {
                    debugLogger.console(
                        "[PlayerInv] No more items of type: " + material
                    );
                    continue;
                }

                // Take items from the queue until we satisfy the amount
                int remainingAmount = amount;
                while (remainingAmount > 0 && !itemQueue.isEmpty()) {
                    ItemStack item = itemQueue.poll();
                    int takeAmount = Math.min(
                        remainingAmount,
                        item.getAmount()
                    );

                    if (takeAmount < item.getAmount()) {
                        // If we're not taking all the item, put the remainder back
                        ItemStack remainder = item.clone();
                        remainder.setAmount(item.getAmount() - takeAmount);
                        itemQueue.add(remainder);
                    }

                    // Create the item to add to the slot map
                    ItemStack slotItem = item.clone();
                    slotItem.setAmount(takeAmount);

                    // Add to slot map, possibly combining with existing items
                    if (slotMap.containsKey(slotName)) {
                        ItemStack existing = slotMap.get(slotName);
                        if (
                            existing.getType() == material &&
                            existing.getAmount() < material.getMaxStackSize()
                        ) {
                            int newAmount = Math.min(
                                existing.getAmount() + takeAmount,
                                material.getMaxStackSize()
                            );
                            existing.setAmount(newAmount);

                            // If we couldn't add all items, create a new slot
                            if (
                                existing.getAmount() <
                                existing.getAmount() + takeAmount
                            ) {
                                int leftover =
                                    (existing.getAmount() + takeAmount) -
                                    material.getMaxStackSize();
                                for (int i = 0; i < 100; i++) { // Avoid infinite loop
                                    String newSlot = "INVENTORY_" + i;
                                    if (!slotMap.containsKey(newSlot)) {
                                        ItemStack leftoverItem = item.clone();
                                        leftoverItem.setAmount(leftover);
                                        slotMap.put(newSlot, leftoverItem);
                                        break;
                                    }
                                }
                            }
                        } else {
                            // Different material, find another slot
                            for (int i = 0; i < 100; i++) { // Avoid infinite loop
                                String newSlot = "INVENTORY_" + i;
                                if (!slotMap.containsKey(newSlot)) {
                                    slotMap.put(newSlot, slotItem);
                                    break;
                                }
                            }
                        }
                    } else {
                        // Slot is free
                        slotMap.put(slotName, slotItem);
                    }

                    remainingAmount -= takeAmount;
                }
            } catch (Exception e) {
                debugLogger.console(
                    "[PlayerInv] Error parsing line: " +
                    line +
                    " - " +
                    e.getMessage()
                );
            }
        }

        // Add any remaining items to available slots
        for (Queue<ItemStack> queue : materialMap.values()) {
            while (!queue.isEmpty()) {
                ItemStack item = queue.poll();

                // Find an empty slot
                for (int i = 0; i < 100; i++) { // Avoid infinite loop
                    String slotName = "INVENTORY_" + i;
                    if (!slotMap.containsKey(slotName)) {
                        slotMap.put(slotName, item);
                        break;
                    }
                }
            }
        }

        return slotMap;
    }

    /**
     * Apply the slot map to the player's inventory
     */
    private void applySlotMap(Player player, Map<String, ItemStack> slotMap) {
        PlayerInventory inv = player.getInventory();

        // Clear inventory first
        inv.clear();

        for (Map.Entry<String, ItemStack> entry : slotMap.entrySet()) {
            String slotName = entry.getKey();
            ItemStack item = entry.getValue();

            try {
                // Handle special slots
                if (slotName.equals(SLOT_HELMET)) {
                    inv.setHelmet(item);
                } else if (slotName.equals(SLOT_CHESTPLATE)) {
                    inv.setChestplate(item);
                } else if (slotName.equals(SLOT_LEGGINGS)) {
                    inv.setLeggings(item);
                } else if (slotName.equals(SLOT_BOOTS)) {
                    inv.setBoots(item);
                } else if (slotName.equals(SLOT_OFFHAND)) {
                    inv.setItemInOffHand(item);
                }
                // Handle hotbar slots
                else if (slotName.startsWith("HOTBAR_")) {
                    int slotIndex = Integer.parseInt(slotName.substring(7));
                    if (slotIndex >= 0 && slotIndex <= 8) {
                        inv.setItem(slotIndex, item);
                    }
                }
                // Handle main inventory slots
                else if (slotName.startsWith("INVENTORY_")) {
                    int slotIndex = Integer.parseInt(slotName.substring(10));
                    if (slotIndex >= 0 && slotIndex <= 26) {
                        inv.setItem(slotIndex + 9, item); // +9 to account for hotbar slots
                    }
                }
                // Unknown slot type - put in any available slot
                else {
                    inv.addItem(item);
                }
            } catch (Exception e) {
                debugLogger.console(
                    "[PlayerInv] Error setting slot " +
                    slotName +
                    ": " +
                    e.getMessage()
                );
                // Try to add the item to any available slot
                inv.addItem(item);
            }
        }
    }

    /**
     * Handle a sorting failure
     */
    private void handleSortingFailure(Player player, String reason) {
        debugLogger.console(
            "[PlayerInv] Sorting failed for " + player.getName() + ": " + reason
        );
        sortingInProgress.remove(player.getUniqueId());

        player.playSound(
            player.getLocation(),
            Sound.ENTITY_VILLAGER_NO,
            0.7f,
            1.0f
        );
        player.sendMessage(
            Component.text("Inventory sorting failed: " + reason).color(
                NamedTextColor.RED
            )
        );

        // Fire event
        Bukkit.getPluginManager()
            .callEvent(
                new SortFailedEvent(player.getInventory(), player, reason)
            );
    }
}
