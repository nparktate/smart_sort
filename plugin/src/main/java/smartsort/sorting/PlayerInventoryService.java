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
        Map<Material, Queue<ItemStack>> materialMap = new HashMap<>();
        
        // Track special slots to ensure we only use each once
        Set<String> specialSlots = new HashSet<>(Arrays.asList(
            SLOT_HELMET, SLOT_CHESTPLATE, SLOT_LEGGINGS, SLOT_BOOTS, SLOT_OFFHAND
        ));
        Set<String> usedSpecialSlots = new HashSet<>();

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

                    // Check if this is a special slot (armor or offhand)
                    if (specialSlots.contains(slotName)) {
                        // Get material type for validation
                        Material itemMaterial = item.getType();
                        String materialCategory = getMaterialCategory(itemMaterial);
                        
                        // If this special slot is already used, reassign to inventory slot
                        if (usedSpecialSlots.contains(slotName)) {
                            debugLogger.console(
                                "[PlayerInv] Special slot " + slotName + " already used, reassigning to inventory slot"
                            );
        
                            // Special handling for boots duplication issue - if reassigning boots, put them in regular inventory
                            if (slotName.equals(SLOT_BOOTS) && itemMaterial == Material.LEATHER_BOOTS) {
                                debugLogger.console("[PlayerInv] Handling special case for leather boots");
            
                                // Find an available inventory slot
                                for (int i = 0; i < 27; i++) {
                                    String newSlot = "INVENTORY_" + i;
                                    if (!slotMap.containsKey(newSlot)) {
                                        slotMap.put(newSlot, slotItem);
                                        break;
                                    }
                                }
                                continue; // Skip the rest of this iteration
                            }
        
                            // Find an available inventory slot
                            for (int i = 0; i < 27; i++) {
                                String newSlot = "INVENTORY_" + i;
                                if (!slotMap.containsKey(newSlot)) {
                                    slotMap.put(newSlot, slotItem);
                                    break;
                                }
                            }
                            continue; // Skip to next iteration
                        }
    
                        // Check if item type is appropriate for this slot
                        String slotCategory = getSlotCategory(slotName);
    
                        if (!materialCategory.equals(slotCategory) && !slotCategory.equals("ANY")) {
                            debugLogger.console(
                                "[PlayerInv] Item type " + itemMaterial + " not appropriate for slot " + slotName + ", reassigning"
                            );
        
                            // Find an available inventory slot
                            for (int i = 0; i < 27; i++) {
                                String newSlot = "INVENTORY_" + i;
                                if (!slotMap.containsKey(newSlot)) {
                                    slotMap.put(newSlot, slotItem);
                                    break;
                                }
                            }
                            continue; // Skip to next iteration
                        }
    
                        // Mark this special slot as used
                        usedSpecialSlots.add(slotName);
                        slotMap.put(slotName, slotItem);
                        debugLogger.console("[PlayerInv] Assigned " + itemMaterial + " to slot " + slotName);
                        continue; // Skip to next iteration
                    }

                    // Handle regular slots with possible stacking
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
                            if (newAmount < existing.getAmount() + takeAmount) {
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
        debugLogger.console("==== STARTING INVENTORY APPLICATION FOR " + player.getName() + " ====");
        PlayerInventory inv = player.getInventory();
        
        // Store armor prior to clearing (we don't want to mess with these if the AI didn't reassign them)
        ItemStack currentHelmet = inv.getHelmet() == null ? null : inv.getHelmet().clone();
        ItemStack currentChestplate = inv.getChestplate() == null ? null : inv.getChestplate().clone();
        ItemStack currentLeggings = inv.getLeggings() == null ? null : inv.getLeggings().clone();
        ItemStack currentBoots = inv.getBoots() == null ? null : inv.getBoots().clone();
        ItemStack currentOffhand = inv.getItemInOffHand() == null ? null : inv.getItemInOffHand().clone();
        
        debugLogger.console("Original Helmet: " + (currentHelmet == null ? "NONE" : currentHelmet.getType()));
        debugLogger.console("Original Chestplate: " + (currentChestplate == null ? "NONE" : currentChestplate.getType()));
        debugLogger.console("Original Leggings: " + (currentLeggings == null ? "NONE" : currentLeggings.getType()));
        debugLogger.console("Original Boots: " + (currentBoots == null ? "NONE" : currentBoots.getType()));
        debugLogger.console("Original Offhand: " + (currentOffhand == null ? "NONE" : currentOffhand.getType()));
        
        // Find armor assignments in the slot map
        ItemStack newHelmet = slotMap.remove(SLOT_HELMET);
        ItemStack newChestplate = slotMap.remove(SLOT_CHESTPLATE);
        ItemStack newLeggings = slotMap.remove(SLOT_LEGGINGS);
        ItemStack newBoots = slotMap.remove(SLOT_BOOTS);
        ItemStack newOffhand = slotMap.remove(SLOT_OFFHAND);
        
        debugLogger.console("New Helmet: " + (newHelmet == null ? "NONE" : newHelmet.getType()));
        debugLogger.console("New Chestplate: " + (newChestplate == null ? "NONE" : newChestplate.getType()));
        debugLogger.console("New Leggings: " + (newLeggings == null ? "NONE" : newLeggings.getType()));
        debugLogger.console("New Boots: " + (newBoots == null ? "NONE" : newBoots.getType()));
        debugLogger.console("New Offhand: " + (newOffhand == null ? "NONE" : newOffhand.getType()));
        
        // IMPORTANT: Clear inventory BEFORE setting armor to avoid duplication
        // Clear only the main inventory and hotbar slots (0-35)
        for (int i = 0; i < 36; i++) {
            inv.setItem(i, null);
        }
        
        // Clear armor slots only if we have replacements
        if (newHelmet != null) {
            inv.setHelmet(null);
        }
        
        if (newChestplate != null) {
            inv.setChestplate(null);
        }
        
        if (newLeggings != null) {
            inv.setLeggings(null);
        }
        
        if (newBoots != null) {
            inv.setBoots(null);
        }
        
        if (newOffhand != null) {
            inv.setItemInOffHand(null);
        }
        
        // Now let's set new armor if specified, otherwise keep the existing armor
        if (newHelmet != null) {
            debugLogger.console("[PlayerInv] Setting helmet to " + newHelmet.getType());
            inv.setHelmet(newHelmet);
        } else if (currentHelmet != null && !currentHelmet.getType().isAir()) {
            debugLogger.console("[PlayerInv] Keeping current helmet: " + currentHelmet.getType());
            inv.setHelmet(currentHelmet);
        }
        
        if (newChestplate != null) {
            debugLogger.console("[PlayerInv] Setting chestplate to " + newChestplate.getType());
            inv.setChestplate(newChestplate);
        } else if (currentChestplate != null && !currentChestplate.getType().isAir()) {
            debugLogger.console("[PlayerInv] Keeping current chestplate: " + currentChestplate.getType());
            inv.setChestplate(currentChestplate);
        }
        
        if (newLeggings != null) {
            debugLogger.console("[PlayerInv] Setting leggings to " + newLeggings.getType());
            inv.setLeggings(newLeggings);
        } else if (currentLeggings != null && !currentLeggings.getType().isAir()) {
            debugLogger.console("[PlayerInv] Keeping current leggings: " + currentLeggings.getType());
            inv.setLeggings(currentLeggings);
        }
        
        if (newBoots != null) {
            debugLogger.console("[PlayerInv] Setting boots to " + newBoots.getType());
            inv.setBoots(newBoots);
        } else if (currentBoots != null && !currentBoots.getType().isAir()) {
            debugLogger.console("[PlayerInv] Keeping current boots: " + currentBoots.getType());
            inv.setBoots(currentBoots);
        }
        
        if (newOffhand != null) {
            debugLogger.console("[PlayerInv] Setting offhand to " + newOffhand.getType());
            inv.setItemInOffHand(newOffhand);
        } else if (currentOffhand != null && !currentOffhand.getType().isAir()) {
            debugLogger.console("[PlayerInv] Keeping current offhand: " + currentOffhand.getType());
            inv.setItemInOffHand(currentOffhand);
        }
        
        // Apply the rest of the items to the inventory
        for (Map.Entry<String, ItemStack> entry : slotMap.entrySet()) {
            String slotName = entry.getKey();
            ItemStack item = entry.getValue();
            
            try {
                // Handle hotbar slots
                if (slotName.startsWith("HOTBAR_")) {
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
                // Any leftovers - put in any available slot
                else {
                    inv.addItem(item);
                }
            } catch (Exception e) {
                debugLogger.console("[PlayerInv] Error setting slot " + slotName + ": " + e.getMessage());
                // Try to add the item to any available slot
                inv.addItem(item);
            }
        }
        
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
     * Get the category of a material (HELMET, CHESTPLATE, etc.)
     */
    private String getMaterialCategory(Material material) {
        String name = material.name();
        
        if (name.endsWith("_HELMET") || name.equals("TURTLE_SHELL")) {
            return "HELMET";
        } else if (name.endsWith("_CHESTPLATE")) {
            return "CHESTPLATE";
        } else if (name.endsWith("_LEGGINGS")) {
            return "LEGGINGS";
        } else if (name.endsWith("_BOOTS")) {
            return "BOOTS";
        } else if (name.equals("SHIELD")) {
            return "OFFHAND";
        } else {
            return "OTHER";
        }
    }
    
    /**
     * Get the category of a slot (what types of items it can hold)
     */
    private String getSlotCategory(String slotName) {
        switch (slotName) {
            case SLOT_HELMET:
                return "HELMET";
            case SLOT_CHESTPLATE:
                return "CHESTPLATE";
            case SLOT_LEGGINGS:
                return "LEGGINGS";
            case SLOT_BOOTS:
                return "BOOTS";
            case SLOT_OFFHAND:
                return "ANY"; // Offhand can hold anything
            default:
                return "ANY";
        }
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
