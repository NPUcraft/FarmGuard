package com.npucraft.farmguard.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.npucraft.farmguard.model.ChunkKey;
import com.npucraft.farmguard.model.ThrottleType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DebugActionCountersTest {

    @Test
    void disabledObserveIsNoOp() {
        DebugActionCounters counters = new DebugActionCounters();
        ChunkKey key = new ChunkKey(UUID.randomUUID(), "world", 1, 2);
        counters.observe(key, ThrottleType.HOPPER, true);
        assertTrue(counters.drain(10).isEmpty());
        assertEquals(0, counters.trackedChunks());
    }

    @Test
    void aggregatesAllowedAndSuppressedThenResets() {
        DebugActionCounters counters = new DebugActionCounters();
        counters.setEnabled(true);
        ChunkKey key = new ChunkKey(UUID.randomUUID(), "world", 120, -84);
        counters.observe(key, ThrottleType.HOPPER, false);
        counters.observe(key, ThrottleType.HOPPER, false);
        counters.observe(key, ThrottleType.HOPPER, true);
        counters.observe(key, ThrottleType.REDSTONE, false);
        List<DebugActionCounters.Window> windows = counters.drain(10);
        assertEquals(1, windows.size());
        DebugActionCounters.Window window = windows.get(0);
        assertEquals(2L, window.observed(ThrottleType.HOPPER));
        assertEquals(1L, window.suppressed(ThrottleType.HOPPER));
        assertEquals(1L, window.observed(ThrottleType.REDSTONE));
        assertEquals(0L, window.suppressed(ThrottleType.REDSTONE));
        assertTrue(counters.drain(10).isEmpty());
    }
}
