package dev.farmguard.hotspot;

import dev.farmguard.model.ChunkActivitySnapshot;
import dev.farmguard.model.ChunkKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Keeps the per-second analysis pass bounded. Currently restricted chunks
 * always stay in the working set so limits can still recover.
 */
public final class AnalysisBudget {

    private AnalysisBudget() {
    }

    public static List<ChunkActivitySnapshot> select(
            List<ChunkActivitySnapshot> snapshots,
            Set<ChunkKey> keep,
            int max
    ) {
        if (snapshots == null || snapshots.isEmpty()) {
            return List.of();
        }
        int cap = Math.max(1, max);
        if (snapshots.size() <= cap) {
            return snapshots;
        }
        List<ChunkActivitySnapshot> protectedOnes = new ArrayList<>();
        List<ChunkActivitySnapshot> rest = new ArrayList<>();
        for (ChunkActivitySnapshot snapshot : snapshots) {
            if (keep != null && keep.contains(snapshot.key())) {
                protectedOnes.add(snapshot);
            } else {
                rest.add(snapshot);
            }
        }
        rest.sort(Comparator.comparingDouble(ChunkActivitySnapshot::totalShortActivity).reversed());
        int room = Math.max(0, cap - protectedOnes.size());
        if (rest.size() > room) {
            rest = rest.subList(0, room);
        }
        List<ChunkActivitySnapshot> selected = new ArrayList<>(protectedOnes.size() + rest.size());
        selected.addAll(protectedOnes);
        selected.addAll(rest);
        return selected;
    }
}
