package dev.farmguard;

import dev.farmguard.model.ChunkActivitySnapshot;
import dev.farmguard.model.ChunkKey;
import dev.farmguard.model.LagCorrelation;
import dev.farmguard.model.MetricType;
import dev.farmguard.model.RiskAssessment;
import dev.farmguard.model.RiskLevel;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;

public final class TestData {

    private TestData() {
    }

    public static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-000000000001");

    public static ChunkKey chunk(int x, int z) {
        return new ChunkKey(WORLD, "world", x, z);
    }

    public static ChunkActivitySnapshot snapshot(ChunkKey key, double hopper, double villagerRate, int villagers, int items) {
        EnumMap<MetricType, Double> shortRates = zeros();
        EnumMap<MetricType, Double> longRates = zeros();
        shortRates.put(MetricType.HOPPER, hopper);
        shortRates.put(MetricType.VILLAGER, villagerRate);
        longRates.put(MetricType.HOPPER, hopper * 0.6);
        longRates.put(MetricType.VILLAGER, villagerRate * 0.6);
        return new ChunkActivitySnapshot(key, 1_000_000L, shortRates, longRates, villagers, 0, items, 10, 0, Math.max(0, items / 2), 0.0);
    }

    public static ChunkActivitySnapshot snapshotRates(ChunkKey key, MetricType type, double shortRate, double longRate) {
        EnumMap<MetricType, Double> shortRates = zeros();
        EnumMap<MetricType, Double> longRates = zeros();
        shortRates.put(type, shortRate);
        longRates.put(type, longRate);
        return new ChunkActivitySnapshot(key, 1_000_000L, shortRates, longRates, 0, 0, 0, 0, 0, 0, 0.0);
    }

    public static ChunkActivitySnapshot mixed(ChunkKey key, double hopper, double redstone, double spawn, int villagers, int items, int minecarts) {
        EnumMap<MetricType, Double> shortRates = zeros();
        EnumMap<MetricType, Double> longRates = zeros();
        shortRates.put(MetricType.HOPPER, hopper);
        shortRates.put(MetricType.REDSTONE, redstone);
        shortRates.put(MetricType.ENTITY_SPAWN, spawn);
        longRates.put(MetricType.HOPPER, hopper * 0.8);
        longRates.put(MetricType.REDSTONE, redstone * 0.8);
        longRates.put(MetricType.ENTITY_SPAWN, spawn * 0.8);
        return new ChunkActivitySnapshot(key, 1_000_000L, shortRates, longRates, villagers, minecarts, items, 20, 0, items, 20.0);
    }

    public static RiskAssessment risk(ChunkKey key, RiskLevel level, double score, ChunkActivitySnapshot snapshot) {
        return new RiskAssessment(key, score, score, 1.0, level, LagCorrelation.NONE, List.of(), List.of(), snapshot);
    }

    private static EnumMap<MetricType, Double> zeros() {
        EnumMap<MetricType, Double> map = new EnumMap<>(MetricType.class);
        for (MetricType type : MetricType.values()) {
            map.put(type, 0.0);
        }
        return map;
    }
}
