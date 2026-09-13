package com.npucraft.farmguard.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.npucraft.farmguard.TestData;
import com.npucraft.farmguard.model.ChunkKey;
import com.npucraft.farmguard.model.RiskLevel;
import java.util.List;
import org.junit.jupiter.api.Test;

class RiskTransitionAggregatorTest {

    @Test
    void noneLowFlappingIsAggregated() {
        RiskTransitionAggregator aggregator = new RiskTransitionAggregator();
        ChunkKey key = TestData.chunk(9, 34);
        long start = 1_000L;
        for (int i = 0; i < 18; i++) {
            RiskLevel from = i % 2 == 0 ? RiskLevel.NONE : RiskLevel.LOW;
            RiskLevel to = i % 2 == 0 ? RiskLevel.LOW : RiskLevel.NONE;
            RiskTransitionAggregator.Decision decision = aggregator.record(key, from, to, 8.1 + i, 20.2 + i, start + i * 1_000L);
            assertFalse(decision.writeImmediate());
        }
        assertEquals(1, aggregator.size());
        List<RiskTransitionAggregator.Summary> due = aggregator.pollDue(start + RiskTransitionAggregator.WINDOW_MS);
        assertEquals(1, due.size());
        assertEquals(18, due.get(0).transitionCount());
        assertEquals(RiskLevel.NONE, due.get(0).firstLevel());
        assertEquals(RiskLevel.NONE, due.get(0).lastLevel());
        assertEquals(RiskLevel.LOW, due.get(0).highestLevel());
        assertEquals(8.1, due.get(0).minRiskScore(), 0.0001);
        assertEquals(8.1 + 17, due.get(0).maxRiskScore(), 0.0001);
        assertEquals(20.2, due.get(0).activityMin(), 0.0001);
        assertEquals(20.2 + 17, due.get(0).activityMax(), 0.0001);
        assertTrue(aggregator.isEmpty());
    }

    @Test
    void lowMediumFlappingKeepsHighestMedium() {
        RiskTransitionAggregator aggregator = new RiskTransitionAggregator();
        ChunkKey key = TestData.chunk(2, 2);
        long start = 5_000L;
        for (int i = 0; i < 10; i++) {
            RiskLevel from = i % 2 == 0 ? RiskLevel.LOW : RiskLevel.MEDIUM;
            RiskLevel to = i % 2 == 0 ? RiskLevel.MEDIUM : RiskLevel.LOW;
            aggregator.record(key, from, to, 12.0 + i, 30.0 + i, start);
        }
        List<RiskTransitionAggregator.Summary> due = aggregator.pollDue(start + RiskTransitionAggregator.WINDOW_MS);
        assertEquals(1, due.size());
        assertEquals(10, due.get(0).transitionCount());
        assertEquals(RiskLevel.MEDIUM, due.get(0).highestLevel());
        assertEquals(RiskLevel.LOW, due.get(0).lastLevel());
    }

    @Test
    void highTransitionIsImmediateAndFlushesPending() {
        RiskTransitionAggregator aggregator = new RiskTransitionAggregator();
        ChunkKey key = TestData.chunk(1, 1);
        aggregator.record(key, RiskLevel.NONE, RiskLevel.LOW, 9.0, 22.0, 0L);
        aggregator.record(key, RiskLevel.LOW, RiskLevel.MEDIUM, 20.0, 40.0, 1_000L);
        RiskTransitionAggregator.Decision decision = aggregator.record(key, RiskLevel.MEDIUM, RiskLevel.HIGH, 55.0, 70.0, 2_000L);
        assertTrue(decision.writeImmediate());
        assertEquals(1, decision.flushed().size());
        assertEquals(2, decision.flushed().get(0).transitionCount());
        assertEquals(RiskLevel.MEDIUM, decision.flushed().get(0).highestLevel());
        assertTrue(aggregator.isEmpty());
    }

    @Test
    void criticalTransitionIsImmediate() {
        RiskTransitionAggregator aggregator = new RiskTransitionAggregator();
        ChunkKey key = TestData.chunk(3, 3);
        RiskTransitionAggregator.Decision decision = aggregator.record(key, RiskLevel.LOW, RiskLevel.CRITICAL, 90.0, 80.0, 0L);
        assertTrue(decision.writeImmediate());
        assertTrue(decision.flushed().isEmpty());
    }

    @Test
    void highToLowIsStillImmediate() {
        RiskTransitionAggregator aggregator = new RiskTransitionAggregator();
        ChunkKey key = TestData.chunk(4, 4);
        RiskTransitionAggregator.Decision decision = aggregator.record(key, RiskLevel.HIGH, RiskLevel.MEDIUM, 30.0, 20.0, 0L);
        assertTrue(decision.writeImmediate());
    }

    @Test
    void flushAllClearsPending() {
        RiskTransitionAggregator aggregator = new RiskTransitionAggregator();
        aggregator.record(TestData.chunk(1, 1), RiskLevel.NONE, RiskLevel.LOW, 8.0, 10.0, 0L);
        List<RiskTransitionAggregator.Summary> flushed = aggregator.flushAll(1_000L);
        assertEquals(1, flushed.size());
        assertTrue(aggregator.isEmpty());
        assertTrue(aggregator.flushAll(2_000L).isEmpty());
    }

    @Test
    void capacityDropsExtraChunks() {
        RiskTransitionAggregator aggregator = new RiskTransitionAggregator(16);
        for (int i = 0; i < 20; i++) {
            aggregator.record(TestData.chunk(i, 0), RiskLevel.NONE, RiskLevel.LOW, 8.0, 10.0, 0L);
        }
        assertEquals(16, aggregator.size());
        assertEquals(4, aggregator.dropped());
    }

    @Test
    void debugOffClearDoesNotKeepEntries() {
        RiskTransitionAggregator aggregator = new RiskTransitionAggregator();
        aggregator.record(TestData.chunk(0, 0), RiskLevel.NONE, RiskLevel.LOW, 8.0, 10.0, 0L);
        aggregator.clear();
        assertTrue(aggregator.isEmpty());
    }
}
