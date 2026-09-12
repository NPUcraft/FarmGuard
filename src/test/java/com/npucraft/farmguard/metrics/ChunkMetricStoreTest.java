package com.npucraft.farmguard.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.npucraft.farmguard.TestData;
import com.npucraft.farmguard.config.FarmGuardSettings;
import com.npucraft.farmguard.model.MetricType;
import org.junit.jupiter.api.Test;

class ChunkMetricStoreTest {

    @Test
    void recordsAndSnapshotsActiveChunks() {
        ChunkMetricStore store = new ChunkMetricStore();
        FarmGuardSettings settings = FarmGuardSettings.defaults();
        long now = 50_000L;
        store.record(TestData.chunk(1, 1), MetricType.HOPPER, 36, now, settings);
        store.record(TestData.chunk(1, 1), MetricType.HOPPER, 36, now + 1000L, settings);
        var snapshots = store.snapshotActive(now + 1000L, settings);
        assertEquals(1, snapshots.size());
        assertTrue(snapshots.get(0).shortPerSecond(MetricType.HOPPER) > 0.0);
    }

    @Test
    void evictsInactiveChunksAfterTtl() {
        ChunkMetricStore store = new ChunkMetricStore();
        FarmGuardSettings settings = FarmGuardSettings.builder().inactiveTtlSeconds(2).build();
        long now = 1_000L;
        store.record(TestData.chunk(2, 2), MetricType.REDSTONE, 1, now, settings);
        assertEquals(1, store.size());
        store.cleanup(now + 3_000L, settings);
        assertEquals(0, store.size());
        assertNull(store.get(TestData.chunk(2, 2)));
    }

    @Test
    void censusDoesNotCreateUntrackedChunks() {
        ChunkMetricStore store = new ChunkMetricStore();
        FarmGuardSettings settings = FarmGuardSettings.defaults();
        store.replaceCensus(TestData.chunk(9, 9), 12, 4, 8, 20, 1, 10_000L, settings);
        store.adjustCensus(TestData.chunk(9, 9), 1, 0, 0, 0, 0, 10_000L, settings);
        assertEquals(0, store.size());
    }

    @Test
    void capKeepsHotspotWhenElytraFlybyFillsTheMap() {
        ChunkMetricStore store = new ChunkMetricStore();
        FarmGuardSettings settings = FarmGuardSettings.builder().maxTrackedChunks(1_000).build();
        long now = 20_000L;
        var hotspot = TestData.chunk(0, 0);
        store.record(hotspot, MetricType.REDSTONE, 80_000, now, settings);
        for (int i = 1; i <= 100_000; i++) {
            store.record(TestData.chunk(i, 0), MetricType.REDSTONE, 1, now, settings);
        }
        assertEquals(1_000, store.size());
        assertTrue(store.contains(hotspot));
    }

    @Test
    void millionIncrementsStayOnOneChunkWithFixedBuckets() {
        FarmGuardSettings settings = FarmGuardSettings.defaults();
        long[] volumes = {100_000L, 500_000L, 1_000_000L};
        for (long volume : volumes) {
            ChunkMetricStore store = new ChunkMetricStore();
            var key = TestData.chunk(3, 3);
            long now = 30_000L;
            for (long i = 0; i < volume; i++) {
                store.record(key, MetricType.REDSTONE, 1, now, settings);
            }
            assertEquals(1, store.size());
            assertEquals(settings.bucketCount(), store.get(key).bucketCount());
            FarmGuardSettings shortTtl = FarmGuardSettings.builder().inactiveTtlSeconds(1).build();
            store.cleanup(now + 2_000L, shortTtl);
            assertEquals(0, store.size());
        }
    }
}
