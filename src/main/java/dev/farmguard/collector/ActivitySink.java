package dev.farmguard.collector;

import dev.farmguard.config.FarmGuardSettings;
import dev.farmguard.metrics.ChunkMetricStore;
import dev.farmguard.model.ChunkKey;
import dev.farmguard.model.MetricType;
import java.util.function.Supplier;

/**
 * Main-thread sink used by listeners. Recording is a map lookup plus a counter
 * increment; no scoring or IO happens here.
 */
public final class ActivitySink {

    private final ChunkMetricStore store;
    private final Supplier<FarmGuardSettings> settings;

    public ActivitySink(ChunkMetricStore store, Supplier<FarmGuardSettings> settings) {
        this.store = store;
        this.settings = settings;
    }

    public void record(ChunkKey key, MetricType type) {
        record(key, type, 1L);
    }

    public void record(ChunkKey key, MetricType type, long amount) {
        if (key == null || type == null) {
            return;
        }
        FarmGuardSettings current = settings.get();
        if (!current.monitorsWorld(key.worldName())) {
            return;
        }
        store.record(key, type, amount, System.currentTimeMillis(), current);
    }

    public void adjustCensus(ChunkKey key, int villagers, int minecarts, int items, int living, int others) {
        if (key == null) {
            return;
        }
        FarmGuardSettings current = settings.get();
        if (!current.monitorsWorld(key.worldName())) {
            return;
        }
        store.adjustCensus(key, villagers, minecarts, items, living, others, System.currentTimeMillis(), current);
    }
}
