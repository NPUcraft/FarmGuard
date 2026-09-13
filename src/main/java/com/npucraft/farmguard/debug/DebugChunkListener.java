package com.npucraft.farmguard.debug;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

/**
 * Debug-only chunk load/unload counters. Does not scan World or Chunk contents.
 */
public final class DebugChunkListener implements Listener {

    private final DebugWorldCounters counters;

    public DebugChunkListener(DebugWorldCounters counters) {
        this.counters = counters;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!counters.enabled()) {
            return;
        }
        counters.onLoad(event.isNewChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent event) {
        if (!counters.enabled()) {
            return;
        }
        counters.onUnload();
    }
}
