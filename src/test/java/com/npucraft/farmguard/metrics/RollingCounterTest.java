package dev.farmguard.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RollingCounterTest {

    @Test
    void sumsOnlyTheRequestedWindow() {
        RollingCounter counter = new RollingCounter(5, 1000L);
        long t = 10_000L;
        counter.add(t, 10);
        counter.add(t + 1000L, 20);
        counter.add(t + 2000L, 30);
        assertEquals(60L, counter.sum(t + 2000L));
        assertEquals(50L, counter.sumLast(t + 2000L, 2));
        assertEquals(30.0, counter.perSecond(t + 2000L, 1, 1));
    }

    @Test
    void expiresOldBucketsInsteadOfGrowing() {
        RollingCounter counter = new RollingCounter(3, 1000L);
        long t = 0L;
        counter.add(t, 5);
        counter.add(t + 1000L, 5);
        counter.add(t + 2000L, 5);
        counter.add(t + 3000L, 7);
        assertEquals(17L, counter.sum(t + 3000L));
        assertEquals(7L, counter.sumLast(t + 3000L, 1));
    }

    @Test
    void largeTimeJumpClearsHistory() {
        RollingCounter counter = new RollingCounter(4, 1000L);
        counter.add(0L, 40);
        assertEquals(0L, counter.sum(10_000L));
    }
}
