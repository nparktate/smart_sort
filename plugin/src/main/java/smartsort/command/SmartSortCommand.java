package smartsort.command;

import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import smartsort.SmartSortPlugin;
import smartsort.util.DebugLogger;

public class SmartSortCommand {

    private final DebugLogger debug;

    public SmartSortCommand(SmartSortPlugin plugin, DebugLogger dbg) {
        this.debug = dbg;
    }

    public void register() {
        PluginCommand cmd = SmartSortPlugin.get().getCommand("smartsort");
        if (cmd == null) return;

        cmd.setExecutor(this::handle);
        cmd.setTabCompleter((s, c, l, args) ->
            List.of("debug", "console", "help")
                .stream()
                .filter(
                    t -> args.length == 1 && t.startsWith(args[0].toLowerCase())
                )
                .toList()
        );
    }

    private boolean handle(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 0 || a[0].equalsIgnoreCase("help")) {
            s.sendMessage(
                """
                §6§lSmartSort Commands
                §e/smartsort debug §7- toggle chat debug
                §e/smartsort console §7- toggle console debug"""
            );
            return true;
        }
        if (a[0].equalsIgnoreCase("debug")) {
            if (s instanceof Player p) debug.togglePlayer(p);
            else debug.toggleGlobal();
            return true;
        }
        if (a[0].equalsIgnoreCase("console")) {
            SmartSortPlugin pl = SmartSortPlugin.get();
            boolean now = pl
                .getConfig()
                .getBoolean("logging.console_debug", false);
            pl.getConfig().set("logging.console_debug", !now);
            pl.saveConfig();
            debug.setConsoleDebug(!now);
            s.sendMessage("[SmartSort] console logging " + (!now));
            return true;
        }
        return false;
    }
}
