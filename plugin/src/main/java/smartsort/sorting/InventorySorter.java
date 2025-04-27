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
import smartsort.ai.AIService;
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

        String signature = signature(items);
        String key =
            (loc != null ? loc.toString() : p.getUniqueId().toString());
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
            applySorting(
                cached.stream().map(ItemStack::clone).toList(),
                inv,
                p,
                loc
            );
            return;
        }

        tick.start(p);
        callAI(signature, items, inv, p, loc);
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

    private String signature(List<ItemStack> items) {
        // Optimized signature generation
        Map<Material, Integer> map = new HashMap<>();
        items.forEach(i -> map.merge(i.getType(), i.getAmount(), Integer::sum));

        StringBuilder sb = new StringBuilder();
        map
            .entrySet()
            .stream()
            .sorted(Comparator.comparing(e -> e.getKey().name())) // More stable sorting
            .forEach(e ->
                sb
                    .append(e.getValue())
                    .append("x")
                    .append(e.getKey())
                    .append(",")
            );

        return sb.length() > 0 ? sb.substring(0, sb.length() - 1) : "";
    }

    private void callAI(
        String sig,
        List<ItemStack> items,
        Inventory inv,
        Player p,
        Location loc
    ) {
        // Select model based on inventory size if enabled
        String model = plugin.getConfig().getString("openai.model", "gpt-4o");
        if (plugin.getConfig().getBoolean("openai.dynamic_model", false)) {
            model = ai.selectModel(items.size());
            debug.console(
                "[AI] Using model " + model + " for " + items.size() + " items"
            );
        }

        // More efficient prompt for better sorting
        String prompt =
            "[SMARTSORT v4] Inventory: " +
            sig.replace(",", ", ") +
            "\n" +
            "RULES:\n" +
            "1. You're a Minecraft expert organizing inventory in the most intuitive way possible\n" +
            "2. Group similar items together (blocks with blocks, tools with tools)\n" +
            "3. Put most commonly used items at the top/beginning of inventory\n" +
            "4. Stack to maximum amounts first\n" +
            "5. For tools and weapons, sort by material quality (wood→stone→iron→gold→diamond)\n" +
            "6. Output ONLY lines like \"12xSTONE\" with no comments or explanations\n" +
            "7. Consider what an experienced Minecraft player would expect";

        debug.console("[AI prompt] " + prompt);
        ai
            .chat(prompt, items.size())
            .thenAcceptAsync(reply ->
                Bukkit.getScheduler()
                    .runTask(plugin, () -> {
                        debug.console("[AI reply]\n" + reply);

                        List<ItemStack> sorted = parseReply(reply, items);

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

                            if (loc != null) inProgress.remove(loc);
                            return;
                        }

                        // Cache successful sort
                        cache.put(
                            sig,
                            sorted.stream().map(ItemStack::clone).toList()
                        );

                        // Apply the sorted items
                        applySorting(sorted, inv, p, loc);
                    })
            );
    }

    private void applySorting(
        List<ItemStack> sorted,
        Inventory inv,
        Player p,
        Location loc
    ) {
        // Clear and apply sorted items
        inv.clear();

        // Apply sorted items with stacking
        Map<String, ItemStack> stackMap = new LinkedHashMap<>();

        for (ItemStack item : sorted) {
            if (item == null || item.getType() == Material.AIR) continue;

            // Create key based on material and metadata
            String key = item.getType().toString();
            if (item.hasItemMeta()) key += ":" + item.getItemMeta().hashCode();

            if (stackMap.containsKey(key)) {
                ItemStack existing = stackMap.get(key);
                int maxSize = existing.getType().getMaxStackSize();

                if (existing.getAmount() < maxSize) {
                    int canAdd = maxSize - existing.getAmount();
                    int toAdd = Math.min(canAdd, item.getAmount());

                    existing.setAmount(existing.getAmount() + toAdd);

                    if (toAdd < item.getAmount()) {
                        ItemStack remainder = item.clone();
                        remainder.setAmount(item.getAmount() - toAdd);
                        // Create a new unique key for the remainder
                        stackMap.put(key + ":" + UUID.randomUUID(), remainder);
                    }
                } else {
                    // Stack is full, create new one with unique key
                    stackMap.put(key + ":" + UUID.randomUUID(), item);
                }
            } else {
                stackMap.put(key, item);
            }
        }

        // Place stacked items in inventory
        int slot = 0;
        for (ItemStack item : stackMap.values()) {
            if (slot < inv.getSize()) {
                inv.setItem(slot++, item);
            }
        }

        // Success feedback
        tick.stop(p);
        p.playSound(
            p.getLocation(),
            Sound.ENTITY_EXPERIENCE_ORB_PICKUP,
            0.7f,
            1.1f
        );
        if (loc != null) inProgress.remove(loc);
    }

    private List<ItemStack> parseReply(String reply, List<ItemStack> items) {
        Map<Material, Queue<ItemStack>> stash = new HashMap<>();
        items.forEach(it ->
            stash
                .computeIfAbsent(it.getType(), k -> new LinkedList<>())
                .add(it.clone())
        );

        List<ItemStack> result = new ArrayList<>();
        for (String line : reply.split("\n")) {
            line = line.trim().toUpperCase();
            // Updated regex to support both "12xSTONE" and "12 x STONE" formats with case-insensitive x
            if (!line.matches("(?i)\\d+\\s*[xX]\\s*[A-Z0-9_]+")) continue;

            // Split on x with optional whitespace, case-insensitive
            String[] parts = line.split("(?i)\\s*[xX]\\s*");

            int amt = Integer.parseInt(parts[0]);
            Material m = Material.matchMaterial(parts[1]);
            Queue<ItemStack> q = m == null ? null : stash.get(m);
            while (amt > 0 && q != null && !q.isEmpty()) {
                ItemStack is = q.poll();
                int take = Math.min(amt, is.getAmount());
                ItemStack slice = is.clone();
                slice.setAmount(take);
                result.add(slice);
                amt -= take;
                if (is.getAmount() > take) {
                    is.setAmount(is.getAmount() - take);
                    q.add(is);
                }
            }
        }
        // leftovers
        stash.values().forEach(q -> q.forEach(result::add));
        return result;
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
