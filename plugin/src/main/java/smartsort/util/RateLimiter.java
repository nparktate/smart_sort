package smartsort.util;

import java.util.concurrent.TimeUnit;

/**
 * Simple rate limiter for API requests with debug logging
 */
public class RateLimiter {

    private final int maxRequests;
    private final long periodMillis;
    private long lastCheckTime = System.currentTimeMillis();
    private int tokens;
    private final DebugLogger debug;
    private int totalRequestsAllowed = 0;
    private int totalRequestsDenied = 0;

    /**
     * Creates a rate limiter
     * @param maxRequests Maximum number of requests
     * @param perSeconds Time period in seconds
     * @param debug Debug logger for monitoring
     */
    public RateLimiter(int maxRequests, int perSeconds, DebugLogger debug) {
        this.maxRequests = maxRequests;
        this.periodMillis = TimeUnit.SECONDS.toMillis(perSeconds);
        this.tokens = maxRequests;
        this.debug = debug;

        debug.console(
            "[RateLimiter] Initialized with " +
            maxRequests +
            " requests per " +
            perSeconds +
            " seconds"
        );
    }

    /**
     * Try to acquire a token for a request
     * @return true if request is allowed, false if rate limited
     */
    public synchronized boolean tryAcquire() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastCheckTime;

        // Refill tokens if time period has passed
        if (elapsed >= periodMillis) {
            int refillAmount = maxRequests - tokens;
            tokens = maxRequests;
            lastCheckTime = now;
            debug.console(
                "[RateLimiter] Period reset. Refilled " +
                refillAmount +
                " tokens. Now at max capacity: " +
                tokens
            );
        }

        // Allow request if tokens available
        if (tokens > 0) {
            tokens--;
            totalRequestsAllowed++;
            debug.console(
                "[RateLimiter] Request allowed. Remaining tokens: " +
                tokens +
                " (Total allowed: " +
                totalRequestsAllowed +
                ")"
            );
            return true;
        }

        totalRequestsDenied++;
        debug.console(
            "[RateLimiter] Request DENIED due to rate limit. Tokens depleted. Wait " +
            ((lastCheckTime + periodMillis - now) / 1000.0) +
            " seconds. (Total denied: " +
            totalRequestsDenied +
            ")"
        );
        return false;
    }

    /**
     * Get statistics about rate limiter usage
     * @return String containing rate limiter statistics
     */
    public String getStats() {
        return (
            "Rate Limiter Stats: " +
            "Max=" +
            maxRequests +
            ", " +
            "Current=" +
            tokens +
            ", " +
            "Allowed=" +
            totalRequestsAllowed +
            ", " +
            "Denied=" +
            totalRequestsDenied +
            ", " +
            "Period=" +
            (periodMillis / 1000) +
            "s"
        );
    }
}
