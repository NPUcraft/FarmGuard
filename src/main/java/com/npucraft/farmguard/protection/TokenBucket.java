package dev.farmguard.protection;

/**
 * Deterministic rate limiter. Tokens refill linearly over time so throttling
 * is stable and testable instead of coin-flip cancellation.
 * Burst is capped to one second of refill so a lag spike cannot dump a huge
 * token surplus into the next tick.
 */
public final class TokenBucket {

    private static final long MAX_REFILL_WINDOW_MS = 2_000L;

    private double tokens;
    private long lastRefillMs;
    private boolean started;

    public boolean tryConsume(long nowMs, int refillPerSecond) {
        if (refillPerSecond <= 0) {
            return false;
        }
        if (!started) {
            started = true;
            tokens = refillPerSecond;
            lastRefillMs = nowMs;
        } else if (nowMs > lastRefillMs) {
            long elapsed = Math.min(nowMs - lastRefillMs, MAX_REFILL_WINDOW_MS);
            double refill = refillPerSecond * (elapsed / 1000.0);
            tokens = tokens + refill;
            lastRefillMs = nowMs;
        }
        tokens = Math.min(tokens, refillPerSecond);
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }

    public double tokens() {
        return tokens;
    }

    public void reset() {
        tokens = 0.0;
        lastRefillMs = 0L;
        started = false;
    }
}
