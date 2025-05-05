package smartsort.util;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Manages async tasks with batching to improve performance
 */
public class AsyncTaskManager {
    private final JavaPlugin plugin;
    private final DebugLogger debug;
    private final ScheduledExecutorService executor;
    private final Map<UUID, Long> pendingTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Runnable> batchedTasks = new ConcurrentHashMap<>();
    private final long batchPeriodMillis;

    public AsyncTaskManager(JavaPlugin plugin, DebugLogger debug, int threadPoolSize, long batchPeriodMillis) {
        this.plugin = plugin;
        this.debug = debug;
        this.executor = Executors.newScheduledThreadPool(threadPoolSize);
        this.batchPeriodMillis = batchPeriodMillis;
        
        // Start the batch processor
        startBatchProcessor();
        
        debug.console("[AsyncTaskManager] Initialized with " + threadPoolSize + " threads and batch period of " + batchPeriodMillis + "ms");
    }
    
    /**
     * Submit a task to run asynchronously
     */
    public <T> void submitTask(Runnable task) {
        executor.submit(() -> {
            try {
                task.run();
            } catch (Exception e) {
                debug.console("[AsyncTaskManager] Error in async task: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }
    
    /**
     * Add a task to the batch for the specified player
     * If multiple tasks are added for the same player within the batch period,
     * only the most recent will be executed
     */
    public void addBatchedTask(UUID playerId, Runnable task) {
        // Update timestamp and task
        pendingTasks.put(playerId, System.currentTimeMillis());
        batchedTasks.put(playerId, task);
        debug.console("[AsyncTaskManager] Added batched task for player " + playerId);
    }
    
    /**
     * Process batched tasks on a schedule
     */
    private void startBatchProcessor() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            long now = System.currentTimeMillis();
            
            // Find tasks that are due to run (older than batch period)
            pendingTasks.entrySet().removeIf(entry -> {
                UUID playerId = entry.getKey();
                long timestamp = entry.getValue();
                
                if (now - timestamp >= batchPeriodMillis) {
                    // Execute the task
                    Runnable task = batchedTasks.remove(playerId);
                    if (task != null) {
                        debug.console("[AsyncTaskManager] Executing batched task for player " + playerId);
                        try {
                            task.run();
                        } catch (Exception e) {
                            debug.console("[AsyncTaskManager] Error in batched task: " + e.getMessage());
                        }
                    }
                    return true; // Remove this entry
                }
                return false; // Keep this entry for next batch
            });
        }, 20L, 20L); // Check every second
    }
    
    /**
     * Schedule a task to run after a delay
     */
    public void scheduleTask(Runnable task, long delayMillis) {
        executor.schedule(() -> {
            try {
                task.run();
            } catch (Exception e) {
                debug.console("[AsyncTaskManager] Error in scheduled task: " + e.getMessage());
            }
        }, delayMillis, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Run a task on the main server thread
     */
    public void runOnMainThread(Runnable task) {
        Bukkit.getScheduler().runTask(plugin, task);
    }
    
    /**
     * Shutdown the executor service
     */
    public void shutdown() {
        debug.console("[AsyncTaskManager] Shutting down");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
    }
}