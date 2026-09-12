package com.npucraft.farmguard.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable chunk identity. Stores world UUID rather than a World reference
 * so unloaded worlds cannot leak Bukkit objects into long-lived maps.
 */
public final class ChunkKey {

    private final UUID worldId;
    private final String worldName;
    private final int x;
    private final int z;
    private final int hash;

    public ChunkKey(UUID worldId, String worldName, int x, int z) {
        this.worldId = Objects.requireNonNull(worldId, "worldId");
        this.worldName = worldName == null || worldName.isBlank() ? worldId.toString() : worldName;
        this.x = x;
        this.z = z;
        this.hash = Objects.hash(this.worldId, this.x, this.z);
    }

    public UUID worldId() {
        return worldId;
    }

    public String worldName() {
        return worldName;
    }

    public int x() {
        return x;
    }

    public int z() {
        return z;
    }

    public boolean isNeighbor(ChunkKey other, int chebyshevRange) {
        if (other == null || !worldId.equals(other.worldId)) {
            return false;
        }
        int dx = Math.abs(x - other.x);
        int dz = Math.abs(z - other.z);
        return Math.max(dx, dz) <= chebyshevRange && (dx + dz) > 0;
    }

    public String display() {
        return worldName + " (" + x + "," + z + ")";
    }

    public String compact() {
        return worldName + "," + x + "," + z;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof ChunkKey other)) {
            return false;
        }
        return x == other.x && z == other.z && worldId.equals(other.worldId);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        return display();
    }
}
