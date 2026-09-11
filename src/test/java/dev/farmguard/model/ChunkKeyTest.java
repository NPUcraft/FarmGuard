package dev.farmguard.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChunkKeyTest {

    @Test
    void equalsAndHashCodeIgnoreWorldName() {
        UUID world = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        ChunkKey a = new ChunkKey(world, "world", 12, -8);
        ChunkKey b = new ChunkKey(world, "renamed", 12, -8);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        Set<ChunkKey> set = new HashSet<>();
        set.add(a);
        assertTrue(set.contains(b));
    }

    @Test
    void differentCoordinatesAreNotEqual() {
        UUID world = UUID.randomUUID();
        ChunkKey a = new ChunkKey(world, "world", 1, 1);
        ChunkKey b = new ChunkKey(world, "world", 1, 2);
        assertNotEquals(a, b);
    }

    @Test
    void neighborUsesChebyshevDistance() {
        UUID world = UUID.randomUUID();
        ChunkKey origin = new ChunkKey(world, "world", 0, 0);
        assertTrue(origin.isNeighbor(new ChunkKey(world, "world", 1, 1), 1));
        assertFalse(origin.isNeighbor(new ChunkKey(world, "world", 2, 0), 1));
        assertFalse(origin.isNeighbor(origin, 1));
        assertFalse(origin.isNeighbor(new ChunkKey(UUID.randomUUID(), "other", 1, 0), 1));
    }
}
