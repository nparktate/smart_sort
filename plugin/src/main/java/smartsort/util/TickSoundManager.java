package smartsort.util;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import smartsort.SmartSortPlugin;

public class TickSoundManager implements Listener {

    private final SmartSortPlugin plugin;
    private final Map<UUID, BukkitTask> tasks = new HashMap<>();

    public TickSoundManager(SmartSortPlugin plugin) {
        this.plugin = plugin;
    }

    public void start(Player p) {
        stop(p);
        tasks.put(
            p.getUniqueId(),
            new BukkitRunnable() {
                @Override
                public void run() {
                    p.playSound(
                        p.getLocation(),
                        Sound.UI_BUTTON_CLICK,
                        0.3f,
                        1.5f
                    );
                }
            }
                .runTaskTimer(plugin, 0L, 8L)
        );
    }

    public void stop(Player p) {
        BukkitTask t = tasks.remove(p.getUniqueId());
        if (t != null) t.cancel();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        stop(e.getPlayer());
    }

    public void shutdown() {
        tasks.values().forEach(BukkitTask::cancel);
        tasks.clear();
    }
}
