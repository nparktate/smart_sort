package smartsort.command;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import smartsort.SmartSortPlugin;
import smartsort.ai.AIService;
import smartsort.util.DebugLogger;

public class SmartSortCommand {

    private final SmartSortPlugin plugin;
    private final DebugLogger debug;
    private final TestChestCommand testChestHandler;

    public SmartSortCommand(
        SmartSortPlugin plugin,
        AIService ai,
        DebugLogger dbg
    ) {
        this.plugin = plugin;
        this.debug = dbg;
        this.testChestHandler = new TestChestCommand(plugin, ai, dbg);
    }

    public void register() {
        PluginCommand cmd = plugin.getCommand("smartsort");
        if (cmd == null) return;

        cmd.setExecutor(this::handle);
        cmd.setTabCompleter(this::tabComplete);
    }

    private List<String> tabComplete(
        CommandSender s,
        Command c,
        String l,
        String[] args
    ) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            List<String> options = List.of("debug", "console", "test", "help");
            String input = args[0].toLowerCase();
            options.forEach(opt -> {
                if (opt.startsWith(input)) completions.add(opt);
            });
        } else if (args.length == 2 && args[0].equalsIgnoreCase("test")) {
            List<String> themes = new ArrayList<>();
            themes.add("random");
            themes.addAll(TestChestCommand.STARTER_THEMES);

            String input = args[1].toLowerCase();
            themes.forEach(theme -> {
                if (theme.toLowerCase().startsWith(input)) {
                    completions.add(theme);
                }
            });
        }

        return completions;
    }

    private void showHelp(CommandSender s) {
        s.sendMessage(
            """
            §6§l⚡ SmartSort Commands
            §e/smartsort debug §7- Toggle chat debug
            §e/smartsort console §7- Toggle console debug §8(admin)
            §e/smartsort test §7- Create themed test chests
            §e/smartsort help §7- Show this help
            """
        );
    }

    private void showTestThemes(CommandSender s) {
        s.sendMessage("§6§l📦 Available Test Chest Themes:");
        s.sendMessage("§e/smartsort test random §7- Pick a random theme");
        s.sendMessage("§fPredefined themes:");
        TestChestCommand.STARTER_THEMES.forEach(theme ->
            s.sendMessage("§7- §f" + theme)
        );
        s.sendMessage("§7Usage: §f/smartsort test <theme>");
    }

    private boolean handle(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 0) {
            showHelp(s);
            return true;
        }

        switch (a[0].toLowerCase()) {
            case "help":
                showHelp(s);
                return true;
            case "debug":
                if (s instanceof Player p) debug.togglePlayer(p);
                else debug.toggleGlobal();
                return true;
            case "console":
                if (!s.hasPermission("smartsort.admin.console")) {
                    s.sendMessage("§cNo permission");
                    return true;
                }
                boolean now = plugin
                    .getConfig()
                    .getBoolean("logging.console_debug", false);
                plugin.getConfig().set("logging.console_debug", !now);
                plugin.saveConfig();
                debug.setConsoleDebug(!now);
                s.sendMessage("[SmartSort] console logging " + (!now));
                return true;
            case "test":
                if (!s.hasPermission("smartsort.test")) {
                    s.sendMessage("§cNo permission");
                    return true;
                }
                if (!(s instanceof Player)) {
                    s.sendMessage("§cPlayers only");
                    return true;
                }

                if (a.length == 1) {
                    showTestThemes(s);
                    return true;
                }

                String theme = String.join(
                    " ",
                    java.util.Arrays.copyOfRange(a, 1, a.length)
                );
                return testChestHandler.handleTestCommand((Player) s, theme);
            default:
                s.sendMessage("§cUnknown command. Use /smartsort help");
                return true;
        }
    }
}
