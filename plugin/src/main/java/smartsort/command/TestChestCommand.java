package smartsort.command;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import smartsort.SmartSortPlugin;
import smartsort.ai.AIService;
import smartsort.util.DebugLogger;

public class TestChestCommand {

    private final AIService ai;
    private final SmartSortPlugin plugin;
    private final DebugLogger debug;

    public static final List<String> STARTER_THEMES = List.of(
        "Magic Chest",
        "Ruined Temple",
        "Ancient Jungle",
        "Abandoned Mine",
        "Sky Castle",
        "Frozen Tundra",
        "Desert Tomb",
        "Nether Fortress",
        "End-City Relic",
        "Underwater Ruins",
        "Village Blacksmith",
        "Enchanter's Study"
    );

    public TestChestCommand(
        SmartSortPlugin plugin,
        AIService ai,
        DebugLogger dbg
    ) {
        this.plugin = plugin;
        this.ai = ai;
        this.debug = dbg;
    }

    public boolean handleTestCommand(Player p, String theme) {
        debug.console(
            "[TestChest] Player " +
            p.getName() +
            " generating chests with theme: " +
            theme
        );
        generate(p, theme);
        return true;
    }

    private void generate(Player p, String requestedTheme) {
        boolean isRandom = requestedTheme.equalsIgnoreCase("random");
        if (isRandom) {
            requestedTheme = STARTER_THEMES.get(
                ThreadLocalRandom.current().nextInt(STARTER_THEMES.size())
            );
            debug.console(
                "[TestChest] Random theme selected: " + requestedTheme
            );
        }

        Location base = p.getLocation().add(2, 0, 0);
        debug.console(
            "[TestChest] Generating chests at " +
            base.getBlockX() +
            "," +
            base.getBlockY() +
            "," +
            base.getBlockZ()
        );

        int maxChests = 4;

        p.sendMessage(
            Component.text(
                "Creating " +
                maxChests +
                " test chests with theme: " +
                requestedTheme
            ).color(NamedTextColor.GREEN)
        );

        for (int i = 0; i < maxChests; i++) {
            final int index = i;
            final int x = index % 2;
            final int z = index / 2;
            final String theme = requestedTheme;

            plugin
                .getServer()
                .getScheduler()
                .runTaskLater(
                    plugin,
                    () -> {
                        createSingleChest(p, base, x, z, theme, index);
                    },
                    i * 60L
                );
        }
    }

    private void createSingleChest(
        Player p,
        Location base,
        int x,
        int z,
        String requestedTheme,
        int chestNum
    ) {
        Location loc = base.clone().add(x * 2, 0, z * 2);
        loc.getBlock().setType(Material.CHEST);
        Inventory inv = ((Chest) loc.getBlock().getState()).getInventory();

        debug.console(
            "[TestChest] Created chest #" +
            (chestNum + 1) +
            " at " +
            loc.getBlockX() +
            "," +
            loc.getBlockY() +
            "," +
            loc.getBlockZ()
        );

        String prompt =
            "Create a realistic Minecraft chest inventory for theme '" +
            requestedTheme +
            "'.\n" +
            "Rules:\n" +
            "1. Include 12-16 different items with appropriate quantities\n" +
            "2. Use ONLY valid Minecraft 1.21 material names (like DIAMOND, OAK_LOG)\n" +
            "3. Format EXACTLY as '12xITEM_NAME' with one item per line (no spaces around x)\n" +
            "4. Include both common and valuable items\n" +
            "5. No commentary or explanations";

        debug.console(
            "[TestChest] Sending prompt to AI for chest #" + (chestNum + 1)
        );

        ai
            .chat(prompt)
            .thenAccept(reply ->
                Bukkit.getScheduler()
                    .runTask(plugin, () -> {
                        debug.console(
                            "[TestChest] Received AI reply: " +
                            reply.length() +
                            " chars"
                        );

                        List<ItemStack> stacks = parseReply(reply);

                        if (stacks.isEmpty()) {
                            debug.console(
                                "[TestChest] AI generated no valid items for " +
                                requestedTheme
                            );
                            p.sendMessage(
                                Component.text(
                                    "AI failed to generate items"
                                ).color(NamedTextColor.RED)
                            );
                            p.playSound(
                                p.getLocation(),
                                Sound.ENTITY_VILLAGER_NO,
                                1.0f,
                                1.0f
                            );
                            return;
                        }

                        debug.console(
                            "[TestChest] Populating chest with " +
                            stacks.size() +
                            " items"
                        );

                        Random rnd = new Random();
                        for (ItemStack stack : stacks) {
                            List<Integer> emptySlots = new ArrayList<>();
                            for (int i = 0; i < inv.getSize(); i++) {
                                if (
                                    inv.getItem(i) == null ||
                                    inv.getItem(i).getType() == Material.AIR
                                ) {
                                    emptySlots.add(i);
                                }
                            }

                            if (emptySlots.isEmpty()) {
                                inv.addItem(stack);
                            } else {
                                int slot = emptySlots.get(
                                    rnd.nextInt(emptySlots.size())
                                );
                                inv.setItem(slot, stack);
                            }
                        }

                        debug.console(
                            "[TestChest] Chest creation complete: " +
                            requestedTheme
                        );
                        p.sendMessage(
                            Component.text(
                                "Created chest: " + requestedTheme
                            ).color(NamedTextColor.GREEN)
                        );
                    })
            );
    }

    private List<ItemStack> parseReply(String reply) {
        debug.console(
            "[TestChest] Parsing AI reply with " +
            reply.split("\n").length +
            " lines"
        );
        List<ItemStack> out = new ArrayList<>();
        for (String line : reply.split("\n")) {
            line = line.trim().toUpperCase();
            if (!line.matches("(?i)\\d+\\s*[xX]\\s*[A-Z0-9_]+")) {
                debug.console(
                    "[TestChest] Skipping invalid line format: " + line
                );
                continue;
            }
            String[] parts = line.split("(?i)\\s*[xX]\\s*");
            int amt = Integer.parseInt(parts[0]);
            Material m = Material.matchMaterial(parts[1]);
            if (m == null || m.isAir()) {
                debug.console("[TestChest] Invalid material name: " + parts[1]);
                continue;
            }
            out.add(new ItemStack(m, Math.min(amt, m.getMaxStackSize())));
        }
        debug.console(
            "[TestChest] Successfully parsed " + out.size() + " item stacks"
        );
        return out;
    }
}
