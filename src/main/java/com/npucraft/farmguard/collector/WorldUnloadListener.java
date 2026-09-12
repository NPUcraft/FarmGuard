package com.npucraft.farmguard.collector;

import com.npucraft.farmguard.metrics.ChunkMetricStore;
import com.npucraft.farmguard.protection.ProtectionManager;
import java.util.UUID;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldUnloadEvent;

public final class WorldUnloadListener implements Listener {

    private final ChunkMetricStore store;
    private final ProtectionManager protection;

    public WorldUnloadListener(ChunkMetricStore store, ProtectionManager protection) {
        this.store = store;
        this.protection = protection;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        UUID worldId = event.getWorld().getUID();
        store.evictWorld(worldId);
        protection.evictWorld(worldId);
    }
}
