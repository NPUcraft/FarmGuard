package dev.farmguard.hotspot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.farmguard.TestData;
import dev.farmguard.model.ChunkActivitySnapshot;
import dev.farmguard.model.ChunkKey;
import dev.farmguard.model.MetricType;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AnalysisBudgetTest {

    @Test
    void keepsRestrictedChunksAndHottestRemainder() {
        List<ChunkActivitySnapshot> snapshots = new ArrayList<>();
        ChunkKey restricted = TestData.chunk(0, 0);
        snapshots.add(TestData.snapshotRates(restricted, MetricType.HOPPER, 1.0, 1.0));
        for (int i = 1; i <= 20; i++) {
            snapshots.add(TestData.snapshotRates(TestData.chunk(i, 0), MetricType.HOPPER, i, i));
        }
        List<ChunkActivitySnapshot> selected = AnalysisBudget.select(snapshots, Set.of(restricted), 5);
        assertEquals(5, selected.size());
        assertTrue(selected.stream().anyMatch(snapshot -> snapshot.key().equals(restricted)));
    }
}
