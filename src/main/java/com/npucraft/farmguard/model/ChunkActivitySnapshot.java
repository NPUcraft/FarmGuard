package com.npucraft.farmguard.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Immutable per-chunk activity view captured on the main thread
 * before any heavier scoring work.
 */
public final class ChunkActivitySnapshot {

    private final ChunkKey key;
    private final long capturedAtMs;
    private final EnumMap<MetricType, Double> shortPerSecond;
    private final EnumMap<MetricType, Double> longPerSecond;
    private final int villagers;
    private final int minecarts;
    private final int items;
    private final int livingEntities;
    private final int otherEntities;
    private final int previousItems;
    private final double previousActivityScore;

    public ChunkActivitySnapshot(
            ChunkKey key,
            long capturedAtMs,
            EnumMap<MetricType, Double> shortPerSecond,
            EnumMap<MetricType, Double> longPerSecond,
            int villagers,
            int minecarts,
            int items,
            int livingEntities,
            int otherEntities,
            int previousItems,
            double previousActivityScore
    ) {
        this.key = key;
        this.capturedAtMs = capturedAtMs;
        this.shortPerSecond = copy(shortPerSecond);
        this.longPerSecond = copy(longPerSecond);
        this.villagers = Math.max(0, villagers);
        this.minecarts = Math.max(0, minecarts);
        this.items = Math.max(0, items);
        this.livingEntities = Math.max(0, livingEntities);
        this.otherEntities = Math.max(0, otherEntities);
        this.previousItems = Math.max(0, previousItems);
        this.previousActivityScore = Math.max(0.0, previousActivityScore);
    }

    private static EnumMap<MetricType, Double> copy(EnumMap<MetricType, Double> source) {
        EnumMap<MetricType, Double> copy = new EnumMap<>(MetricType.class);
        if (source != null) {
            copy.putAll(source);
        }
        for (MetricType type : MetricType.values()) {
            copy.putIfAbsent(type, 0.0);
        }
        return copy;
    }

    public ChunkKey key() {
        return key;
    }

    public long capturedAtMs() {
        return capturedAtMs;
    }

    public double shortPerSecond(MetricType type) {
        return shortPerSecond.getOrDefault(type, 0.0);
    }

    public double longPerSecond(MetricType type) {
        return longPerSecond.getOrDefault(type, 0.0);
    }

    public Map<MetricType, Double> shortRates() {
        return Collections.unmodifiableMap(shortPerSecond);
    }

    public int villagers() {
        return villagers;
    }

    public int minecarts() {
        return minecarts;
    }

    public int items() {
        return items;
    }

    public int livingEntities() {
        return livingEntities;
    }

    public int otherEntities() {
        return otherEntities;
    }

    public int totalEntities() {
        return villagers + minecarts + items + livingEntities + otherEntities;
    }

    public int previousItems() {
        return previousItems;
    }

    public double previousActivityScore() {
        return previousActivityScore;
    }

    public double totalShortActivity() {
        double sum = 0.0;
        for (double value : shortPerSecond.values()) {
            sum += value;
        }
        return sum;
    }
}
