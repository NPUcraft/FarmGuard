package dev.farmguard.collector;

import dev.farmguard.model.MetricType;
import dev.farmguard.util.ChunkKeys;
import org.bukkit.block.Hopper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Villager;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.vehicle.VehicleCreateEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.inventory.InventoryHolder;

/**
 * Collectors stay on the hot path: resolve a ChunkKey, increment, return.
 * Scoring, clustering, and protection decisions happen in the aggregation task.
 */
public final class ActivityListener implements Listener {

    private final ActivitySink sink;

    public ActivityListener(ActivitySink sink) {
        this.sink = sink;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRedstone(BlockRedstoneEvent event) {
        if (event.getOldCurrent() == event.getNewCurrent()) {
            return;
        }
        sink.record(ChunkKeys.of(event.getBlock()), MetricType.REDSTONE);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        sink.record(ChunkKeys.of(event.getBlock()), MetricType.PISTON);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        sink.record(ChunkKeys.of(event.getBlock()), MetricType.PISTON);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHopperMove(InventoryMoveItemEvent event) {
        InventoryHolder holder = event.getInitiator().getHolder();
        if (holder instanceof Hopper hopper) {
            sink.record(ChunkKeys.of(hopper.getBlock()), MetricType.HOPPER);
        } else if (holder instanceof HopperMinecart minecart) {
            sink.record(ChunkKeys.of(minecart), MetricType.HOPPER);
            sink.record(ChunkKeys.of(minecart), MetricType.MINECART);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        Entity entity = event.getEntity();
        sink.record(ChunkKeys.of(entity), MetricType.ENTITY_SPAWN);
        if (entity instanceof Villager) {
            sink.record(ChunkKeys.of(entity), MetricType.VILLAGER);
        }
        applyCensus(entity, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        Entity entity = event.getEntity();
        sink.record(ChunkKeys.of(entity), MetricType.ENTITY_DEATH);
        if (entity instanceof Villager) {
            sink.record(ChunkKeys.of(entity), MetricType.VILLAGER);
        }
        applyCensus(entity, -1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        sink.record(ChunkKeys.of(event.getEntity()), MetricType.ITEM);
        applyCensus(event.getEntity(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent event) {
        sink.record(ChunkKeys.of(event.getEntity()), MetricType.BREEDING);
        if (event.getEntity() instanceof Villager || event.getMother() instanceof Villager) {
            sink.record(ChunkKeys.of(event.getEntity()), MetricType.VILLAGER);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleCreate(VehicleCreateEvent event) {
        if (event.getVehicle() instanceof Minecart minecart) {
            sink.record(ChunkKeys.of(minecart), MetricType.MINECART);
            applyCensus(minecart, 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleDestroy(VehicleDestroyEvent event) {
        if (event.getVehicle() instanceof Minecart minecart) {
            applyCensus(minecart, -1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent event) {
        sink.record(ChunkKeys.of(event.getBlock()), MetricType.BLOCK_UPDATE);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        sink.record(ChunkKeys.of(event.getBlock()), MetricType.BLOCK_UPDATE);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFromTo(BlockFromToEvent event) {
        sink.record(ChunkKeys.of(event.getBlock()), MetricType.BLOCK_UPDATE);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        sink.record(ChunkKeys.of(event.getBlock()), MetricType.BLOCK_UPDATE);
    }

    /**
     * Census is updated only from activity events plus periodic hotspot sampling.
     * Listening to chunk entity-load would insert every populated chunk into the
     * metric map and defeat TTL eviction.
     */
    private void applyCensus(Entity entity, int delta) {
        int villagers = 0;
        int minecarts = 0;
        int items = 0;
        int living = 0;
        int others = 0;
        if (entity instanceof Item) {
            items = delta;
        } else if (entity instanceof Villager) {
            villagers = delta;
        } else if (entity instanceof Minecart) {
            minecarts = delta;
        } else if (entity instanceof LivingEntity) {
            living = delta;
        } else {
            others = delta;
        }
        sink.adjustCensus(ChunkKeys.of(entity), villagers, minecarts, items, living, others);
    }
}
