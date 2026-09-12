package dev.farmguard.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.farmguard.TestData;
import dev.farmguard.config.FarmGuardSettings;
import dev.farmguard.model.ChunkKey;
import dev.farmguard.model.ClusterType;
import dev.farmguard.model.LagCorrelation;
import dev.farmguard.model.MetricType;
import dev.farmguard.model.RiskAssessment;
import dev.farmguard.model.RiskLevel;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClusterManagerTest {

    @Test
    void adjacentHotChunksMerge() {
        ClusterManager manager = new ClusterManager();
        FarmGuardSettings settings = FarmGuardSettings.defaults();
        RiskAssessment a = assessment(TestData.chunk(10, 10), 40, RiskLevel.HIGH);
        RiskAssessment b = assessment(TestData.chunk(10, 11), 38, RiskLevel.HIGH);
        RiskAssessment far = assessment(TestData.chunk(40, 40), 38, RiskLevel.HIGH);
        manager.refresh(List.of(a, b, far), Map.of(), settings);
        assertEquals(2, manager.current().size());
        assertNotNull(manager.find(a.chunk()));
        assertEquals(manager.find(a.chunk()), manager.find(b.chunk()));
        assertNull(manager.find(new ChunkKey(a.chunk().worldId(), "world", 99, 99)));
    }

    @Test
    void uncertainMixStaysUnknownAutomation() {
        FarmGuardSettings settings = FarmGuardSettings.builder().classificationMargin(0.5).build();
        RiskAssessment mixed = new RiskAssessment(
                TestData.chunk(1, 1),
                30,
                40,
                1.0,
                RiskLevel.MEDIUM,
                LagCorrelation.NONE,
                List.of(),
                List.of(),
                TestData.mixed(TestData.chunk(1, 1), 20, 20, 20, 20, 20, 20)
        );
        ClusterType type = ClusterManager.classify(List.of(mixed), settings);
        assertEquals(ClusterType.UNKNOWN_AUTOMATION, type);
    }

    @Test
    void hopperDominantClassifiesAsStorage() {
        FarmGuardSettings settings = FarmGuardSettings.builder().classificationMargin(0.05).build();
        var snapshot = TestData.snapshotRates(TestData.chunk(2, 2), MetricType.HOPPER, 80.0, 70.0);
        RiskAssessment storage = new RiskAssessment(snapshot.key(), 40, 40, 1.0, RiskLevel.MEDIUM, LagCorrelation.NONE, List.of(), List.of(), snapshot);
        assertEquals(ClusterType.STORAGE, ClusterManager.classify(List.of(storage), settings));
    }

    @Test
    void adjacentHopperAndRedstoneStaySeparate() {
        ClusterManager manager = new ClusterManager();
        FarmGuardSettings settings = FarmGuardSettings.defaults();
        RiskAssessment hopper = TestData.risk(
                TestData.chunk(8, 8),
                RiskLevel.HIGH,
                40,
                TestData.snapshotRates(TestData.chunk(8, 8), MetricType.HOPPER, 80.0, 70.0)
        );
        RiskAssessment redstone = TestData.risk(
                TestData.chunk(8, 9),
                RiskLevel.HIGH,
                40,
                TestData.snapshotRates(TestData.chunk(8, 9), MetricType.REDSTONE, 90.0, 80.0)
        );
        manager.refresh(List.of(hopper, redstone), Map.of(), settings);
        assertEquals(2, manager.current().size());
        assertTrue(manager.find(hopper.chunk()) != manager.find(redstone.chunk()));
    }

    @Test
    void fourAdjacentHopperChunksFormOneClusterWithStableId() {
        ClusterManager manager = new ClusterManager();
        FarmGuardSettings settings = FarmGuardSettings.defaults();
        List<RiskAssessment> members = List.of(
                hopper(0, 0),
                hopper(0, 1),
                hopper(1, 0),
                hopper(1, 1)
        );
        manager.refresh(members, Map.of(), settings);
        assertEquals(1, manager.current().size());
        String id = manager.current().get(0).id();
        manager.refresh(members, Map.of(), settings);
        assertEquals(id, manager.current().get(0).id());
        assertEquals(4, manager.current().get(0).chunks().size());
    }

    @Test
    void inactiveClustersExpire() {
        ClusterManager manager = new ClusterManager();
        FarmGuardSettings settings = FarmGuardSettings.defaults();
        manager.refresh(List.of(hopper(2, 2)), Map.of(), settings);
        assertEquals(1, manager.current().size());
        RiskAssessment idle = TestData.risk(
                TestData.chunk(2, 2),
                RiskLevel.NONE,
                0,
                TestData.snapshotRates(TestData.chunk(2, 2), MetricType.HOPPER, 0.0, 0.0)
        );
        manager.refresh(List.of(idle), Map.of(), settings);
        assertEquals(0, manager.current().size());
    }

    private static RiskAssessment hopper(int x, int z) {
        var key = TestData.chunk(x, z);
        return TestData.risk(key, RiskLevel.HIGH, 40, TestData.snapshotRates(key, MetricType.HOPPER, 80.0, 70.0));
    }

    private static RiskAssessment assessment(ChunkKey key, double score, RiskLevel level) {
        var snapshot = TestData.snapshot(key, 50, 8, 20, 10);
        return new RiskAssessment(key, score, score, 1.0, level, LagCorrelation.NONE, List.of(), List.of(), snapshot);
    }
}
