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
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;
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
    private BukkitTask inventoryCheckTask;

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

        // Start the inventory checking task
        startInventoryCheckTask();
    }

    public void shutdown() {
        // Clean up any resources if needed
        sortingInProgress.clear();
        lastSortTimes.clear();

        // Cancel the inventory check task
        if (inventoryCheckTask != null) {
            inventoryCheckTask.cancel();
            inventoryCheckTask = null;
        }
    }

    // Add this method to start a task that checks for open inventories
    private void startInventoryCheckTask() {
        inventoryCheckTask = plugin
            .getServer()
            .getScheduler()
            .runTaskTimer(
                plugin,
                () -> {
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        // Skip if auto-sort not enabled
                        if (
                            !preferenceManager.isAutoSortEnabled(
                                player.getUniqueId()
                            )
                        ) {
                            continue;
                        }

                        // Check if player has inventory open
                        InventoryView view = player.getOpenInventory();

                        // Only add the indicator for proper inventory views
                        if (view.getType() == InventoryType.CRAFTING) {
                            // Get the top inventory
                            Inventory topInventory = view.getTopInventory();

                            // Check if it's a crafting inventory
                            if (
                                topInventory.getType() == InventoryType.CRAFTING
                            ) {
                                // Skip if there are items in the crafting grid (slots 1-4)
                                boolean craftingInProgress = false;
                                for (int slot = 1; slot <= 4; slot++) {
                                    ItemStack item = topInventory.getItem(slot);
                                    if (
                                        item != null &&
                                        item.getType() != Material.AIR
                                    ) {
                                        craftingInProgress = true;
                                        break;
                                    }
                                }

                                if (craftingInProgress) {
                                    continue;
                                }

                                // Check if output slot is empty
                                ItemStack outputItem = topInventory.getItem(0);
                                if (
                                    outputItem != null &&
                                    outputItem.getType() != Material.AIR
                                ) {
                                    continue;
                                }

                                // Create the sorting indicator
                                ItemStack sortIndicator = new ItemStack(
                                    Material.HOPPER,
                                    1
                                );
                                ItemMeta meta = sortIndicator.getItemMeta();
                                if (meta != null) {
                                    meta.displayName(
                                        Component.text(
                                            "Click to Sort Inventory",
                                            NamedTextColor.GOLD
                                        )
                                    );

                                    List<Component> lore = new ArrayList<>();
                                    lore.add(
                                        Component.text(
                                            "Click to organize your inventory",
                                            NamedTextColor.GRAY
                                        )
                                    );
                                    meta.lore(lore);

                                    meta.addItemFlags(
                                        org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES
                                    );
                                    meta.addItemFlags(
                                        org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS
                                    );
                                    sortIndicator.setItemMeta(meta);

                                    // Set the indicator in the output slot
                                    topInventory.setItem(0, sortIndicator);
                                }
                            }
                        }
                    }
                },
                10L, // Initial delay
                10L // Run every 10 ticks (0.5 seconds)
            );
    }

    public void toggleAutoSort(Player player) {
        UUID playerId = player.getUniqueId();
        boolean currentValue = preferenceManager.isAutoSortEnabled(playerId);
        boolean newValue = !currentValue;
        preferenceManager.setAutoSortEnabled(playerId, newValue);

        if (newValue) {
            player.sendMessage(
                Component.text(
                    "Smart inventory sorting ENABLED! Open your inventory and click the hopper icon to sort."
                ).color(NamedTextColor.GREEN)
            );
            // Sort immediately if enabled
            sortPlayerInventory(player);
        } else {
            player.sendMessage(
                Component.text(
                    "Smart inventory sorting DISABLED. The sort button will no longer appear."
                ).color(NamedTextColor.YELLOW)
            );
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Clean up resources
        UUID playerId = event.getPlayer().getUniqueId();
        sortingInProgress.remove(playerId);
        lastSortTimes.remove(playerId);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryInteractionDuringSorting(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        
        // If sorting is in progress for this player, block ALL inventory interactions
        if (sortingInProgress.contains(player.getUniqueId())) {
            event.setCancelled(true);
            
            // Optional: notify player that sorting is in progress (20% chance to avoid spam)
            if (Math.random() < 0.2) {
                player.sendMessage(
                    Component.text("Please wait, sorting in progress...").color(NamedTextColor.YELLOW)
                );
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onPlayerClickInventory(InventoryClickEvent event) {
        // Skip if not a player
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        // Skip if auto-sort not enabled
        if (!preferenceManager.isAutoSortEnabled(player.getUniqueId())) {
            return;
        }

        // Check if clicking in a crafting inventory
        if (
            event.getClickedInventory() == null ||
            event.getClickedInventory().getType() != InventoryType.CRAFTING
        ) {
            return;
        }

        // Check if clicking the crafting result slot (slot 0)
        if (event.getSlot() != 0) {
            return;
        }

        // Check if the clicked item is our sort indicator
        ItemStack clickedItem = event.getCurrentItem();
        if (clickedItem == null || clickedItem.getType() != Material.HOPPER) {
            return;
        }

        // Verify it's our special hopper item with the right name
        ItemMeta meta = clickedItem.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) {
            return;
        }

        Component displayName = meta.displayName();
        if (
            displayName == null ||
            !displayName.equals(
                Component.text("Click to Sort Inventory", NamedTextColor.GOLD)
            )
        ) {
            return;
        }

        // Cancel the event to prevent picking up the item
        event.setCancelled(true);

        // Only proceed if not on cooldown
        long now = System.currentTimeMillis();
        UUID playerId = player.getUniqueId();
        long cooldownMillis =
            plugin
                .getConfig()
                .getInt("smart_sort.player_inventory_delay_seconds", 30) *
            1000L;

        if (now - lastSortTimes.getOrDefault(playerId, 0L) < cooldownMillis) {
            long remainingSeconds =
                (lastSortTimes.get(playerId) + cooldownMillis - now) / 1000;
            player.sendMessage(
                Component.text(
                    "Please wait " +
                    remainingSeconds +
                    " seconds before sorting again"
                ).color(NamedTextColor.YELLOW)
            );
            return;
        }

        // Check if sorting already in progress
        if (sortingInProgress.contains(playerId)) {
            return;
        }

        // Play a sound effect
        player.playSound(
            player.getLocation(),
            Sound.UI_BUTTON_CLICK,
            0.5f,
            1.0f
        );

        // Trigger sorting
        Bukkit.getScheduler()
            .runTask(plugin, () -> sortPlayerInventory(player));
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
                        
                        // Debug: Log the AI response to see slot assignments
                        debugLogger.console("[PlayerInv] AI Response:\n" + response);

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

        // Main inventory (0-35) - avoiding iteration to be more explicit
        for (int i = 0; i < 36; i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && !item.getType().isAir()) {
                items.add(item.clone());
            }
        }

        // Handle armor individually to be explicit
        ItemStack helmet = inv.getHelmet();
        if (helmet != null && !helmet.getType().isAir()) {
            items.add(helmet.clone());
            debugLogger.console("[PlayerInv] Extracted HELMET: " + helmet.getType() + " x" + helmet.getAmount());
        }
        
        ItemStack chestplate = inv.getChestplate();
        if (chestplate != null && !chestplate.getType().isAir()) {
            items.add(chestplate.clone());
            debugLogger.console("[PlayerInv] Extracted CHESTPLATE: " + chestplate.getType() + " x" + chestplate.getAmount());
        }
        
        ItemStack leggings = inv.getLeggings();
        if (leggings != null && !leggings.getType().isAir()) {
            items.add(leggings.clone());
            debugLogger.console("[PlayerInv] Extracted LEGGINGS: " + leggings.getType() + " x" + leggings.getAmount());
        }
        
        ItemStack boots = inv.getBoots();
        if (boots != null && !boots.getType().isAir()) {
            items.add(boots.clone());
            debugLogger.console("[PlayerInv] Extracted BOOTS: " + boots.getType() + " x" + boots.getAmount());
        }

        // Offhand
        ItemStack offhand = inv.getItemInOffHand();
        if (offhand != null && !offhand.getType().isAir()) {
            items.add(offhand.clone());
            debugLogger.console("[PlayerInv] Extracted OFFHAND: " + offhand.getType() + " x" + offhand.getAmount());
        }

        debugLogger.console(
            "[PlayerInv] Extracted " + items.size() + " items from " + player.getName() + "'s inventory"
        );
        
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
        
        // Step 1: Create a deep copy of original items to work with
        List<ItemStack> availableItems = new ArrayList<>();
        for (ItemStack item : originalItems) {
            if (item != null && !item.getType().isAir()) {
                availableItems.add(item.clone());
            }
        }
        
        debugLogger.console("[AI] Original inventory contains " + availableItems.size() + " items");
        // Log just the first 10 lines of the AI response to avoid cluttering the logs
        String responsePreview = response.length() > 500 ? response.substring(0, 500) + "..." : response;
        debugLogger.console("[AI] AI response preview:\n" + responsePreview);
        
        // Step 2: Parse the AI response into slot assignments
        List<AISlotAssignment> assignments = new ArrayList<>();
        
        for (String line : response.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            
            // Parse assignment line (format: "12xSTONE:SLOT_3")
            if (!line.matches("(?i)\\d+\\s*[xX]\\s*[A-Z0-9_]+\\s*:\\s*[A-Z0-9_]+")) {
                debugLogger.console("[AI] Skipping invalid line: " + line);
                continue;
            }
            
            try {
                // Extract amount, material name, and slot
                String[] parts = line.split("(?i)\\s*[xX]\\s*");
                int requestedAmount = Integer.parseInt(parts[0]);
                
                String[] materialAndSlot = parts[1].split("\\s*:\\s*");
                String materialName = materialAndSlot[0].trim();
                String slotName = materialAndSlot[1].trim();
                
                Material material = Material.matchMaterial(materialName);
                if (material == null || material.isAir()) {
                    debugLogger.console("[AI] Invalid material: " + materialName);
                    continue;
                }
                
                // Add to assignments list with priority based on slot type
                int priority = getSlotPriority(slotName);
                assignments.add(new AISlotAssignment(material, requestedAmount, slotName, priority));
            } catch (Exception e) {
                debugLogger.console("[AI] Error parsing line: " + line + " - " + e.getMessage());
            }
        }
        
        // Step 3: Sort assignments by priority (armor first, then hotbar, then inventory)
        assignments.sort(Comparator.comparingInt(a -> a.priority));
        
        // Step 4: Process assignments in priority order
        Set<String> usedSlots = new HashSet<>();
        
        for (AISlotAssignment assignment : assignments) {
            // Skip if slot already used
            if (usedSlots.contains(assignment.slotName)) {
                debugLogger.console("[AI] Slot " + assignment.slotName + " already assigned, skipping");
                continue;
            }
            
            // Find matching items in available items
            List<Integer> matchingIndices = new ArrayList<>();
            for (int i = 0; i < availableItems.size(); i++) {
                if (availableItems.get(i).getType() == assignment.material) {
                    matchingIndices.add(i);
                }
            }
            
            if (matchingIndices.isEmpty()) {
                debugLogger.console("[AI] No available items of type " + assignment.material);
                continue;
            }
            
            // For armor slots, we want exactly one item
            if (isArmorSlot(assignment.slotName)) {
                // Get the first matching item
                int index = matchingIndices.get(0);
                ItemStack originalItem = availableItems.get(index);
                
                // Create a copy with amount=1 for the armor slot
                ItemStack armorItem = originalItem.clone();
                armorItem.setAmount(1);
                
                // Add to slot map
                slotMap.put(assignment.slotName, armorItem);
                usedSlots.add(assignment.slotName);
                
                // Update or remove the original item
                if (originalItem.getAmount() > 1) {
                    originalItem.setAmount(originalItem.getAmount() - 1);
                } else {
                    availableItems.remove(index);
                }
            }
            // For regular slots, try to match the requested amount
            else {
                int remainingAmount = assignment.requestedAmount;
                List<ItemStack> collectedItems = new ArrayList<>();
                
                // Use a copy of matchingIndices to avoid ConcurrentModificationException
                List<Integer> indicesToRemove = new ArrayList<>();
                
                for (int index : matchingIndices) {
                    if (remainingAmount <= 0) break;
                    
                    ItemStack item = availableItems.get(index);
                    int takeAmount = Math.min(remainingAmount, item.getAmount());
                    
                    // Create a copy for the collected items
                    ItemStack takenItem = item.clone();
                    takenItem.setAmount(takeAmount);
                    collectedItems.add(takenItem);
                    
                    // Update or mark for removal
                    if (item.getAmount() > takeAmount) {
                        item.setAmount(item.getAmount() - takeAmount);
                    } else {
                        indicesToRemove.add(index);
                    }
                    
                    remainingAmount -= takeAmount;
                }
                
                // Remove items marked for removal (in reverse order to avoid index shifting)
                indicesToRemove.sort(Collections.reverseOrder());
                for (int index : indicesToRemove) {
                    availableItems.remove(index);
                }
                
                // Combine collected items up to max stack size
                List<ItemStack> stacks = combineItems(collectedItems);
                
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
                            debugLogger.console("[AI] No available slot for extra stack of " + stacks.get(i).getType());
                            break;
                        }
                    }
                }
            }
        }
        
        // Step 5: Place remaining items in available slots
        for (ItemStack item : availableItems) {
            String availableSlot = findAvailableSlot(usedSlots);
            if (availableSlot != null) {
                slotMap.put(availableSlot, item);
                usedSlots.add(availableSlot);
            } else {
                debugLogger.console("[AI] WARNING: No available slot for item " + item.getType());
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
        return slotName.equals(SLOT_HELMET) || 
               slotName.equals(SLOT_CHESTPLATE) || 
               slotName.equals(SLOT_LEGGINGS) || 
               slotName.equals(SLOT_BOOTS) || 
               slotName.equals(SLOT_OFFHAND);
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
        
        AISlotAssignment(Material material, int requestedAmount, String slotName, int priority) {
            this.material = material;
            this.requestedAmount = requestedAmount;
            this.slotName = slotName;
            this.priority = priority;
        }
    }
    

    

    


    /**
     * Apply the slot map to the player's inventory
     */
    private void applySlotMap(Player player, Map<String, ItemStack> slotMap) {
        debugLogger.console("==== STARTING INVENTORY APPLICATION FOR " + player.getName() + " ====");
        PlayerInventory inv = player.getInventory();

        // STEP 1: Take snapshot of ALL items in the player's inventory (including armor)
        Map<String, ItemStack> originalArmorItems = new HashMap<>();
        
        // Save current armor items
        ItemStack currentHelmet = inv.getHelmet();
        if (currentHelmet != null && !currentHelmet.getType().isAir()) {
            originalArmorItems.put(SLOT_HELMET, currentHelmet.clone());
            debugLogger.console("Original Helmet: " + currentHelmet.getType());
        } else {
            debugLogger.console("Original Helmet: NONE");
        }
        
        ItemStack currentChestplate = inv.getChestplate();
        if (currentChestplate != null && !currentChestplate.getType().isAir()) {
            originalArmorItems.put(SLOT_CHESTPLATE, currentChestplate.clone());
            debugLogger.console("Original Chestplate: " + currentChestplate.getType());
        } else {
            debugLogger.console("Original Chestplate: NONE");
        }
        
        ItemStack currentLeggings = inv.getLeggings();
        if (currentLeggings != null && !currentLeggings.getType().isAir()) {
            originalArmorItems.put(SLOT_LEGGINGS, currentLeggings.clone());
            debugLogger.console("Original Leggings: " + currentLeggings.getType());
        } else {
            debugLogger.console("Original Leggings: NONE");
        }
        
        ItemStack currentBoots = inv.getBoots();
        if (currentBoots != null && !currentBoots.getType().isAir()) {
            originalArmorItems.put(SLOT_BOOTS, currentBoots.clone());
            debugLogger.console("Original Boots: " + currentBoots.getType());
        } else {
            debugLogger.console("Original Boots: NONE");
        }
        
        ItemStack currentOffhand = inv.getItemInOffHand();
        if (currentOffhand != null && !currentOffhand.getType().isAir()) {
            originalArmorItems.put(SLOT_OFFHAND, currentOffhand.clone());
            debugLogger.console("Original Offhand: " + currentOffhand.getType());
        } else {
            debugLogger.console("Original Offhand: NONE");
        }
        
        // STEP 2: Extract armor assignments from slot map first
        Map<String, ItemStack> newArmorItems = new HashMap<>();
        
        // Extract special slots
        ItemStack newHelmet = slotMap.remove(SLOT_HELMET);
        if (newHelmet != null) {
            newArmorItems.put(SLOT_HELMET, newHelmet);
            debugLogger.console("New Helmet: " + newHelmet.getType());
        } else {
            debugLogger.console("New Helmet: NONE");
        }
        
        ItemStack newChestplate = slotMap.remove(SLOT_CHESTPLATE);
        if (newChestplate != null) {
            newArmorItems.put(SLOT_CHESTPLATE, newChestplate);
            debugLogger.console("New Chestplate: " + newChestplate.getType());
        } else {
            debugLogger.console("New Chestplate: NONE");
        }
        
        ItemStack newLeggings = slotMap.remove(SLOT_LEGGINGS);
        if (newLeggings != null) {
            newArmorItems.put(SLOT_LEGGINGS, newLeggings);
            debugLogger.console("New Leggings: " + newLeggings.getType());
        } else {
            debugLogger.console("New Leggings: NONE");
        }
        
        ItemStack newBoots = slotMap.remove(SLOT_BOOTS);
        if (newBoots != null) {
            newArmorItems.put(SLOT_BOOTS, newBoots);
            debugLogger.console("New Boots: " + newBoots.getType());
        } else {
            debugLogger.console("New Boots: NONE");
        }
        
        ItemStack newOffhand = slotMap.remove(SLOT_OFFHAND);
        if (newOffhand != null) {
            newArmorItems.put(SLOT_OFFHAND, newOffhand);
            debugLogger.console("New Offhand: " + newOffhand.getType());
        } else {
            debugLogger.console("New Offhand: NONE");
        }
        
        // STEP 3: COMPLETELY CLEAR the player's inventory (including armor)
        inv.clear();
        
        // STEP 4: Apply armor items first
        try {
            if (newHelmet != null) {
                debugLogger.console("Setting helmet to: " + newHelmet.getType());
                inv.setHelmet(newHelmet);
            } else if (originalArmorItems.containsKey(SLOT_HELMET)) {
                debugLogger.console("Restoring original helmet: " + originalArmorItems.get(SLOT_HELMET).getType());
                inv.setHelmet(originalArmorItems.get(SLOT_HELMET));
            }
            
            if (newChestplate != null) {
                debugLogger.console("Setting chestplate to: " + newChestplate.getType());
                inv.setChestplate(newChestplate);
            } else if (originalArmorItems.containsKey(SLOT_CHESTPLATE)) {
                debugLogger.console("Restoring original chestplate: " + originalArmorItems.get(SLOT_CHESTPLATE).getType());
                inv.setChestplate(originalArmorItems.get(SLOT_CHESTPLATE));
            }
            
            if (newLeggings != null) {
                debugLogger.console("Setting leggings to: " + newLeggings.getType());
                inv.setLeggings(newLeggings);
            } else if (originalArmorItems.containsKey(SLOT_LEGGINGS)) {
                debugLogger.console("Restoring original leggings: " + originalArmorItems.get(SLOT_LEGGINGS).getType());
                inv.setLeggings(originalArmorItems.get(SLOT_LEGGINGS));
            }
            
            if (newBoots != null) {
                debugLogger.console("Setting boots to: " + newBoots.getType());
                inv.setBoots(newBoots);
            } else if (originalArmorItems.containsKey(SLOT_BOOTS)) {
                debugLogger.console("Restoring original boots: " + originalArmorItems.get(SLOT_BOOTS).getType());
                inv.setBoots(originalArmorItems.get(SLOT_BOOTS));
            }
            
            if (newOffhand != null) {
                debugLogger.console("Setting offhand to: " + newOffhand.getType());
                inv.setItemInOffHand(newOffhand);
            } else if (originalArmorItems.containsKey(SLOT_OFFHAND)) {
                debugLogger.console("Restoring original offhand: " + originalArmorItems.get(SLOT_OFFHAND).getType());
                inv.setItemInOffHand(originalArmorItems.get(SLOT_OFFHAND));
            }
        } catch (Exception e) {
            debugLogger.console("Error setting armor: " + e.getMessage());
            e.printStackTrace();
        }
        
        // STEP 5: Apply the rest of the items to the inventory slots
        for (Map.Entry<String, ItemStack> entry : slotMap.entrySet()) {
            String slotName = entry.getKey();
            ItemStack item = entry.getValue();
            
            if (item == null || item.getType().isAir()) continue;
            
            try {
                // Handle hotbar slots
                if (slotName.startsWith("HOTBAR_")) {
                    int slotIndex = Integer.parseInt(slotName.substring(7));
                    if (slotIndex >= 0 && slotIndex <= 8) {
                        inv.setItem(slotIndex, item);
                        debugLogger.console("Set hotbar slot " + slotIndex + " to " + item.getType() + " x" + item.getAmount());
                    }
                }
                // Handle main inventory slots
                else if (slotName.startsWith("INVENTORY_")) {
                    int slotIndex = Integer.parseInt(slotName.substring(10));
                    if (slotIndex >= 0 && slotIndex <= 26) {
                        inv.setItem(slotIndex + 9, item); // +9 to account for hotbar slots
                        debugLogger.console("Set inventory slot " + (slotIndex + 9) + " to " + item.getType() + " x" + item.getAmount());
                    }
                }
                // Any leftovers - put in any available slot
                else {
                    inv.addItem(item);
                    debugLogger.console("Added leftover item " + item.getType() + " x" + item.getAmount());
                }
            } catch (Exception e) {
                debugLogger.console("Error setting slot " + slotName + ": " + e.getMessage());
                // Try to add the item to any available slot
                inv.addItem(item);
            }
        }
        
        // STEP 6: Log final armor state
        debugLogger.console("Final armor state:");
        debugLogger.console("Helmet: " + (inv.getHelmet() == null ? "NONE" : inv.getHelmet().getType()));
        debugLogger.console("Chestplate: " + (inv.getChestplate() == null ? "NONE" : inv.getChestplate().getType()));
        debugLogger.console("Leggings: " + (inv.getLeggings() == null ? "NONE" : inv.getLeggings().getType()));
        debugLogger.console("Boots: " + (inv.getBoots() == null ? "NONE" : inv.getBoots().getType()));
        debugLogger.console("Offhand: " + (inv.getItemInOffHand() == null ? "NONE" : inv.getItemInOffHand().getType()));
        
        debugLogger.console("==== FINISHED INVENTORY APPLICATION FOR " + player.getName() + " ====");
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
    


    /**
     * Force a sort of the player's inventory without cooldown check
     */
    public void forcePlayerInventorySort(Player player) {
        UUID playerId = player.getUniqueId();
        if (sortingInProgress.contains(playerId)) {
            debugLogger.console(
                "[PlayerInv] Sorting already in progress for " +
                player.getName()
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

        // Mark sorting in progress (but don't update last sort time to avoid affecting cooldown)
        sortingInProgress.add(playerId);

        // Start feedback
        tickSoundManager.start(player);
        player.sendMessage(
            Component.text("Immediately organizing your inventory...").color(
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
            "[PlayerInv] Sending immediate sort prompt for " + player.getName()
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
                            "[PlayerInv] Received AI response for immediate sort " +
                            player.getName()
                        );
                        
                        // Debug: Log the AI response to see slot assignments
                        debugLogger.console("[PlayerInv] AI Response:\n" + response);

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
                                    "Inventory immediately organized!"
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
}
