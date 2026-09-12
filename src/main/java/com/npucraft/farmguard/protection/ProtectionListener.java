package dev.farmguard.protection;

import dev.farmguard.FarmGuardPlugin;
import dev.farmguard.config.FarmGuardSettings;
import dev.farmguard.model.ChunkKey;
import dev.farmguard.model.ThrottleType;
import dev.farmguard.util.ChunkKeys;
import org.bukkit.block.Hopper;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.vehicle.VehicleCreateEvent;
import org.bukkit.inventory.InventoryHolder;

/**
 * All mutation goes through {@link ProtectionManager#shouldThrottle}.
 * That gate is false whenever mode is MONITOR or restrictions were released.
 */
public final class ProtectionListener implements Listener {

    private final FarmGuardPlugin plugin;
    private final ProtectionManager protection;

    public ProtectionListener(FarmGuardPlugin plugin, ProtectionManager protection) {
        this.plugin = plugin;
        this.protection = protection;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHopperMove(InventoryMoveItemEvent event) {
        InventoryHolder holder = event.getInitiator().getHolder();
        ChunkKey key = null;
        if (holder instanceof Hopper hopper) {
            key = ChunkKeys.of(hopper.getBlock());
        } else if (holder instanceof HopperMinecart minecart) {
            key = ChunkKeys.of(minecart);
        }
        if (throttled(key, ThrottleType.HOPPER)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (throttled(ChunkKeys.of(event.getBlock()), ThrottleType.PISTON)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (throttled(ChunkKeys.of(event.getBlock()), ThrottleType.PISTON)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRedstone(BlockRedstoneEvent event) {
        if (throttled(ChunkKeys.of(event.getBlock()), ThrottleType.REDSTONE)) {
            event.setNewCurrent(event.getOldCurrent());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        // Default reasons: SPAWNER, TRIAL_SPAWNER, SLIME_SPLIT.
        // NATURAL, COMMAND, CUSTOM, RAID, BREEDING, SPAWNER_EGG and similar
        // player/plugin/event spawns are not throttled unless configured.
        FarmGuardSettings settings = plugin.runtime().settings();
        if (!settings.spawnReasonThrottled(event.getSpawnReason().name())) {
            return;
        }
        if (throttled(ChunkKeys.of(event.getEntity()), ThrottleType.SPAWN)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent event) {
        if (event.getBreeder() instanceof Player player && player.hasPermission("farmguard.bypass")) {
            return;
        }
        if (throttled(ChunkKeys.of(event.getEntity()), ThrottleType.BREEDING)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (event.getEntity() instanceof Item item && (item.getThrower() != null || item.getOwner() != null)) {
            return;
        }
        if (throttled(ChunkKeys.of(event.getEntity()), ThrottleType.ITEM)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVehicleCreate(VehicleCreateEvent event) {
        if (throttled(ChunkKeys.of(event.getVehicle()), ThrottleType.MINECART)) {
            event.setCancelled(true);
        }
    }

    private boolean throttled(ChunkKey key, ThrottleType type) {
        if (key == null) {
            return false;
        }
        FarmGuardSettings settings = plugin.runtime().settings();
        return protection.shouldThrottle(key, type, settings, System.currentTimeMillis());
    }
}
