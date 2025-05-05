package smartsort.players;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import smartsort.SmartSortPlugin;
import smartsort.api.openai.OpenAIPromptBuilder;
import smartsort.api.openai.OpenAIService;
import smartsort.events.inventory.SortFailedEvent;
import smartsort.events.player.PlayerInventorySortCompletedEvent;
import smartsort.events.player.PlayerInventorySortEvent;
import smartsort.players.inventory.PlayerInventoryApplier;
import smartsort.players.inventory.PlayerInventoryExtractor;
import smartsort.players.inventory.PlayerInventoryExtractor.PlayerArmorSnapshot;
import smartsort.players.inventory.PlayerInventoryResponseParser;
import smartsort.players.ui.SortButtonManager;
import smartsort.players.ui.SortButtonManager.SortButtonClickCallback;
import smartsort.util.DebugLogger;
import smartsort.util.InventoryChangeTracker;
import smartsort.util.PlayerPreferenceManager;
import smartsort.util.TickSoundManager;

/**
 * Core service that coordinates player inventory sorting.
 */
public class PlayerInventorySorter
    implements Listener, SortButtonClickCallback {

    private final SmartSortPlugin plugin;
    private final OpenAIService aiService;
    private final TickSoundManager tickSoundManager;
    private final DebugLogger debugLogger;
    private final PlayerPreferenceManager preferenceManager;
    private final OpenAIPromptBuilder promptBuilder;
    private final Map<UUID, Long> lastSortTimes = new ConcurrentHashMap<>();
    private final Set<UUID> sortingInProgress = ConcurrentHashMap.newKeySet();

    // Modularity: references to specialized components
    private final PlayerInventoryExtractor inventoryExtractor;
    private final PlayerInventoryResponseParser responseParser;
    private final PlayerInventoryApplier inventoryApplier;
    private final SortButtonManager uiManager;
    private final InventoryChangeTracker changeTracker;

    public PlayerInventorySorter(
        SmartSortPlugin plugin,
        OpenAIService aiService,
        TickSoundManager tickSoundManager,
        DebugLogger debugLogger,
        PlayerPreferenceManager preferenceManager,
        PlayerInventoryExtractor inventoryExtractor,
        PlayerInventoryResponseParser responseParser,
        PlayerInventoryApplier inventoryApplier,
        SortButtonManager uiManager,
        InventoryChangeTracker changeTracker
    ) {
        this.plugin = plugin;
        this.aiService = aiService;
        this.tickSoundManager = tickSoundManager;
        this.debugLogger = debugLogger;
        this.preferenceManager = preferenceManager;
        this.promptBuilder = new OpenAIPromptBuilder();

        // Store references to specialized components
        this.inventoryExtractor = inventoryExtractor;
        this.responseParser = responseParser;
        this.inventoryApplier = inventoryApplier;
        this.uiManager = uiManager;
        this.changeTracker = changeTracker;

        // Set this class as the UI callback
        this.uiManager.setSortButtonClickCallback(this);
    }

    /**
     * Called when the sort button is clicked
     */
    @Override
    public void onSortButtonClick(Player player) {
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
     * Clean up resources
     */
    public void shutdown() {
        sortingInProgress.clear();
        lastSortTimes.clear();

        // Shut down UI manager
        uiManager.shutdown();
    }

    /**
     * Handle player quit
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Clean up resources
        UUID playerId = event.getPlayer().getUniqueId();
        sortingInProgress.remove(playerId);
        lastSortTimes.remove(playerId);
    }

    /**
     * Block inventory interaction during sorting
     */
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
                    Component.text("Please wait, sorting in progress...").color(
                        NamedTextColor.YELLOW
                    )
                );
            }
        }
    }

    /**
     * Toggle auto-sort for a player
     */
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

        // Take a snapshot of armor state
        PlayerArmorSnapshot armorSnapshot =
            inventoryExtractor.takeArmorSnapshot(player);

        // Extract player inventory items
        List<ItemStack> allItems = inventoryExtractor.extractPlayerItems(
            player
        );

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

        // Store sort start timestamp for inventory change validation
        final long sortStartTime = System.currentTimeMillis();

        // Call AI service
        String model = plugin
            .getConfig()
            .getString("openai.models.large", "gpt-4o");
        aiService
            .chat(prompt, model)
            .thenAcceptAsync(response ->
                Bukkit.getScheduler()
                    .runTask(plugin, () -> {
                        // Stop feedback first
                        tickSoundManager.stop(player);

                        // Check if player still online
                        if (!player.isOnline()) {
                            debugLogger.console(
                                "[PlayerInv] Player " +
                                player.getName() +
                                " went offline during sorting"
                            );
                            sortingInProgress.remove(playerId);
                            return;
                        }

                        // Check if inventory changed during sorting
                        if (
                            changeTracker.hasPlayerInventoryChangedSince(
                                playerId,
                                sortStartTime
                            )
                        ) {
                            handleSortingFailure(
                                player,
                                "Inventory changed during sorting"
                            );
                            return;
                        }

                        if (response.isEmpty()) {
                            handleSortingFailure(player, "Empty AI response");
                            return;
                        }

                        debugLogger.console(
                            "[PlayerInv] Received AI response for " +
                            player.getName()
                        );

                        // Debug: Log the AI response to see slot assignments
                        debugLogger.console(
                            "[PlayerInv] AI Response:\n" + response
                        );

                        // Parse response and apply to inventory
                        try {
                            Map<String, ItemStack> slotMap =
                                responseParser.parseResponse(
                                    response,
                                    allItems
                                );
                            inventoryApplier.applySlotMap(
                                player,
                                slotMap,
                                armorSnapshot
                            );

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

        // Take a snapshot of current armor
        PlayerArmorSnapshot armorSnapshot =
            inventoryExtractor.takeArmorSnapshot(player);

        // Extract player inventory items
        List<ItemStack> allItems = inventoryExtractor.extractPlayerItems(
            player
        );

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

        // Store sort start timestamp for inventory change validation
        final long sortStartTime = System.currentTimeMillis();

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

                        // Check if player still online
                        if (!player.isOnline()) {
                            debugLogger.console(
                                "[PlayerInv] Player " +
                                player.getName() +
                                " went offline during sorting"
                            );
                            sortingInProgress.remove(playerId);
                            return;
                        }

                        // Check if inventory changed during sorting
                        if (
                            changeTracker.hasPlayerInventoryChangedSince(
                                playerId,
                                sortStartTime
                            )
                        ) {
                            handleSortingFailure(
                                player,
                                "Inventory changed during sorting"
                            );
                            return;
                        }

                        if (response.isEmpty()) {
                            handleSortingFailure(player, "Empty AI response");
                            return;
                        }

                        debugLogger.console(
                            "[PlayerInv] Received AI response for immediate sort " +
                            player.getName()
                        );

                        // Debug: Log the AI response to see slot assignments
                        debugLogger.console(
                            "[PlayerInv] AI Response:\n" + response
                        );

                        // Parse response and apply to inventory
                        try {
                            Map<String, ItemStack> slotMap =
                                responseParser.parseResponse(
                                    response,
                                    allItems
                                );
                            inventoryApplier.applySlotMap(
                                player,
                                slotMap,
                                armorSnapshot
                            );

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
