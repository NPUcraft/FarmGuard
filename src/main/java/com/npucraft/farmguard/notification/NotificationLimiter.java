package dev.farmguard.notification;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Deduplicates noisy admin alerts. A hotspot that stays HIGH for a minute
 * should not produce one console line per second.
 */
public final class NotificationLimiter {

    private final Map<String, Long> lastSent = new HashMap<>();
    private final Map<String, Long> firstSeen = new HashMap<>();

    public boolean allow(String key, long nowMs, int cooldownSeconds) {
        Long previous = lastSent.get(key);
        long cooldownMs = Math.max(1, cooldownSeconds) * 1000L;
        if (previous != null && nowMs - previous < cooldownMs) {
            return false;
        }
        lastSent.put(key, nowMs);
        firstSeen.putIfAbsent(key, nowMs);
        return true;
    }

    public int secondsSinceFirst(String key, long nowMs) {
        Long first = firstSeen.get(key);
        if (first == null) {
            firstSeen.put(key, nowMs);
            return 0;
        }
        return (int) Math.max(0L, (nowMs - first) / 1000L);
    }

    public void clear(String key) {
        lastSent.remove(key);
        firstSeen.remove(key);
    }

    public void clearAll() {
        lastSent.clear();
        firstSeen.clear();
    }

    public void cleanup(long nowMs, long maxAgeMs) {
        Iterator<Map.Entry<String, Long>> iterator = lastSent.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            if (nowMs - entry.getValue() > maxAgeMs) {
                iterator.remove();
                firstSeen.remove(entry.getKey());
            }
        }
    }
}
