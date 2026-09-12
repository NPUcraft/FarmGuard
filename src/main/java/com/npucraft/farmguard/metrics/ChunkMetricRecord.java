package com.npucraft.farmguard.metrics;

import com.npucraft.farmguard.config.FarmGuardSettings;
import com.npucraft.farmguard.model.ChunkActivitySnapshot;
import com.npucraft.farmguard.model.ChunkKey;
import com.npucraft.farmguard.model.MetricType;
import java.util.EnumMap;

public final class ChunkMetricRecord {

    private final ChunkKey key;
    private final RollingCounter[] counters;
    private int villagers;
    private int minecarts;
    private int items;
    private int livingEntities;
    private int otherEntities;
    private int previousItems;
    private double lastActivityScore;
    private long lastActivityMs;
    private final double[] activityHistory;
    private final double[] msptHistory;
    private int historyCursor;
    private int historySize;

    public ChunkMetricRecord(ChunkKey key, FarmGuardSettings settings) {
        this.key = key;
        this.counters = new RollingCounter[MetricType.values().length];
        long bucketMs = Math.max(1, settings.bucketSeconds()) * 1000L;
        int buckets = settings.bucketCount();
        for (int i = 0; i < counters.length; i++) {
            counters[i] = new RollingCounter(buckets, bucketMs);
        }
        this.lastActivityMs = 0L;
        this.activityHistory = new double[Math.max(4, settings.correlationSamples())];
        this.msptHistory = new double[activityHistory.length];
    }

    public ChunkKey key() {
        return key;
    }

    public void record(MetricType type, long amount, long nowMs) {
        if (amount == 0L) {
            return;
        }
        counters[type.ordinal()].add(nowMs, amount);
        lastActivityMs = nowMs;
    }

    public void adjustCensus(int villagerDelta, int minecartDelta, int itemDelta, int livingDelta, int otherDelta, long nowMs) {
        villagers = Math.max(0, villagers + villagerDelta);
        minecarts = Math.max(0, minecarts + minecartDelta);
        previousItems = items;
        items = Math.max(0, items + itemDelta);
        livingEntities = Math.max(0, livingEntities + livingDelta);
        otherEntities = Math.max(0, otherEntities + otherDelta);
        if (villagerDelta != 0 || minecartDelta != 0 || itemDelta != 0 || livingDelta != 0 || otherDelta != 0) {
            lastActivityMs = nowMs;
        }
    }

    public void setCensus(int villagerCount, int minecartCount, int itemCount, int livingCount, int otherCount, long nowMs) {
        previousItems = items;
        villagers = Math.max(0, villagerCount);
        minecarts = Math.max(0, minecartCount);
        items = Math.max(0, itemCount);
        livingEntities = Math.max(0, livingCount);
        otherEntities = Math.max(0, otherCount);
    }

    public ChunkActivitySnapshot snapshot(long nowMs, FarmGuardSettings settings) {
        EnumMap<MetricType, Double> shortRates = new EnumMap<>(MetricType.class);
        EnumMap<MetricType, Double> longRates = new EnumMap<>(MetricType.class);
        int shortBuckets = settings.shortBucketCount();
        int longBuckets = settings.bucketCount();
        for (MetricType type : MetricType.values()) {
            RollingCounter counter = counters[type.ordinal()];
            shortRates.put(type, counter.perSecond(nowMs, shortBuckets, settings.shortWindowSeconds()));
            longRates.put(type, counter.perSecond(nowMs, longBuckets, settings.longWindowSeconds()));
        }
        return new ChunkActivitySnapshot(
                key,
                nowMs,
                shortRates,
                longRates,
                villagers,
                minecarts,
                items,
                livingEntities,
                otherEntities,
                previousItems,
                lastActivityScore
        );
    }

    public void rememberSample(double activityScore, double mspt) {
        activityHistory[historyCursor] = activityScore;
        msptHistory[historyCursor] = mspt;
        historyCursor = (historyCursor + 1) % activityHistory.length;
        if (historySize < activityHistory.length) {
            historySize++;
        }
        lastActivityScore = activityScore;
    }

    public int copyHistory(double[] activityOut, double[] msptOut) {
        int n = Math.min(historySize, activityOut.length);
        int start = historySize == activityHistory.length ? historyCursor : 0;
        for (int i = 0; i < n; i++) {
            int idx = (start + i) % activityHistory.length;
            activityOut[i] = activityHistory[idx];
            msptOut[i] = msptHistory[idx];
        }
        return n;
    }

    public long lastActivityMs() {
        return lastActivityMs;
    }

    public boolean isInactive(long nowMs, long ttlMs) {
        return lastActivityMs > 0L && nowMs - lastActivityMs > ttlMs;
    }

    int bucketCount() {
        return counters.length == 0 ? 0 : counters[0].bucketCount();
    }

    public double recentActivityHint(long nowMs, FarmGuardSettings settings) {
        double sum = 0.0;
        for (RollingCounter counter : counters) {
            sum += counter.sumLast(nowMs, settings.shortBucketCount());
        }
        return sum + villagers + minecarts + items + livingEntities;
    }
}
