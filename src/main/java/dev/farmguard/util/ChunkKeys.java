package dev.farmguard.util;

import dev.farmguard.model.ChunkKey;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;

public final class ChunkKeys {

    private ChunkKeys() {
    }

    public static ChunkKey of(World world, int chunkX, int chunkZ) {
        if (world == null) {
            return null;
        }
        return new ChunkKey(world.getUID(), world.getName(), chunkX, chunkZ);
    }

    public static ChunkKey of(Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        return of(location.getWorld(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    public static ChunkKey of(Block block) {
        if (block == null) {
            return null;
        }
        return of(block.getWorld(), block.getX() >> 4, block.getZ() >> 4);
    }

    public static ChunkKey of(Entity entity) {
        if (entity == null) {
            return null;
        }
        return of(entity.getLocation());
    }

    public static ChunkKey of(Chunk chunk) {
        if (chunk == null) {
            return null;
        }
        return of(chunk.getWorld(), chunk.getX(), chunk.getZ());
    }
}
