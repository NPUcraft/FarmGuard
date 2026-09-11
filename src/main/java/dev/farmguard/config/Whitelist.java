package dev.farmguard.config;

import dev.farmguard.model.AutomationCluster;
import dev.farmguard.model.ChunkKey;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class Whitelist {

    private final Set<ChunkKey> chunks = new LinkedHashSet<>();
    private final Set<String> clusterIds = new LinkedHashSet<>();

    public boolean contains(ChunkKey key) {
        if (key == null) {
            return false;
        }
        for (ChunkKey listed : chunks) {
            if (listed.equals(key) || sameChunk(listed, key)) {
                return true;
            }
        }
        return false;
    }

    public boolean add(ChunkKey key) {
        if (key == null || contains(key)) {
            return false;
        }
        return chunks.add(key);
    }

    public boolean remove(ChunkKey key) {
        if (key == null) {
            return false;
        }
        return chunks.removeIf(listed -> listed.equals(key) || sameChunk(listed, key));
    }

    public List<ChunkKey> chunks() {
        return List.copyOf(chunks);
    }

    public Set<String> clusterIds() {
        return Set.copyOf(clusterIds);
    }

    public boolean addCluster(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        return clusterIds.add(id);
    }

    public boolean removeCluster(String id) {
        return clusterIds.remove(id);
    }

    public boolean clusterListed(String id) {
        return id != null && clusterIds.contains(id);
    }

    /**
     * Whitelist means "do not auto-restrict". Monitoring, risk, top, and
     * incidents still continue. A listed cluster or any member chunk exempts
     * the whole current cluster.
     */
    public boolean exemptFromProtection(ChunkKey key, AutomationCluster cluster) {
        if (contains(key)) {
            return true;
        }
        if (cluster == null || !cluster.chunks().contains(key)) {
            return false;
        }
        if (clusterListed(cluster.id())) {
            return true;
        }
        for (ChunkKey member : cluster.chunks()) {
            if (contains(member)) {
                return true;
            }
        }
        return false;
    }

    public void replace(List<ChunkKey> nextChunks, List<String> nextClusters) {
        chunks.clear();
        clusterIds.clear();
        if (nextChunks != null) {
            chunks.addAll(nextChunks);
        }
        if (nextClusters != null) {
            clusterIds.addAll(nextClusters);
        }
    }

    public static ChunkKey parseChunk(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.split(",");
        try {
            if (parts.length >= 4) {
                String world = parts[0].trim();
                UUID uuid = UUID.fromString(parts[1].trim());
                int x = Integer.parseInt(parts[2].trim());
                int z = Integer.parseInt(parts[3].trim());
                return new ChunkKey(uuid, world, x, z);
            }
            if (parts.length == 3) {
                String world = parts[0].trim();
                int x = Integer.parseInt(parts[1].trim());
                int z = Integer.parseInt(parts[2].trim());
                UUID uuid = UUID.nameUUIDFromBytes(("farmguard-world:" + world).getBytes());
                return new ChunkKey(uuid, world, x, z);
            }
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        return null;
    }

    public static String formatChunk(ChunkKey key) {
        return key.worldName() + "," + key.worldId() + "," + key.x() + "," + key.z();
    }

    private static boolean sameChunk(ChunkKey left, ChunkKey right) {
        return left.x() == right.x()
                && left.z() == right.z()
                && left.worldName().equalsIgnoreCase(right.worldName());
    }
}
