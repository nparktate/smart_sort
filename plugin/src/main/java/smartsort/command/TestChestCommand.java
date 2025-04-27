package smartsort.command;

import java.util.*;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Chest;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import smartsort.SmartSortPlugin;
import smartsort.ai.AIService;
import smartsort.util.DebugLogger;

public class TestChestCommand {

    private final AIService ai;
    // Using plugin field to store plugin instance for later use
    private final SmartSortPlugin plugin;
    private final DebugLogger debug;

    public TestChestCommand(
        SmartSortPlugin plugin,
        AIService ai,
        DebugLogger dbg
    ) {
        this.plugin = plugin;
        this.ai = ai;
        this.debug = dbg;
    }

    public void register() {
        PluginCommand cmd = SmartSortPlugin.get().getCommand("testsortchests");
        if (cmd == null) {
            debug.console(
                "[TestChest] Failed to register command: command not found"
            );
            return;
        }
        cmd.setExecutor(this::handle);
        debug.console("[TestChest] Command registered successfully");

        // Add tab completion for common themes
        cmd.setTabCompleter((sender, command, alias, args) -> {
            if (args.length == 1) {
                List<String> suggestions = List.of(
                    "random",
                    "mining",
                    "combat",
                    "farming",
                    "redstone",
                    "building",
                    "enchanting",
                    "brewing",
                    "nether",
                    "end",
                    "storage",
                    "valuables",
                    "tools",
                    "weapons",
                    "food"
                );
                return suggestions
                    .stream()
                    .filter(s ->
                        s.toLowerCase().startsWith(args[0].toLowerCase())
                    )
                    .collect(Collectors.toList());
            }
            return Collections.emptyList();
        });
    }

    private boolean handle(CommandSender s, Command c, String l, String[] a) {
        if (!(s instanceof Player p)) {
            debug.console(
                "[TestChest] Command executed by non-player: " + s.getName()
            );
            s.sendMessage("Players only");
            return true;
        }
        if (!p.hasPermission("smartsort.test")) {
            debug.console("[TestChest] Permission denied for: " + p.getName());
            p.sendMessage(
                Component.text("No permission").color(NamedTextColor.RED)
            );
            return true;
        }

        String theme = "random";
        if (a.length > 0) {
            theme = String.join(" ", a);
        }

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
        Location base = p.getLocation().add(2, 0, 0);
        debug.console(
            "[TestChest] Generating chests at " +
            base.getBlockX() +
            "," +
            base.getBlockY() +
            "," +
            base.getBlockZ()
        );

        // Create fewer chests and stagger their creation
        int maxChests = 4; // Reduce from 9 to 4 chests

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

            // Add progressive delay between chest creations (0s, 3s, 6s, 9s)
            plugin
                .getServer()
                .getScheduler()
                .runTaskLater(
                    plugin,
                    () -> {
                        createSingleChest(p, base, x, z, requestedTheme, index);
                    },
                    i * 60L
                ); // 3-second delay between creations
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
        // Extract the chest creation logic from the original generate method
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

        boolean isRandom = requestedTheme.equalsIgnoreCase("random");
        String promptPrefix = isRandom
            ? "First, choose a creative Minecraft chest theme. Then create a realistic Minecraft chest inventory matching your chosen theme.\n"
            : "Create a realistic Minecraft chest inventory for theme '" +
            requestedTheme +
            "'.\n";

        String prompt =
            promptPrefix +
            "Rules:\n" +
            "1. Include 12-16 different items with appropriate quantities\n" +
            "2. Use ONLY valid Minecraft 1.21 material names (like DIAMOND, OAK_LOG)\n" +
            "3. Format EXACTLY as '12xITEM_NAME' with one item per line (no spaces around x)\n" +
            "4. Include both common and valuable items\n" +
            "5. If you chose your own theme, include it as the first line starting with 'Theme:'\n" +
            "6. No other commentary or explanations";

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

                        // Extract theme if AI generated one
                        String actualTheme = requestedTheme;
                        String itemsText = reply;

                        if (isRandom && reply.trim().startsWith("Theme:")) {
                            String[] lines = reply.split("\n");
                            if (
                                lines.length > 0 &&
                                lines[0].startsWith("Theme:")
                            ) {
                                actualTheme = lines[0].substring(6).trim();
                                debug.console(
                                    "[TestChest] AI selected theme: " +
                                    actualTheme
                                );
                                // Remove theme line from parsing
                                StringBuilder sb = new StringBuilder();
                                for (int i = 1; i < lines.length; i++) {
                                    sb.append(lines[i]).append("\n");
                                }
                                itemsText = sb.toString();
                            }
                        }

                        List<ItemStack> stacks = parseReply(itemsText);

                        if (stacks.isEmpty()) {
                            debug.console(
                                "[TestChest] AI generated no valid items for " +
                                actualTheme
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

                        // Place items randomly for better testing
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
                            actualTheme
                        );
                        p.sendMessage(
                            Component.text(
                                "Created chest: " + actualTheme
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
            // Updated regex to handle both formats with or without spaces
            if (!line.matches("(?i)\\d+\\s*[xX]\\s*[A-Z0-9_]+")) {
                debug.console(
                    "[TestChest] Skipping invalid line format: " + line
                );
                continue;
            }
            // Split with flexible whitespace pattern
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
