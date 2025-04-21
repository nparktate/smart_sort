// SmartSortPlugin.java — v1.2.6: Improved command handling and debug system

package smartsort;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import okhttp3.*;
import org.bukkit.*;
import org.bukkit.block.Chest;
import org.bukkit.command.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.json.*;

public class SmartSortPlugin extends JavaPlugin implements Listener {

    private boolean debugMode = false;
    // New setting: should debug messages go to console (true) or player (false)
    private boolean debugToConsole = true;
    private Set<UUID> debugSubscribers = new HashSet<>();

    private String apiKey;
    private String model;
    private int sortCooldown;

    private final Map<String, String> inventorySignatures = new HashMap<>();
    private final Map<String, Long> inventoryTimestamps = new HashMap<>();
    private final Set<Location> recentlySorted = new HashSet<>();
    private final Set<Location> activelySorting = new HashSet<>();
    private final Map<UUID, BukkitTask> tickTasks = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        FileConfiguration config = getConfig();
        apiKey = config.getString("openai.api_key", "");
        model = config.getString("openai.model", "gpt-4o");
        sortCooldown = config.getInt("smart_sort.delay_seconds", 3);
        debugMode = config.getBoolean("debug.enabled", false);
        debugToConsole = config.getBoolean("debug.to_console", true);

        if (apiKey.isEmpty()) {
            getLogger()
                .severe(
                    "OpenAI API key not set in config.yml! Plugin will not work."
                );
            return;
        }

        Bukkit.getPluginManager().registerEvents(this, this);

        // Register commands with better handling
        PluginCommand testCommand = getCommand("testsortchests");
        if (testCommand != null) {
            testCommand.setExecutor(new TestChestCommand());
            testCommand.setTabCompleter(new TestChestTabCompleter());
        }

        PluginCommand smartSortCommand = getCommand("smartsort");
        if (smartSortCommand != null) {
            smartSortCommand.setExecutor(new SmartSortCommand());
            smartSortCommand.setTabCompleter(new SmartSortTabCompleter());
        }

        getLogger().info("SmartSort v1.2.6-SNAPSHOT+013 activated.");
    }

    // Command classes for better organization
    private class TestChestCommand implements CommandExecutor {

        @Override
        public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
        ) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(
                    Component.text(
                        "This command can only be used by players"
                    ).color(NamedTextColor.RED)
                );
                return false;
            }

            if (!player.hasPermission("smartsort.test")) {
                player.sendMessage(
                    Component.text(
                        "You don't have permission to use this command"
                    ).color(NamedTextColor.RED)
                );
                return false;
            }

            logDebug("Generating test chests for " + player.getName());
            generateTestChests(player);
            return true;
        }
    }

    private class TestChestTabCompleter implements TabCompleter {

        @Override
        public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args
        ) {
            return Collections.emptyList(); // No arguments for this command
        }
    }

    private class SmartSortCommand implements CommandExecutor {

        @Override
        public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
        ) {
            if (!sender.hasPermission("smartsort.admin")) {
                sender.sendMessage(
                    Component.text(
                        "You don't have permission to use this command"
                    ).color(NamedTextColor.RED)
                );
                return false;
            }

            if (args.length == 0) {
                sendHelpMessage(sender);
                return true;
            }

            switch (args[0].toLowerCase()) {
                case "debug":
                    toggleDebugMode(sender);
                    return true;
                case "subscribe":
                    if (sender instanceof Player player) {
                        toggleDebugSubscription(player);
                    } else {
                        sender.sendMessage(
                            Component.text(
                                "Only players can subscribe to debug messages"
                            ).color(NamedTextColor.RED)
                        );
                    }
                    return true;
                case "output":
                    if (
                        args.length > 1 &&
                        (args[1].equalsIgnoreCase("console") ||
                            args[1].equalsIgnoreCase("player"))
                    ) {
                        setDebugOutput(
                            sender,
                            args[1].equalsIgnoreCase("console")
                        );
                    } else {
                        sender.sendMessage(
                            Component.text(
                                "Usage: /smartsort output <console|player>"
                            ).color(NamedTextColor.RED)
                        );
                    }
                    return true;
                case "help":
                default:
                    sendHelpMessage(sender);
                    return true;
            }
        }

        private void sendHelpMessage(CommandSender sender) {
            sender.sendMessage(
                Component.text("SmartSort Commands:")
                    .color(NamedTextColor.GOLD)
                    .decorate(TextDecoration.BOLD)
            );
            sender.sendMessage(
                Component.text(" • /smartsort debug")
                    .color(NamedTextColor.YELLOW)
                    .append(
                        Component.text(" - Toggle debug mode on/off").color(
                            NamedTextColor.GRAY
                        )
                    )
            );
            sender.sendMessage(
                Component.text(" • /smartsort subscribe")
                    .color(NamedTextColor.YELLOW)
                    .append(
                        Component.text(" - Subscribe to debug messages").color(
                            NamedTextColor.GRAY
                        )
                    )
            );
            sender.sendMessage(
                Component.text(" • /smartsort output <console|player>")
                    .color(NamedTextColor.YELLOW)
                    .append(
                        Component.text(" - Set debug output destination").color(
                            NamedTextColor.GRAY
                        )
                    )
            );
            sender.sendMessage(
                Component.text(" • /testsortchests")
                    .color(NamedTextColor.YELLOW)
                    .append(
                        Component.text(" - Generate test chests").color(
                            NamedTextColor.GRAY
                        )
                    )
            );
        }

        private void toggleDebugMode(CommandSender sender) {
            debugMode = !debugMode;
            Component message = Component.text("[SmartSort] Debug mode is now ")
                .color(NamedTextColor.YELLOW)
                .append(
                    Component.text(debugMode ? "enabled" : "disabled").color(
                        debugMode ? NamedTextColor.GREEN : NamedTextColor.RED
                    )
                );
            sender.sendMessage(message);

            // Update config
            getConfig().set("debug.enabled", debugMode);
            saveConfig();

            logDebug(
                "Debug mode changed to: " +
                debugMode +
                " by " +
                sender.getName()
            );
        }

        private void toggleDebugSubscription(Player player) {
            UUID playerId = player.getUniqueId();
            if (debugSubscribers.contains(playerId)) {
                debugSubscribers.remove(playerId);
                player.sendMessage(
                    Component.text(
                        "[SmartSort] You are no longer subscribed to debug messages"
                    ).color(NamedTextColor.YELLOW)
                );
            } else {
                debugSubscribers.add(playerId);
                player.sendMessage(
                    Component.text(
                        "[SmartSort] You are now subscribed to debug messages"
                    ).color(NamedTextColor.GREEN)
                );
            }
        }

        private void setDebugOutput(CommandSender sender, boolean toConsole) {
            debugToConsole = toConsole;
            Component message = Component.text(
                "[SmartSort] Debug output will now go to "
            )
                .color(NamedTextColor.YELLOW)
                .append(
                    Component.text(
                        debugToConsole ? "console" : "players"
                    ).color(NamedTextColor.AQUA)
                );
            sender.sendMessage(message);

            // Update config
            getConfig().set("debug.to_console", debugToConsole);
            saveConfig();
        }
    }

    private class SmartSortTabCompleter implements TabCompleter {

        @Override
        public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args
        ) {
            if (args.length == 1) {
                List<String> completions = new ArrayList<>(
                    Arrays.asList("debug", "subscribe", "output", "help")
                );
                return completions
                    .stream()
                    .filter(s ->
                        s.toLowerCase().startsWith(args[0].toLowerCase())
                    )
                    .toList();
            } else if (args.length == 2 && args[0].equalsIgnoreCase("output")) {
                List<String> completions = new ArrayList<>(
                    Arrays.asList("console", "player")
                );
                return completions
                    .stream()
                    .filter(s ->
                        s.toLowerCase().startsWith(args[1].toLowerCase())
                    )
                    .toList();
            }
            return Collections.emptyList();
        }
    }

    // Centralized debug logging method
    private void logDebug(String message) {
        if (!debugMode) return;

        // Always log to console at fine level for server logs
        getLogger().fine("[DEBUG] " + message);

        // If debug is set to console and it's a significant message, log at info level
        if (debugToConsole) {
            getLogger().info("[SmartSort] [DEBUG] " + message);
        }

        // If debug should go to players, send to all subscribers
        if (!debugToConsole || !debugSubscribers.isEmpty()) {
            Component debugComponent = Component.text("[SmartSort] [DEBUG] ")
                .color(NamedTextColor.DARK_AQUA)
                .append(Component.text(message).color(NamedTextColor.WHITE));

            // Send to all subscribed players
            for (UUID playerId : debugSubscribers) {
                Player player = Bukkit.getPlayer(playerId);
                if (player != null && player.isOnline()) {
                    player.sendMessage(debugComponent);
                }
            }
        }
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        long sinceJoin = System.currentTimeMillis() - player.getFirstPlayed();

        logDebug(
            "Inventory open triggered by " +
            player.getName() +
            ", time since join: " +
            sinceJoin +
            "ms"
        );
        if (sinceJoin < 3000) return;

        Inventory inv = event.getInventory();
        Location loc = inv.getLocation();
        if (loc == null) return;
        if (
            activelySorting.contains(loc) || recentlySorted.contains(loc)
        ) return;
        sortInventory(inv, event.getPlayer());
    }

    private void sortInventory(Inventory inventory, HumanEntity player) {
        if (
            inventory.getType() != InventoryType.CHEST &&
            inventory.getType() != InventoryType.BARREL &&
            inventory.getType() != InventoryType.SHULKER_BOX
        ) return;

        List<ItemStack> originalItems = Arrays.stream(inventory.getContents())
            .filter(item -> item != null && item.getType() != Material.AIR)
            .map(ItemStack::clone)
            .toList();

        if (originalItems.isEmpty()) return;

        Location loc = inventory.getLocation();
        String locKey = loc != null
            ? loc.toString()
            : player.getUniqueId().toString();
        long now = System.currentTimeMillis();

        Map<String, Integer> counts = new HashMap<>();
        Map<String, Queue<ItemStack>> itemMap = new HashMap<>();
        for (ItemStack item : originalItems) {
            String key = item.getType().name();
            counts.put(key, counts.getOrDefault(key, 0) + item.getAmount());
            itemMap
                .computeIfAbsent(key, k -> new LinkedList<>())
                .add(item.clone());
        }

        List<String> itemDescriptions = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            itemDescriptions.add(entry.getValue() + " x " + entry.getKey());
        }

        String signature = String.join(",", itemDescriptions);
        if (
            inventorySignatures.containsKey(locKey) &&
            inventorySignatures.get(locKey).equals(signature)
        ) {
            long last = inventoryTimestamps.getOrDefault(locKey, 0L);
            if (now - last < sortCooldown * 1000L) return;
        }

        if (loc != null) activelySorting.add(loc);
        inventorySignatures.put(locKey, signature);
        inventoryTimestamps.put(locKey, now);

        if (player instanceof Player p) {
            startTickSound(p);

            String prompt =
                "You are an AI assistant that sorts Minecraft inventories. Make deliberate choices. " +
                "ONLY reply with a clean list in this format: 'amount x ITEM', one per line. " +
                "Input: " +
                String.join(", ", itemDescriptions);

            logDebug("AI Prompt: " + prompt);

            sendToOpenAI(prompt).thenAccept(response -> {
                if (response.trim().isEmpty()) {
                    getLogger()
                        .warning(
                            "[SmartSort] ⚠️ AI response was empty — skipping sort for: " +
                            loc
                        );
                    if (loc != null) activelySorting.remove(loc);
                    return;
                }

                logDebug("AI Response:\n" + response);

                List<ItemStack> sortedItems = new ArrayList<>();
                List<ItemStack> unmatched = new ArrayList<>();

                for (String line : response.split("\n")) {
                    String key = line.trim();
                    if (!key.matches("\\d+ x [A-Z0-9_]+")) continue;

                    String[] parts = key.split(" x ", 2);
                    int amt;
                    Material mat;
                    try {
                        amt = Integer.parseInt(parts[0]);
                        mat = Material.matchMaterial(parts[1]);
                    } catch (Exception e) {
                        continue;
                    }
                    if (mat == null) continue;

                    Queue<ItemStack> stackList = itemMap.get(mat.name());
                    while (
                        amt > 0 && stackList != null && !stackList.isEmpty()
                    ) {
                        ItemStack is = stackList.poll();
                        if (is != null) {
                            int take = Math.min(is.getAmount(), amt);
                            ItemStack piece = is.clone();
                            piece.setAmount(take);
                            sortedItems.add(piece);
                            amt -= take;
                            if (is.getAmount() > take) {
                                is.setAmount(is.getAmount() - take);
                                stackList.add(is);
                            }
                        }
                    }
                }

                for (Queue<ItemStack> leftovers : itemMap.values()) {
                    while (!leftovers.isEmpty()) unmatched.add(
                        leftovers.poll()
                    );
                }

                List<ItemStack> currentContents = Arrays.stream(
                    inventory.getContents()
                )
                    .filter(
                        item -> item != null && item.getType() != Material.AIR
                    )
                    .toList();

                List<ItemStack> finalItems = new ArrayList<>();
                Map<String, Integer> liveCounts = new HashMap<>();
                for (ItemStack item : currentContents) {
                    String key = item.getType().name();
                    liveCounts.put(
                        key,
                        liveCounts.getOrDefault(key, 0) + item.getAmount()
                    );
                }

                for (ItemStack item : sortedItems) {
                    String key = item.getType().name();
                    int liveAmt = liveCounts.getOrDefault(key, 0);
                    if (liveAmt <= 0) continue;
                    int amt = item.getAmount();
                    int kept = Math.min(amt, liveAmt);
                    item.setAmount(kept);
                    finalItems.add(item);
                    liveCounts.put(key, liveAmt - kept);
                }

                finalItems.addAll(unmatched);

                for (ItemStack item : currentContents) {
                    boolean isNew = true;
                    for (ItemStack orig : originalItems) {
                        if (
                            item.isSimilar(orig) &&
                            item.getAmount() == orig.getAmount()
                        ) {
                            isNew = false;
                            break;
                        }
                    }
                    if (isNew) finalItems.add(item);
                }

                Bukkit.getScheduler()
                    .runTask(this, () -> {
                        inventory.clear();
                        for (int i = 0; i < finalItems.size(); i++) {
                            inventory.setItem(i, finalItems.get(i));
                        }

                        stopTickSound(p);
                        p.playSound(
                            p.getLocation(),
                            Sound.ENTITY_EXPERIENCE_ORB_PICKUP,
                            0.7f,
                            1.1f
                        );

                        if (loc != null) {
                            activelySorting.remove(loc);
                            recentlySorted.add(loc);
                            Bukkit.getScheduler()
                                .runTaskLater(
                                    this,
                                    () -> recentlySorted.remove(loc),
                                    80L
                                );
                        }

                        logDebug("Sorted: " + loc);
                    });
            });
        }
    }

    private void startTickSound(Player player) {
        BukkitTask task = Bukkit.getScheduler()
            .runTaskTimer(
                this,
                () ->
                    player.playSound(
                        player.getLocation(),
                        Sound.UI_BUTTON_CLICK,
                        0.3f,
                        1.5f
                    ),
                0L,
                8L
            );
        tickTasks.put(player.getUniqueId(), task);
    }

    private void stopTickSound(Player player) {
        BukkitTask task = tickTasks.remove(player.getUniqueId());
        if (task != null) task.cancel();
    }

    private CompletableFuture<String> sendToOpenAI(String prompt) {
        CompletableFuture<String> future = new CompletableFuture<>();

        OkHttpClient client = new OkHttpClient();
        JSONObject json = new JSONObject()
            .put("model", model)
            .put(
                "messages",
                List.of(
                    new JSONObject().put("role", "user").put("content", prompt)
                )
            )
            .put("temperature", 0.3);

        RequestBody body = RequestBody.create(
            json.toString(),
            MediaType.parse("application/json")
        );
        Request request = new Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .post(body)
            .addHeader("Authorization", "Bearer " + apiKey)
            .build();

        client
            .newCall(request)
            .enqueue(
                new Callback() {
                    public void onFailure(Call call, IOException e) {
                        logDebug("API call failed: " + e.getMessage());
                        future.complete("");
                    }

                    public void onResponse(Call call, Response response)
                        throws IOException {
                        String jsonResponse = response.body().string();
                        try {
                            JSONObject res = new JSONObject(jsonResponse);
                            String content = res
                                .getJSONArray("choices")
                                .getJSONObject(0)
                                .getJSONObject("message")
                                .getString("content");
                            future.complete(content);
                        } catch (Exception e) {
                            logDebug(
                                "Failed to parse OpenAI response: " +
                                e.getMessage() +
                                "\nResponse: " +
                                jsonResponse
                            );
                            future.complete("");
                        }
                    }
                }
            );

        return future;
    }

    private Map<String, List<ItemStack>> getThemeFallbacks() {
        Map<String, List<ItemStack>> fallbacks = new HashMap<>();

        // Combat gear
        List<ItemStack> combat = new ArrayList<>();
        combat.add(new ItemStack(Material.IRON_SWORD));
        combat.add(new ItemStack(Material.BOW));
        combat.add(new ItemStack(Material.ARROW, 32));
        combat.add(new ItemStack(Material.SHIELD));
        combat.add(new ItemStack(Material.IRON_HELMET));
        fallbacks.put("combat gear", combat);

        // Miner haul
        List<ItemStack> mining = new ArrayList<>();
        mining.add(new ItemStack(Material.IRON_PICKAXE));
        mining.add(new ItemStack(Material.COAL, 16));
        mining.add(new ItemStack(Material.IRON_ORE, 8));
        mining.add(new ItemStack(Material.GOLD_ORE, 4));
        mining.add(new ItemStack(Material.DIAMOND, 2));
        fallbacks.put("miner haul", mining);

        // Farming loot
        List<ItemStack> farming = new ArrayList<>();
        farming.add(new ItemStack(Material.WHEAT, 24));
        farming.add(new ItemStack(Material.CARROT, 16));
        farming.add(new ItemStack(Material.POTATO, 16));
        farming.add(new ItemStack(Material.BEETROOT_SEEDS, 8));
        farming.add(new ItemStack(Material.BONE_MEAL, 12));
        fallbacks.put("farming loot", farming);

        // Base supplies
        List<ItemStack> baseSupplies = new ArrayList<>();
        baseSupplies.add(new ItemStack(Material.OAK_LOG, 32));
        baseSupplies.add(new ItemStack(Material.COBBLESTONE, 64));
        baseSupplies.add(new ItemStack(Material.TORCH, 16));
        baseSupplies.add(new ItemStack(Material.CRAFTING_TABLE));
        baseSupplies.add(new ItemStack(Material.FURNACE));
        fallbacks.put("base supplies", baseSupplies);

        // Loot chest
        List<ItemStack> loot = new ArrayList<>();
        loot.add(new ItemStack(Material.GOLD_INGOT, 6));
        loot.add(new ItemStack(Material.IRON_INGOT, 12));
        loot.add(new ItemStack(Material.DIAMOND, 3));
        loot.add(new ItemStack(Material.EMERALD, 4));
        loot.add(new ItemStack(Material.GOLDEN_APPLE, 2));
        fallbacks.put("loot chest", loot);

        // Tool stash
        List<ItemStack> tools = new ArrayList<>();
        tools.add(new ItemStack(Material.IRON_PICKAXE));
        tools.add(new ItemStack(Material.IRON_AXE));
        tools.add(new ItemStack(Material.IRON_SHOVEL));
        tools.add(new ItemStack(Material.SHEARS));
        tools.add(new ItemStack(Material.FISHING_ROD));
        fallbacks.put("tool stash", tools);

        // Potion chest
        List<ItemStack> potions = new ArrayList<>();
        potions.add(new ItemStack(Material.GLASS_BOTTLE, 8));
        potions.add(new ItemStack(Material.NETHER_WART, 12));
        potions.add(new ItemStack(Material.BLAZE_POWDER, 6));
        potions.add(new ItemStack(Material.SPIDER_EYE, 4));
        potions.add(new ItemStack(Material.GLISTERING_MELON_SLICE, 3));
        fallbacks.put("potion chest", potions);

        // Building blocks
        List<ItemStack> blocks = new ArrayList<>();
        blocks.add(new ItemStack(Material.OAK_PLANKS, 64));
        blocks.add(new ItemStack(Material.STONE_BRICKS, 48));
        blocks.add(new ItemStack(Material.GLASS, 24));
        blocks.add(new ItemStack(Material.LANTERN, 8));
        blocks.add(new ItemStack(Material.BOOKSHELF, 6));
        fallbacks.put("building blocks", blocks);

        // Exploration kit
        List<ItemStack> exploration = new ArrayList<>();
        exploration.add(new ItemStack(Material.COMPASS));
        exploration.add(new ItemStack(Material.MAP));
        exploration.add(new ItemStack(Material.BREAD, 12));
        exploration.add(new ItemStack(Material.TORCH, 24));
        exploration.add(new ItemStack(Material.OAK_BOAT));
        fallbacks.put("exploration kit", exploration);

        return fallbacks;
    }

    private void generateTestChests(Player player) {
        Location base = player.getLocation().add(2, 0, 0);
        Random random = new Random();

        String[] themes = {
            "combat gear",
            "miner haul",
            "farming loot",
            "base supplies",
            "loot chest",
            "tool stash",
            "potion chest",
            "building blocks",
            "exploration kit",
        };

        // Prepare fallback items map
        Map<String, List<ItemStack>> fallbackItems = getThemeFallbacks();

        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                String theme = themes[random.nextInt(themes.length)];
                Location chestLoc = base.clone().add(x * 2, 0, z * 2);
                chestLoc.getBlock().setType(Material.CHEST);
                Chest chest = (Chest) chestLoc.getBlock().getState();
                Inventory inv = chest.getInventory();

                String prompt =
                    "Generate a Minecraft chest inventory with a theme: '" +
                    theme +
                    "'. " +
                    "ONLY output in 'amount x ITEM' format, one per line.";

                sendToOpenAI(prompt).thenAccept(response ->
                    Bukkit.getScheduler()
                        .runTask(this, () -> {
                            List<ItemStack> stacks = new ArrayList<>();
                            for (String line : response.split("\n")) {
                                String[] parts = line.trim().split(" x ", 2);
                                if (parts.length != 2) continue;
                                try {
                                    int amt = Integer.parseInt(parts[0]);
                                    Material mat = Material.matchMaterial(
                                        parts[1]
                                    );
                                    if (
                                        mat != null &&
                                        mat.isItem() &&
                                        !mat.isAir() &&
                                        !mat.name().contains("COMMAND")
                                    ) {
                                        stacks.add(
                                            new ItemStack(
                                                mat,
                                                Math.min(
                                                    amt,
                                                    mat.getMaxStackSize()
                                                )
                                            )
                                        );
                                    }
                                } catch (Exception ignored) {}
                            }

                            // Use fallback items if AI response didn't yield usable items
                            if (
                                stacks.isEmpty() &&
                                fallbackItems.containsKey(theme)
                            ) {
                                stacks.addAll(fallbackItems.get(theme));
                                getLogger()
                                    .info(
                                        "Using fallback items for theme: " +
                                        theme
                                    );
                            } else if (stacks.isEmpty()) {
                                // Generic fallback if no theme-specific items
                                stacks.add(new ItemStack(Material.STONE, 32));
                                stacks.add(new ItemStack(Material.BREAD, 16));
                                stacks.add(
                                    new ItemStack(Material.IRON_INGOT, 8)
                                );
                                getLogger()
                                    .warning(
                                        "AI response produced no valid items. Using generic fallbacks."
                                    );
                            }

                            // Shuffle items for random placement
                            Collections.shuffle(stacks);
                            Set<Integer> usedSlots = new HashSet<>();

                            // Place items in inventory
                            for (ItemStack stack : stacks) {
                                if (usedSlots.size() >= inv.getSize()) {
                                    // All slots used, replace a random one
                                    inv.setItem(
                                        random.nextInt(inv.getSize()),
                                        stack
                                    );
                                } else {
                                    // Find an unused slot
                                    int slot;
                                    do {
                                        slot = random.nextInt(inv.getSize());
                                    } while (usedSlots.contains(slot));

                                    inv.setItem(slot, stack);
                                    usedSlots.add(slot);
                                }
                            }

                            // Ensure minimum number of items
                            if (usedSlots.size() < 5) {
                                Material[] basics = {
                                    Material.STONE,
                                    Material.OAK_LOG,
                                    Material.BREAD,
                                    Material.COAL,
                                    Material.IRON_INGOT,
                                };

                                for (int i = 0; i < 5 - usedSlots.size(); i++) {
                                    int slot;
                                    do {
                                        slot = random.nextInt(inv.getSize());
                                    } while (usedSlots.contains(slot));

                                    Material mat =
                                        basics[random.nextInt(basics.length)];
                                    inv.setItem(
                                        slot,
                                        new ItemStack(
                                            mat,
                                            4 + random.nextInt(12)
                                        )
                                    );
                                    usedSlots.add(slot);
                                }
                            }

                            // Notify player
                            player.sendMessage(
                                Component.text(
                                    "Created test chest with theme: " + theme
                                ).color(NamedTextColor.GREEN)
                            );
                        })
                );
            }
        }
    }
}
