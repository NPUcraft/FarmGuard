package com.npucraft.farmguard.debug;

import java.util.concurrent.atomic.LongAdder;

/**
 * Server-wide chunk load/unload counters for debug snapshots. Hot path is an
 * enabled check plus LongAdder increment. Never writes JSON or files.
 */
public final class DebugWorldCounters {

    private volatile boolean enabled;
    private final LongAdder chunkLoads = new LongAdder();
    private final LongAdder chunkUnloads = new LongAdder();
    private final LongAdder newChunkLoads = new LongAdder();

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            reset();
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public void onLoad(boolean isNewChunk) {
        if (!enabled) {
            return;
        }
        chunkLoads.increment();
        if (isNewChunk) {
            newChunkLoads.increment();
        }
    }

    public void onUnload() {
        if (!enabled) {
            return;
        }
        chunkUnloads.increment();
    }

    public Snapshot drain() {
        return new Snapshot(
                chunkLoads.sumThenReset(),
                chunkUnloads.sumThenReset(),
                newChunkLoads.sumThenReset()
        );
    }

    public void reset() {
        chunkLoads.reset();
        chunkUnloads.reset();
        newChunkLoads.reset();
    }

    public record Snapshot(long chunkLoads, long chunkUnloads, long newChunkLoads) {
    }
}
