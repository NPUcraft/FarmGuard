package com.npucraft.farmguard.metrics;

import java.util.Arrays;

/**
 * Fixed-size ring of time buckets. Old buckets are overwritten so history
 * cannot grow without bound. Callers must pass a monotonic clock.
 */
public final class RollingCounter {

    private final int bucketCount;
    private final long bucketDurationMs;
    private final long[] buckets;
    private int index;
    private long bucketStartMs;
    private long total;
    private boolean started;

    public RollingCounter(int bucketCount, long bucketDurationMs) {
        if (bucketCount < 1) {
            throw new IllegalArgumentException("bucketCount must be >= 1");
        }
        if (bucketDurationMs < 1L) {
            throw new IllegalArgumentException("bucketDurationMs must be >= 1");
        }
        this.bucketCount = bucketCount;
        this.bucketDurationMs = bucketDurationMs;
        this.buckets = new long[bucketCount];
    }

    public void add(long nowMs, long amount) {
        if (amount == 0L) {
            return;
        }
        advance(nowMs);
        buckets[index] += amount;
        total += amount;
    }

    public long sum(long nowMs) {
        advance(nowMs);
        return total;
    }

    public long sumLast(long nowMs, int bucketsToSum) {
        advance(nowMs);
        int n = Math.min(Math.max(bucketsToSum, 0), bucketCount);
        long sum = 0L;
        for (int i = 0; i < n; i++) {
            int idx = Math.floorMod(index - i, bucketCount);
            sum += buckets[idx];
        }
        return sum;
    }

    public double perSecond(long nowMs, int windowBuckets, int windowSeconds) {
        if (windowSeconds <= 0) {
            return 0.0;
        }
        return sumLast(nowMs, windowBuckets) / (double) windowSeconds;
    }

    public void reset() {
        Arrays.fill(buckets, 0L);
        index = 0;
        total = 0L;
        started = false;
        bucketStartMs = 0L;
    }

    int bucketCount() {
        return bucketCount;
    }

    private void advance(long nowMs) {
        if (!started) {
            started = true;
            bucketStartMs = nowMs;
            return;
        }
        if (nowMs < bucketStartMs) {
            return;
        }
        long elapsed = nowMs - bucketStartMs;
        int steps = (int) (elapsed / bucketDurationMs);
        if (steps <= 0) {
            return;
        }
        if (steps >= bucketCount) {
            Arrays.fill(buckets, 0L);
            total = 0L;
            index = 0;
            bucketStartMs = nowMs;
            return;
        }
        for (int i = 0; i < steps; i++) {
            index = (index + 1) % bucketCount;
            total -= buckets[index];
            if (total < 0L) {
                total = 0L;
            }
            buckets[index] = 0L;
        }
        bucketStartMs += (long) steps * bucketDurationMs;
    }
}
