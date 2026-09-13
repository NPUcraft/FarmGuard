package com.npucraft.farmguard.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DebugWorldCountersTest {

    @Test
    void disabledIsNoOp() {
        DebugWorldCounters counters = new DebugWorldCounters();
        counters.onLoad(true);
        counters.onUnload();
        DebugWorldCounters.Snapshot snapshot = counters.drain();
        assertEquals(0L, snapshot.chunkLoads());
        assertEquals(0L, snapshot.chunkUnloads());
        assertEquals(0L, snapshot.newChunkLoads());
        assertFalse(counters.enabled());
    }

    @Test
    void countsLoadsUnloadsAndNewChunksThenResets() {
        DebugWorldCounters counters = new DebugWorldCounters();
        counters.setEnabled(true);
        counters.onLoad(false);
        counters.onLoad(true);
        counters.onLoad(true);
        counters.onUnload();
        counters.onUnload();
        DebugWorldCounters.Snapshot snapshot = counters.drain();
        assertEquals(3L, snapshot.chunkLoads());
        assertEquals(2L, snapshot.chunkUnloads());
        assertEquals(2L, snapshot.newChunkLoads());
        DebugWorldCounters.Snapshot empty = counters.drain();
        assertEquals(0L, empty.chunkLoads());
        assertEquals(0L, empty.newChunkLoads());
    }

    @Test
    void disablingResetsAccumulatedCounts() {
        DebugWorldCounters counters = new DebugWorldCounters();
        counters.setEnabled(true);
        counters.onLoad(true);
        counters.setEnabled(false);
        assertEquals(0L, counters.drain().chunkLoads());
        counters.onLoad(true);
        assertEquals(0L, counters.drain().newChunkLoads());
    }

    @Test
    void multipleWorldsShareOneCounter() {
        DebugWorldCounters counters = new DebugWorldCounters();
        counters.setEnabled(true);
        counters.onLoad(false);
        counters.onLoad(true);
        counters.onUnload();
        DebugWorldCounters.Snapshot snapshot = counters.drain();
        assertEquals(2L, snapshot.chunkLoads());
        assertEquals(1L, snapshot.newChunkLoads());
        assertEquals(1L, snapshot.chunkUnloads());
        assertTrue(counters.enabled());
    }
}
