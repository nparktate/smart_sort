// SmartSortPlugin.java — v1.2.3: Chest debounce improvements + messy test chest slot fill

package smartsort;

import org.bukkit.*;
import org.bukkit.block.Chest;
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

import okhttp3.*;
import org.json.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

public class SmartSortPlugin extends JavaPlugin implements Listener {

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

        if (apiKey.isEmpty()) {
            getLogger().severe("OpenAI API key not set in config.yml! Plugin will not work.");
            return;
        }

        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("testsortchests").setExecutor((sender, command, label, args) -> {
            if (sender instanceof Player player) generateTestChests(player);
            return true;
        });

        getLogger().info("SmartSort v1.2.3 activated.");
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        Inventory inv = event.getInventory();
        Location loc = inv.getLocation();
        if (loc == null) return;
        if (activelySorting.contains(loc) || recentlySorted.contains(loc)) return;
        sortInventory(inv, event.getPlayer());
    }

    private void sortInventory(Inventory inventory, HumanEntity player) {
        if (inventory.getType() != InventoryType.CHEST &&
            inventory.getType() != InventoryType.BARREL &&
            inventory.getType() != InventoryType.SHULKER_BOX) return;

        List<ItemStack> originalItems = Arrays.stream(inventory.getContents())
                .filter(item -> item != null && item.getType() != Material.AIR)
                .map(ItemStack::clone)
                .toList();

        if (originalItems.isEmpty()) return;

        Location loc = inventory.getLocation();
        String locKey = loc != null ? loc.toString() : player.getUniqueId().toString();
        long now = System.currentTimeMillis();

        Map<String, Integer> counts = new HashMap<>();
        Map<String, Queue<ItemStack>> itemMap = new HashMap<>();
        for (ItemStack item : originalItems) {
            String key = item.getType().name();
            counts.put(key, counts.getOrDefault(key, 0) + item.getAmount());
            itemMap.computeIfAbsent(key, k -> new LinkedList<>()).add(item.clone());
        }

        List<String> itemDescriptions = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            itemDescriptions.add(entry.getValue() + " x " + entry.getKey());
        }

        String signature = String.join(",", itemDescriptions);
        if (inventorySignatures.containsKey(locKey) && inventorySignatures.get(locKey).equals(signature)) {
            long last = inventoryTimestamps.getOrDefault(locKey, 0L);
            if (now - last < sortCooldown * 1000L) return;
        }

        activelySorting.add(loc);
        inventorySignatures.put(locKey, signature);
        inventoryTimestamps.put(locKey, now);

        if (player instanceof Player p) {
            startTickSound(p);

            String prompt = "You are an AI assistant that sorts Minecraft inventories. Make deliberate choices. " +
                    "ONLY reply with a clean list in this format: 'amount x ITEM', one per line. " +
                    "Input: " + String.join(", ", itemDescriptions);

            getLogger().info("[SmartSort] AI Prompt: " + prompt);

            sendToOpenAI(prompt).thenAccept(response -> {
                getLogger().info("[SmartSort] AI Response:\n" + response);

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
                    while (amt > 0 && stackList != null && !stackList.isEmpty()) {
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
                    while (!leftovers.isEmpty()) unmatched.add(leftovers.poll());
                }

                List<ItemStack> currentContents = Arrays.stream(inventory.getContents())
                        .filter(item -> item != null && item.getType() != Material.AIR)
                        .toList();

                List<ItemStack> finalItems = new ArrayList<>();
                Map<String, Integer> liveCounts = new HashMap<>();
                for (ItemStack item : currentContents) {
                    String key = item.getType().name();
                    liveCounts.put(key, liveCounts.getOrDefault(key, 0) + item.getAmount());
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
                        if (item.isSimilar(orig) && item.getAmount() == orig.getAmount()) {
                            isNew = false;
                            break;
                        }
                    }
                    if (isNew) finalItems.add(item);
                }

                Bukkit.getScheduler().runTask(this, () -> {
                    inventory.clear();
                    for (int i = 0; i < finalItems.size(); i++) {
                        inventory.setItem(i, finalItems.get(i));
                    }

                    stopTickSound(p);
                    p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.1f);

                    if (loc != null) {
                        activelySorting.remove(loc);
                        recentlySorted.add(loc);
                        Bukkit.getScheduler().runTaskLater(this, () -> recentlySorted.remove(loc), 80L);
                    }
                    getLogger().info("[SmartSort] Sorted: " + loc);
                });
            });
        }
    }

    private void startTickSound(Player player) {
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(this, () ->
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.3f, 1.5f), 0L, 8L);
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
                .put("messages", List.of(new JSONObject().put("role", "user").put("content", prompt)))
                .put("temperature", 0.3);

        RequestBody body = RequestBody.create(json.toString(), MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url("https://api.openai.com/v1/chat/completions")
                .post(body)
                .addHeader("Authorization", "Bearer " + apiKey)
                .build();

        client.newCall(request).enqueue(new Callback() {
            public void onFailure(Call call, IOException e) {
                future.complete("");
            }

            public void onResponse(Call call, Response response) throws IOException {
                String jsonResponse = response.body().string();
                JSONObject res = new JSONObject(jsonResponse);
                String content = res.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
                future.complete(content);
            }
        });

        return future;
    }

    private void generateTestChests(Player player) {
        Location base = player.getLocation().add(2, 0, 0);
        Random random = new Random();

        String[] themes = {"combat gear", "miner haul", "farming loot", "base supplies", "loot chest", "tool stash", "potion chest", "building blocks", "exploration kit"};

        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                String theme = themes[random.nextInt(themes.length)];
                Location chestLoc = base.clone().add(x * 2, 0, z * 2);
                chestLoc.getBlock().setType(Material.CHEST);
                Chest chest = (Chest) chestLoc.getBlock().getState();
                Inventory inv = chest.getInventory();

                String prompt = "Generate a Minecraft chest inventory with a theme: '" + theme + "'. " +
                        "ONLY output in 'amount x ITEM' format, one per line.";

                sendToOpenAI(prompt).thenAccept(response -> Bukkit.getScheduler().runTask(this, () -> {
                    List<ItemStack> stacks = new ArrayList<>();
                    for (String line : response.split("\n")) {
                        String[] parts = line.trim().split(" x ", 2);
                        if (parts.length != 2) continue;
                        try {
                            int amt = Integer.parseInt(parts[0]);
                            Material mat = Material.matchMaterial(parts[1]);
                            if (mat != null && mat.isItem()) {
                                stacks.add(new ItemStack(mat, Math.min(amt, mat.getMaxStackSize())));
                            }
                        } catch (Exception ignored) {}
                    }
                    Collections.shuffle(stacks);
                    Set<Integer> usedSlots = new HashSet<>();
                    for (ItemStack stack : stacks) {
                        int slot;
                        int tries = 0;
                        do {
                            slot = random.nextInt(inv.getSize());
                            tries++;
                        } while (usedSlots.contains(slot) && tries < 10);
                        if (!usedSlots.contains(slot)) {
                            inv.setItem(slot, stack);
                            usedSlots.add(slot);
                        }
                    }
                }));
            }
        }
    }
}
