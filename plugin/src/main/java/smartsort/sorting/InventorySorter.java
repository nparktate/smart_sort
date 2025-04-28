package smartsort.sorting;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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
import smartsort.ai.AIPromptBuilder;
import smartsort.ai.AIResponseParser;
import smartsort.ai.AIService;
import smartsort.event.InventorySortEvent;
import smartsort.event.SortCompletedEvent;
import smartsort.event.SortFailedEvent;
import smartsort.util.DebugLogger;
import smartsort.util.TickSoundManager;

public class InventorySorter implements Listener {

    private final SmartSortPlugin plugin;
    private final int cooldownTicks;
    private final Map<String, String> signatures = new HashMap<>();
    private final Map<String, Long> lastSorted = new HashMap<>();
    private final Set<Location> inProgress = new HashSet<>();
    private Cache<String, List<ItemStack>> cache;

    private final AIService ai;
    private final TickSoundManager tick;
    private final DebugLogger debug;
    private final AIPromptBuilder promptBuilder;
    private final AIResponseParser responseParser;
    private final InventoryAnalyzer inventoryAnalyzer;
    private final SortResultApplier resultApplier;

    public InventorySorter(
        SmartSortPlugin pl,
        AIService ai,
        TickSoundManager tick,
        DebugLogger dbg
    ) {
        this.plugin = pl;
        this.ai = ai;
        this.tick = tick;
        this.debug = dbg;
        this.promptBuilder = new AIPromptBuilder();
        this.responseParser = new AIResponseParser();
        this.inventoryAnalyzer = new InventoryAnalyzer();
        this.resultApplier = new SortResultApplier(tick);
        this.cooldownTicks =
            pl.getConfig().getInt("smart_sort.delay_seconds", 3) * 20;
        this.cache = Caffeine.newBuilder()
            .maximumSize(
                plugin.getConfig().getInt("performance.cache_size", 500)
            )
            .build();
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

        List<ItemStack> items = Arrays.stream(inv.getContents())
            .filter(i -> i != null && i.getType() != Material.AIR)
            .toList();
        if (items.isEmpty()) return;

        // Fire event to allow cancellation
        InventorySortEvent sortEvent = new InventorySortEvent(inv, p);
        Bukkit.getPluginManager().callEvent(sortEvent);
        if (sortEvent.isCancelled()) return;

        // Continue with the sorting process
        String signature = inventoryAnalyzer.createInventorySignature(inv);
        String key =
            (loc != null ? getLocationKey(loc) : p.getUniqueId().toString());
        if (
            signature.equals(signatures.get(key)) &&
            System.currentTimeMillis() - lastSorted.getOrDefault(key, 0L) <
            cooldownTicks * 50L
        ) return;

        signatures.put(key, signature);
        lastSorted.put(key, System.currentTimeMillis());
        if (loc != null) inProgress.add(loc);

        // Check cache first
        List<ItemStack> cached = cache.getIfPresent(signature);
        if (cached != null) {
            debug.console("[CACHE] Using cached sorting for " + key);
            List<ItemStack> clonedItems = cached
                .stream()
                .map(ItemStack::clone)
                .toList();

            boolean success = resultApplier.applySortedItems(
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
        callAI(signature, items, inv, p, loc);
    }

    /* ---------- helpers ---------- */

    private String getLocationKey(Location loc) {
        return (
            loc.getWorld().getName() +
            ":" +
            loc.getBlockX() +
            ":" +
            loc.getBlockY() +
            ":" +
            loc.getBlockZ()
        );
    }

    private boolean shouldSkipSort(Inventory inv) {
        return (
            plugin
                .getConfig()
                .getBoolean("performance.skip_small_containers", false) &&
            inv.getSize() < 9
        );
    }

    private void callAI(
        String sig,
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

                        // Use AIResponseParser to parse the reply
                        List<ItemStack> sorted = responseParser.parseResponse(
                            reply,
                            items
                        );

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

                        // Cache successful sort
                        cache.put(
                            sig,
                            sorted.stream().map(ItemStack::clone).toList()
                        );

                        // Apply the sorted items
                        boolean success = resultApplier.applySortedItems(
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
