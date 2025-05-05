package smartsort.util;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.bukkit.scheduler.BukkitTask;
import smartsort.SmartSortPlugin;

import java.util.concurrent.TimeUnit;

/**
 * Enhanced cache implementation with version tracking to prevent
 * applying outdated sorting results.
 */
public class VersionedCache<K, V> {

    private final Cache<K, CacheEntry<V>> cache;
    private final DebugLogger debug;
    private BukkitTask cleanupTask;
    private final SmartSortPlugin plugin;

    public VersionedCache(int maxSize, DebugLogger debug) {
        this.debug = debug;
        this.plugin = SmartSortPlugin.get();
        
        // Use Caffeine's builder with expiration after write
        this.cache = Caffeine.newBuilder()
            .maximumSize(maxSize)
            .expireAfterWrite(6, TimeUnit.HOURS)
            .build();
            
        // Schedule periodic cleanup
        schedulePeriodicCleanup();
    }

    /**
     * Schedule periodic cleanup of the cache
     */
    private void schedulePeriodicCleanup() {
        int intervalMinutes = plugin.getConfig().getInt("performance.cache_cleanup_interval_minutes", 30);
        cleanupTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
            plugin,
            () -> {
                long beforeSize = cache.estimatedSize();
                cache.cleanUp();
                long afterSize = cache.estimatedSize();
                debug.console(
                    "[VersionedCache] Cleaned up cache. Before: " + 
                    beforeSize + ", After: " + afterSize +
                    ", Removed: " + (beforeSize - afterSize) + " entries"
                );
            },
            20 * 60, // Initial delay: 1 minute
            20 * 60 * intervalMinutes // Run every X minutes
        );
        debug.console("[VersionedCache] Scheduled cleanup every " + intervalMinutes + " minutes");
    }

    /**
     * Store a value in the cache with a version timestamp
     */
    public void put(K key, V value, long version) {
        cache.put(key, new CacheEntry<>(value, version));
        debug.console(
            "[VersionedCache] Added entry for key: " +
            key +
            " with version: " +
            version
        );
    }

    /**
     * Get a value if its version is current (>= currentVersion)
     */
    public V getIfCurrent(K key, long currentVersion) {
        CacheEntry<V> entry = cache.getIfPresent(key);
        if (entry != null) {
            if (entry.getVersion() >= currentVersion) {
                debug.console("[VersionedCache] Cache hit for key: " + key);
                return entry.getValue();
            } else {
                debug.console(
                    "[VersionedCache] Cache entry outdated for key: " +
                    key +
                    " (entry version: " +
                    entry.getVersion() +
                    ", current version: " +
                    currentVersion +
                    ")"
                );
            }
        } else {
            debug.console("[VersionedCache] Cache miss for key: " + key);
        }
        return null;
    }

    /**
     * Clear the cache
     */
    public void clear() {
        cache.invalidateAll();
        debug.console("[VersionedCache] Cache cleared");
    }

    /**
     * Get the size of the cache
     */
    public long size() {
        return cache.estimatedSize();
    }
    
    /**
     * Shutdown the cache
     */
    public void shutdown() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
        clear();
    }

    /**
     * Cache entry with version tracking
     */
    private static class CacheEntry<V> {

        private final V value;
        private final long version;

        public CacheEntry(V value, long version) {
            this.value = value;
            this.version = version;
        }

        public V getValue() {
            return value;
        }

        public long getVersion() {
            return version;
        }
    }
}
