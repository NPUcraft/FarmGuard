package dev.farmguard.metrics;

import dev.farmguard.config.FarmGuardSettings;
import dev.farmguard.model.ChunkActivitySnapshot;
import dev.farmguard.model.ChunkKey;
import dev.farmguard.model.MetricType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Main-thread chunk metric registry. Listeners only increment counters here.
 * Inactive entries are evicted by TTL and a hard cap that prefers keeping
 * currently hot chunks over fly-by elytra noise.
 */
public final class ChunkMetricStore {

    private final Map<ChunkKey, ChunkMetricRecord> records = new HashMap<>();

    public void record(ChunkKey key, MetricType type, long amount, long nowMs, FarmGuardSettings settings) {
        if (key == null || type == null || amount == 0L) {
            return;
        }
        ChunkMetricRecord record = records.get(key);
        if (record == null) {
            if (!admitNew(key, amount, nowMs, settings)) {
                return;
            }
            record = new ChunkMetricRecord(key, settings);
            records.put(key, record);
        }
        record.record(type, amount, nowMs);
    }

    public void adjustCensus(
            ChunkKey key,
            int villagerDelta,
            int minecartDelta,
            int itemDelta,
            int livingDelta,
            int otherDelta,
            long nowMs,
            FarmGuardSettings settings
    ) {
        if (key == null) {
            return;
        }
        ChunkMetricRecord record = records.get(key);
        if (record == null) {
            return;
        }
        record.adjustCensus(villagerDelta, minecartDelta, itemDelta, livingDelta, otherDelta, nowMs);
    }

    public void replaceCensus(
            ChunkKey key,
            int villagers,
            int minecarts,
            int items,
            int living,
            int others,
            long nowMs,
            FarmGuardSettings settings
    ) {
        ChunkMetricRecord record = records.get(key);
        if (record == null) {
            return;
        }
        record.setCensus(villagers, minecarts, items, living, others, nowMs);
    }

    public ChunkMetricRecord get(ChunkKey key) {
        return records.get(key);
    }

    public int size() {
        return records.size();
    }

    public boolean contains(ChunkKey key) {
        return records.containsKey(key);
    }

    public List<ChunkActivitySnapshot> snapshotRecent(long nowMs, FarmGuardSettings settings, Set<ChunkKey> extraKeep) {
        long windowMs = Math.max(1, settings.longWindowSeconds()) * 1000L;
        List<ChunkActivitySnapshot> snapshots = new ArrayList<>();
        for (ChunkMetricRecord record : records.values()) {
            boolean recent = record.lastActivityMs() > 0L && nowMs - record.lastActivityMs() <= windowMs;
            if (recent || (extraKeep != null && extraKeep.contains(record.key()))) {
                snapshots.add(record.snapshot(nowMs, settings));
            }
        }
        return snapshots;
    }

    public List<ChunkActivitySnapshot> snapshotActive(long nowMs, FarmGuardSettings settings) {
        return snapshotRecent(nowMs, settings, Set.of());
    }

    public void rememberSample(ChunkKey key, double activityScore, double mspt) {
        ChunkMetricRecord record = records.get(key);
        if (record != null) {
            record.rememberSample(activityScore, mspt);
        }
    }

    public int copyHistory(ChunkKey key, double[] activityOut, double[] msptOut) {
        ChunkMetricRecord record = records.get(key);
        if (record == null) {
            return 0;
        }
        return record.copyHistory(activityOut, msptOut);
    }

    public int cleanup(long nowMs, FarmGuardSettings settings) {
        long ttlMs = Math.max(1, settings.inactiveTtlSeconds()) * 1000L;
        int removed = 0;
        Iterator<Map.Entry<ChunkKey, ChunkMetricRecord>> iterator = records.entrySet().iterator();
        while (iterator.hasNext()) {
            ChunkMetricRecord record = iterator.next().getValue();
            if (record.isInactive(nowMs, ttlMs)) {
                iterator.remove();
                removed++;
            }
        }
        enforceCap(nowMs, settings, Double.POSITIVE_INFINITY);
        return removed;
    }

    public void evictWorld(UUID worldId) {
        records.keySet().removeIf(key -> key.worldId().equals(worldId));
    }

    public void clear() {
        records.clear();
    }

    /**
     * Admit a new chunk only by replacing an equal-or-colder resident.
     * A full map of hotter farms will not evict a hotspot for elytra noise.
     */
    private boolean admitNew(ChunkKey key, long incomingHint, long nowMs, FarmGuardSettings settings) {
        int max = Math.max(1, settings.maxTrackedChunks());
        if (records.size() < max) {
            return true;
        }
        Map.Entry<ChunkKey, ChunkMetricRecord> coldest = findColdest(nowMs, settings);
        if (coldest == null) {
            return false;
        }
        double coldHint = coldest.getValue().recentActivityHint(nowMs, settings);
        if (coldHint > incomingHint) {
            return false;
        }
        records.remove(coldest.getKey());
        return true;
    }

    private void enforceCap(long nowMs, FarmGuardSettings settings, double incomingHint) {
        int max = Math.max(1, settings.maxTrackedChunks());
        while (records.size() > max) {
            Map.Entry<ChunkKey, ChunkMetricRecord> coldest = findColdest(nowMs, settings);
            if (coldest == null || coldest.getValue().recentActivityHint(nowMs, settings) > incomingHint) {
                break;
            }
            records.remove(coldest.getKey());
        }
    }

    private Map.Entry<ChunkKey, ChunkMetricRecord> findColdest(long nowMs, FarmGuardSettings settings) {
        Map.Entry<ChunkKey, ChunkMetricRecord> coldest = null;
        double lowestHint = Double.MAX_VALUE;
        long oldest = Long.MAX_VALUE;
        for (Map.Entry<ChunkKey, ChunkMetricRecord> entry : records.entrySet()) {
            ChunkMetricRecord record = entry.getValue();
            double hint = record.recentActivityHint(nowMs, settings);
            long activity = record.lastActivityMs();
            if (hint < lowestHint || (hint == lowestHint && activity < oldest)) {
                lowestHint = hint;
                oldest = activity;
                coldest = entry;
            }
        }
        return coldest;
    }
}
