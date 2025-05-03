package smartsort.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.json.JSONObject;
import smartsort.SmartSortPlugin;

public class PlayerPreferenceManager {

    private final SmartSortPlugin plugin;
    private final File dataFolder;
    private final Map<UUID, PlayerPreferences> playerPreferences =
        new ConcurrentHashMap<>();

    public PlayerPreferenceManager(SmartSortPlugin plugin) {
        this.plugin = plugin;
        this.dataFolder = new File(plugin.getDataFolder(), "playerdata");
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }

        // Register events for player join/quit
        plugin
            .getServer()
            .getPluginManager()
            .registerEvents(new PlayerListener(), plugin);
    }

    public void loadAllPreferences() {
        if (!dataFolder.exists()) return;

        File[] files = dataFolder.listFiles((dir, name) ->
            name.endsWith(".json")
        );
        if (files == null) return;

        for (File file : files) {
            try {
                String filename = file.getName();
                String uuidStr = filename.substring(0, filename.length() - 5); // Remove .json
                UUID uuid = UUID.fromString(uuidStr);

                String jsonContent = Files.readString(file.toPath());
                JSONObject json = new JSONObject(jsonContent);

                PlayerPreferences prefs = new PlayerPreferences();
                prefs.autoSortEnabled = json.optBoolean(
                    "autoSortEnabled",
                    false
                );

                playerPreferences.put(uuid, prefs);
                plugin
                    .getLogger()
                    .info("Loaded preferences for player " + uuid);
            } catch (Exception e) {
                plugin
                    .getLogger()
                    .warning(
                        "Failed to load player preferences from " +
                        file.getName() +
                        ": " +
                        e.getMessage()
                    );
            }
        }
    }

    public void saveAllPreferences() {
        for (Map.Entry<
            UUID,
            PlayerPreferences
        > entry : playerPreferences.entrySet()) {
            savePlayerPreferences(entry.getKey());
        }
    }

    private void savePlayerPreferences(UUID playerId) {
        PlayerPreferences prefs = playerPreferences.get(playerId);
        if (prefs == null) return;

        try {
            JSONObject json = new JSONObject();
            json.put("autoSortEnabled", prefs.autoSortEnabled);

            File playerFile = new File(
                dataFolder,
                playerId.toString() + ".json"
            );
            Files.writeString(playerFile.toPath(), json.toString());
        } catch (IOException e) {
            plugin
                .getLogger()
                .warning(
                    "Failed to save preferences for player " +
                    playerId +
                    ": " +
                    e.getMessage()
                );
        }
    }

    public boolean isAutoSortEnabled(UUID playerId) {
        PlayerPreferences prefs = playerPreferences.computeIfAbsent(
            playerId,
            k -> new PlayerPreferences()
        );
        return prefs.autoSortEnabled;
    }

    public void setAutoSortEnabled(UUID playerId, boolean enabled) {
        PlayerPreferences prefs = playerPreferences.computeIfAbsent(
            playerId,
            k -> new PlayerPreferences()
        );
        prefs.autoSortEnabled = enabled;
        savePlayerPreferences(playerId);
    }

    public void toggleAutoSort(UUID playerId) {
        PlayerPreferences prefs = playerPreferences.computeIfAbsent(
            playerId,
            k -> new PlayerPreferences()
        );
        prefs.autoSortEnabled = !prefs.autoSortEnabled;
        savePlayerPreferences(playerId);
    }

    private static class PlayerPreferences {

        boolean autoSortEnabled = false;
    }

    private class PlayerListener implements Listener {

        @EventHandler
        public void onPlayerJoin(PlayerJoinEvent event) {
            // Load preferences if not already loaded
            UUID playerId = event.getPlayer().getUniqueId();
            if (!playerPreferences.containsKey(playerId)) {
                File playerFile = new File(
                    dataFolder,
                    playerId.toString() + ".json"
                );
                if (playerFile.exists()) {
                    try {
                        String jsonContent = Files.readString(
                            playerFile.toPath()
                        );
                        JSONObject json = new JSONObject(jsonContent);

                        PlayerPreferences prefs = new PlayerPreferences();
                        prefs.autoSortEnabled = json.optBoolean(
                            "autoSortEnabled",
                            false
                        );

                        playerPreferences.put(playerId, prefs);
                    } catch (Exception e) {
                        plugin
                            .getLogger()
                            .warning(
                                "Failed to load preferences for player " +
                                playerId +
                                ": " +
                                e.getMessage()
                            );
                        playerPreferences.put(
                            playerId,
                            new PlayerPreferences()
                        );
                    }
                } else {
                    playerPreferences.put(playerId, new PlayerPreferences());
                }
            }
        }

        @EventHandler
        public void onPlayerQuit(PlayerQuitEvent event) {
            // Save preferences when player quits
            UUID playerId = event.getPlayer().getUniqueId();
            if (playerPreferences.containsKey(playerId)) {
                savePlayerPreferences(playerId);
            }
        }
    }
}
