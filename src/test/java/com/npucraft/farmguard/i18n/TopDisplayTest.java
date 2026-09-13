package com.npucraft.farmguard.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.npucraft.farmguard.TestData;
import com.npucraft.farmguard.cluster.ClusterManager;
import com.npucraft.farmguard.config.FarmGuardSettings;
import com.npucraft.farmguard.model.MetricType;
import com.npucraft.farmguard.model.RiskAssessment;
import com.npucraft.farmguard.model.RiskLevel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TopDisplayTest {

    @Test
    void sameClusterAppearsOnce() {
        ClusterManager clusters = new ClusterManager();
        RiskAssessment a = hopper(0, 0, 40);
        RiskAssessment b = hopper(0, 1, 39);
        clusters.refresh(List.of(a, b), Map.of(), FarmGuardSettings.defaults());
        List<TopDisplay.Entry> entries = TopDisplay.of(List.of(a, b), clusters.current(), List.of(), 10);
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).clustered());
        assertEquals(2, entries.get(0).memberChunks());
        assertEquals(clusters.current().get(0).id(), entries.get(0).cluster().id());
    }

    @Test
    void standaloneHotspotIsKept() {
        RiskAssessment alone = hopper(40, 40, 30);
        List<TopDisplay.Entry> entries = TopDisplay.of(List.of(alone), List.of(), List.of(), 10);
        assertEquals(1, entries.size());
        assertFalse(entries.get(0).clustered());
        assertEquals(alone.chunk(), entries.get(0).location());
    }

    @Test
    void mixedClusterStandaloneClusterEachTakeOneRank() {
        ClusterManager clusters = new ClusterManager();
        RiskAssessment a1 = hopper(0, 0, 50);
        RiskAssessment a2 = hopper(0, 1, 49);
        RiskAssessment standalone = quiet(80, 80, 12);
        RiskAssessment c1 = hopper(20, 20, 40);
        RiskAssessment c2 = hopper(20, 21, 39);
        RiskAssessment c3 = hopper(21, 20, 38);
        List<RiskAssessment> ranked = List.of(a1, a2, standalone, c1, c2, c3);
        clusters.refresh(ranked, Map.of(), FarmGuardSettings.defaults());
        List<TopDisplay.Entry> entries = TopDisplay.of(ranked, clusters.current(), List.of(), 10);
        assertEquals(3, entries.size());
        assertTrue(entries.get(0).clustered());
        assertFalse(entries.get(1).clustered());
        assertEquals(standalone.chunk(), entries.get(1).location());
        assertTrue(entries.get(2).clustered());
        assertEquals(3, entries.get(2).memberChunks());
        assertEquals(clusters.find(a1.chunk()).id(), entries.get(0).cluster().id());
        assertEquals(clusters.find(c1.chunk()).id(), entries.get(2).cluster().id());
    }

    @Test
    void limitCountsDisplayEntriesNotRawChunks() {
        ClusterManager clusters = new ClusterManager();
        List<RiskAssessment> ranked = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            ranked.add(hopper(i * 10, 0, 50 - i));
            ranked.add(hopper(i * 10, 1, 49 - i));
        }
        clusters.refresh(ranked, Map.of(), FarmGuardSettings.defaults());
        List<TopDisplay.Entry> entries = TopDisplay.of(ranked, clusters.current(), List.of(), 5);
        assertEquals(5, entries.size());
        for (TopDisplay.Entry entry : entries) {
            assertTrue(entry.clustered());
            assertEquals(2, entry.memberChunks());
        }
    }

    @Test
    void clusterRankFollowsFirstHotspotAppearance() {
        ClusterManager clusters = new ClusterManager();
        RiskAssessment lateMember = hopper(0, 1, 60);
        RiskAssessment first = hopper(0, 0, 20);
        RiskAssessment standalone = hopper(40, 40, 50);
        List<RiskAssessment> ranked = List.of(lateMember, standalone, first);
        clusters.refresh(List.of(lateMember, first, standalone), Map.of(), FarmGuardSettings.defaults());
        List<TopDisplay.Entry> entries = TopDisplay.of(ranked, clusters.current(), List.of(), 10);
        assertEquals(2, entries.size());
        assertEquals(clusters.find(lateMember.chunk()).id(), entries.get(0).cluster().id());
        assertEquals(standalone.chunk(), entries.get(1).location());
    }

    @Test
    void originUsesStableMinCoordinate() {
        ClusterManager clusters = new ClusterManager();
        RiskAssessment a = hopper(2, 2, 40);
        RiskAssessment b = hopper(1, 3, 41);
        clusters.refresh(List.of(a, b), Map.of(), FarmGuardSettings.defaults());
        List<TopDisplay.Entry> entries = TopDisplay.of(List.of(b, a), clusters.current(), List.of(), 10);
        assertEquals(1, entries.size());
        assertEquals(1, entries.get(0).location().x());
        assertEquals(3, entries.get(0).location().z());
        assertNull(TopDisplay.of(List.of(), List.of(), List.of(), 10).stream().findFirst().orElse(null));
    }

    private static RiskAssessment hopper(int x, int z, double score) {
        var key = TestData.chunk(x, z);
        return TestData.risk(key, RiskLevel.HIGH, score, TestData.snapshotRates(key, MetricType.HOPPER, 80.0, 70.0));
    }

    private static RiskAssessment quiet(int x, int z, double score) {
        var key = TestData.chunk(x, z);
        return TestData.risk(key, RiskLevel.LOW, score, TestData.snapshotRates(key, MetricType.HOPPER, 4.0, 3.5));
    }
}
