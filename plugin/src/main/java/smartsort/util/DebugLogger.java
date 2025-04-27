package smartsort.util;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import smartsort.SmartSortPlugin;

/** Unified debug handler: chat, console, toggle-per-player */
public class DebugLogger {

    @SuppressWarnings("unused")
    private final SmartSortPlugin plugin;

    private final Set<UUID> chatPlayers = new HashSet<>();
    private boolean global = false;
    private volatile boolean consoleDebug;

    public DebugLogger(SmartSortPlugin plugin) {
        this.plugin = plugin;
        this.consoleDebug = plugin
            .getConfig()
            .getBoolean("logging.console_debug", false);
    }

    public void togglePlayer(Player p) {
        if (chatPlayers.remove(p.getUniqueId())) p.sendMessage(
            "§e[SmartSort] debug off"
        );
        else {
            chatPlayers.add(p.getUniqueId());
            p.sendMessage("§a[SmartSort] debug on");
        }
    }

    public void toggleGlobal() {
        global = !global;
        console("Global debug " + global);
    }

    public void setConsoleDebug(boolean enabled) {
        this.consoleDebug = enabled;
    }

    public void console(String msg) {
        // Use cached console debug value
        if (consoleDebug || global) {
            Bukkit.getConsoleSender().sendMessage("[SmartSort] " + msg);
        }

        // Player chat messages remain the same
        for (UUID id : chatPlayers) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.sendMessage(
                Component.text("[SmartSort] ")
                    .color(NamedTextColor.AQUA)
                    .append(Component.text(msg).color(NamedTextColor.WHITE))
            );
        }
    }
}
