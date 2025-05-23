package smartsort.containers;

import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import smartsort.SmartSortPlugin;
import smartsort.api.openai.OpenAIPromptBuilder;
import smartsort.api.openai.OpenAIResponseParser;
import smartsort.api.openai.OpenAIService;
import smartsort.events.inventory.InventorySortEvent;
import smartsort.events.inventory.SortCompletedEvent;
import smartsort.events.inventory.SortFailedEvent;
import smartsort.util.DebugLogger;
import smartsort.util.InventoryChangeTracker;
import smartsort.util.TickSoundManager;
import smartsort.util.VersionedCache;

public class ContainerSorter implements Listener {

    private final SmartSortPlugin plugin;
    private final int cooldownTicks;
    private final Map<String, String> signatures = new HashMap<>();
    private final Map<String, Long> lastSorted = new HashMap<>();
    private final Set<Location> inProgress = new HashSet<>();

    private final OpenAIService ai;
    private final TickSoundManager tick;
    private final DebugLogger debug;
    private final OpenAIPromptBuilder promptBuilder;
    private final ContainerSignatureGenerator inventoryAnalyzer;
    private final OpenAIResponseParser responseParser;
    private final InventoryChangeTracker changeTracker;
    private final VersionedCache<String, List<ItemStack>> versionedCache;
    private final ContainerExtractor containerExtractor;
    private final ContainerSortApplier containerSortApplier;

    public ContainerSorter(
        SmartSortPlugin pl,
        OpenAIService ai,
        TickSoundManager tick,
        DebugLogger dbg,
        InventoryChangeTracker changeTracker,
        VersionedCache<String, List<ItemStack>> versionedCache,
        ContainerExtractor containerExtractor,
        ContainerSortApplier containerSortApplier
    ) {
        this.plugin = pl;
        this.ai = ai;
        this.tick = tick;
        this.debug = dbg;
        this.changeTracker = changeTracker;
        this.versionedCache = versionedCache;
        this.containerExtractor = containerExtractor;
        this.containerSortApplier = containerSortApplier;
        this.promptBuilder = new OpenAIPromptBuilder();
        this.inventoryAnalyzer = new ContainerSignatureGenerator();
        this.responseParser = new OpenAIResponseParser(dbg);
        this.cooldownTicks =
            pl.getConfig().getInt("smart_sort.delay_seconds", 3) * 20;
    }

    @EventHandler
    public void onOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;

        Inventory inv = e.getInventory();
        if (
            !EnumSet.of(
                InventoryType.CHEST,
                InventoryType.BARREL,
                InventoryType.SHULKER_BOX
            ).contains(inv.getType())
        ) return;
        Location loc = inv.getLocation();
        if (loc != null && inProgress.contains(loc)) return;

        // Skip sort for small containers if configured
        if (shouldSkipSort(inv)) return;

        // Use container extractor to get items
        List<ItemStack> items = containerExtractor.extractItems(inv);
        if (items.isEmpty()) return;

        // Fire event to allow cancellation
        InventorySortEvent sortEvent = new InventorySortEvent(inv, p);
        Bukkit.getPluginManager().callEvent(sortEvent);
        if (sortEvent.isCancelled()) return;

        // Continue with the sorting process
        String signature = inventoryAnalyzer.createInventorySignature(inv);
        String containerKey = containerExtractor.getContainerKey(inv);
        if (containerKey == null) {
            // Fallback if location isn't available
            containerKey = p.getUniqueId().toString();
        }

        if (
            signature.equals(signatures.get(containerKey)) &&
            System.currentTimeMillis() -
            lastSorted.getOrDefault(containerKey, 0L) <
            cooldownTicks * 50L
        ) return;

        signatures.put(containerKey, signature);
        lastSorted.put(containerKey, System.currentTimeMillis());
        if (loc != null) inProgress.add(loc);

        // Record current timestamp for cache version check
        final long sortStartTime = System.currentTimeMillis();

        // Check cache first with version validation
        List<ItemStack> cached = versionedCache.getIfCurrent(
            signature,
            sortStartTime
        );
        if (cached != null) {
            debug.console("[CACHE] Using cached sorting for " + containerKey);
            List<ItemStack> clonedItems = cached
                .stream()
                .map(ItemStack::clone)
                .toList();

            boolean success = containerSortApplier.applySortedItems(
                inv,
                clonedItems,
                p
            );

            if (success) {
                Bukkit.getPluginManager()
                    .callEvent(new SortCompletedEvent(inv, p, clonedItems));
                if (loc != null) inProgress.remove(loc);
            } else {
                Bukkit.getPluginManager()
                    .callEvent(
                        new SortFailedEvent(
                            inv,
                            p,
                            "Failed to apply cached sorting"
                        )
                    );
                if (loc != null) inProgress.remove(loc);
            }
            return;
        }

        tick.start(p);
        callAI(containerKey, signature, sortStartTime, items, inv, p, loc);
    }

    /* ---------- helpers ---------- */

    private boolean shouldSkipSort(Inventory inv) {
        return (
            plugin
                .getConfig()
                .getBoolean("performance.skip_small_containers", false) &&
            inv.getSize() < 9
        );
    }

    private void callAI(
        String containerKey,
        String signature,
        long sortStartTime,
        List<ItemStack> items,
        Inventory inv,
        Player p,
        Location loc
    ) {
        // Use the AIPromptBuilder to generate the prompt
        String prompt = promptBuilder.buildSortingPrompt(items);
        debug.console("[AI prompt] " + prompt);

        // Select model based on inventory size if enabled
        String model = plugin.getConfig().getString("openai.model", "gpt-4o");
        if (plugin.getConfig().getBoolean("openai.dynamic_model", false)) {
            model = ai.selectModel(items.size());
            debug.console(
                "[AI] Using model " + model + " for " + items.size() + " items"
            );
        }

        ai
            .chat(prompt, items.size())
            .thenAcceptAsync(reply ->
                Bukkit.getScheduler()
                    .runTask(plugin, () -> {
                        debug.console("[AI reply]\n" + reply);

                        // Check if inventory changed during sorting
                        if (
                            changeTracker.hasContainerChangedSince(
                                containerKey,
                                sortStartTime
                            )
                        ) {
                            tick.stop(p);
                            p.sendMessage(
                                Component.text(
                                    "Inventory changed during sorting - aborted"
                                ).color(NamedTextColor.YELLOW)
                            );
                            debug.console(
                                "[SORT] Container changed during sorting - aborted"
                            );

                            // Fire sort failed event
                            Bukkit.getPluginManager()
                                .callEvent(
                                    new SortFailedEvent(
                                        inv,
                                        p,
                                        "Inventory changed during sorting"
                                    )
                                );

                            if (loc != null) inProgress.remove(loc);
                            return;
                        }

                        // Parse the AI response using our parser component
                        List<ItemStack> sorted = parseAIResponse(reply, items);

                        if (sorted.isEmpty()) {
                            // Sorting failed - clear feedback
                            tick.stop(p);
                            p.playSound(
                                p.getLocation(),
                                Sound.ENTITY_VILLAGER_NO,
                                0.7f,
                                1.0f
                            );
                            p.sendMessage(
                                Component.text(
                                    "[SmartSort] Sorting failed - items unchanged"
                                ).color(NamedTextColor.RED)
                            );

                            // Fire sort failed event
                            Bukkit.getPluginManager()
                                .callEvent(
                                    new SortFailedEvent(
                                        inv,
                                        p,
                                        "Empty response from AI"
                                    )
                                );

                            if (loc != null) inProgress.remove(loc);
                            return;
                        }

                        // Verify all items are accounted for
                        if (!validateItemCounts(items, sorted)) {
                            tick.stop(p);
                            p.playSound(
                                p.getLocation(),
                                Sound.ENTITY_VILLAGER_NO,
                                0.7f,
                                1.0f
                            );
                            p.sendMessage(
                                Component.text(
                                    "[SmartSort] Item count mismatch - sorting aborted"
                                ).color(NamedTextColor.RED)
                            );
                            debug.console(
                                "[SORT ERROR] Item count mismatch - original vs sorted"
                            );

                            // Fire sort failed event
                            Bukkit.getPluginManager()
                                .callEvent(
                                    new SortFailedEvent(
                                        inv,
                                        p,
                                        "Item count mismatch"
                                    )
                                );

                            if (loc != null) inProgress.remove(loc);
                            return;
                        }

                        // Cache successful sort with version info
                        versionedCache.put(
                            signature,
                            sorted.stream().map(ItemStack::clone).toList(),
                            sortStartTime
                        );

                        // Apply the sorted items using our container applier
                        boolean success = containerSortApplier.applySortedItems(
                            inv,
                            sorted,
                            p
                        );

                        if (success) {
                            // Fire sort completed event
                            Bukkit.getPluginManager()
                                .callEvent(
                                    new SortCompletedEvent(inv, p, sorted)
                                );
                            if (loc != null) inProgress.remove(loc);
                        } else {
                            // Handle failure case
                            Bukkit.getPluginManager()
                                .callEvent(
                                    new SortFailedEvent(
                                        inv,
                                        p,
                                        "Failed to apply sorting"
                                    )
                                );
                            if (loc != null) inProgress.remove(loc);
                        }
                    })
            );
    }

    /**
     * Parse AI response into sorted items
     */
    private List<ItemStack> parseAIResponse(
        String response,
        List<ItemStack> originalItems
    ) {
        // Use the AIResponseParser to parse the response
        return responseParser.parseResponse(response, originalItems);
    }

    // Add validation method to ensure item count consistency
    private boolean validateItemCounts(
        List<ItemStack> original,
        List<ItemStack> sorted
    ) {
        Map<Material, Integer> originalCounts = new HashMap<>();
        Map<Material, Integer> sortedCounts = new HashMap<>();

        // Count original items
        for (ItemStack item : original) {
            if (item != null && item.getType() != Material.AIR) {
                originalCounts.merge(
                    item.getType(),
                    item.getAmount(),
                    Integer::sum
                );
            }
        }

        // Count sorted items
        for (ItemStack item : sorted) {
            if (item != null && item.getType() != Material.AIR) {
                sortedCounts.merge(
                    item.getType(),
                    item.getAmount(),
                    Integer::sum
                );
            }
        }

        // Compare counts for each material
        for (Material material : originalCounts.keySet()) {
            int originalCount = originalCounts.get(material);
            int sortedCount = sortedCounts.getOrDefault(material, 0);

            if (originalCount != sortedCount) {
                debug.console(
                    "[SORT ERROR] Count mismatch for " +
                    material +
                    ": original=" +
                    originalCount +
                    ", sorted=" +
                    sortedCount
                );
                return false;
            }
        }

        return true;
    }
}
