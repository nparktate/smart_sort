package smartsort.util;

import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import smartsort.SmartSortPlugin;

/** Unified debug handler: chat, console, toggle-per-player */
public class DebugLogger {

    private final SmartSortPlugin plugin;
    private final Set<UUID> chatPlayers = ConcurrentHashMap.newKeySet();
    private boolean global = false;
    private volatile boolean consoleDebug;

    private final Queue<LogMessage> messageQueue =
        new ConcurrentLinkedQueue<>();
    private BukkitTask processingTask;

    private static class LogMessage {

        final String content;
        final boolean toConsole;

        LogMessage(String content, boolean toConsole) {
            this.content = content;
            this.toConsole = toConsole;
        }
    }

    public DebugLogger(SmartSortPlugin plugin) {
        this.plugin = plugin;
        this.consoleDebug = plugin
            .getConfig()
            .getBoolean("logging.console_debug", false);
    }

    public void initialize() {
        processingTask = plugin
            .getServer()
            .getScheduler()
            .runTaskTimer(plugin, this::processMessageQueue, 1L, 1L);
    }

    public void shutdown() {
        if (processingTask != null) {
            processingTask.cancel();
            processingTask = null;
        }
    }

    private void processMessageQueue() {
        LogMessage message;
        while ((message = messageQueue.poll()) != null) {
            // Process on main thread
            if (message.toConsole && (consoleDebug || global)) {
                Bukkit.getConsoleSender()
                    .sendMessage("[SmartSort] " + message.content);
            }

            // Send to players
            for (UUID id : chatPlayers) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) p.sendMessage(
                    Component.text("[SmartSort] ")
                        .color(NamedTextColor.AQUA)
                        .append(
                            Component.text(message.content).color(
                                NamedTextColor.WHITE
                            )
                        )
                );
            }
        }
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
        // Queue message for processing on the main thread
        messageQueue.add(new LogMessage(msg, true));
    }
}
