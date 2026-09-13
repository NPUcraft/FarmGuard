package com.npucraft.farmguard.debug;

import com.npucraft.farmguard.model.ChunkKey;
import com.npucraft.farmguard.model.RiskLevel;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregates low-level RiskLevel flaps for debug.log. Does not change RiskEngine.
 */
public final class RiskTransitionAggregator {

    public static final long WINDOW_MS = 30_000L;
    public static final int DEFAULT_CAP = 512;

    private final Map<ChunkKey, Acc> pending = new LinkedHashMap<>();
    private int maxEntries;
    private long dropped;

    public RiskTransitionAggregator() {
        this(DEFAULT_CAP);
    }

    public RiskTransitionAggregator(int maxEntries) {
        this.maxEntries = Math.max(16, maxEntries);
    }

    public void setMaxEntries(int maxEntries) {
        this.maxEntries = Math.max(16, maxEntries);
        while (pending.size() > this.maxEntries) {
            Iterator<ChunkKey> keys = pending.keySet().iterator();
            if (!keys.hasNext()) {
                break;
            }
            keys.next();
            keys.remove();
            dropped++;
        }
    }

    public static boolean isImmediate(RiskLevel from, RiskLevel to) {
        return high(from) || high(to);
    }

    public Decision record(
            ChunkKey key,
            RiskLevel from,
            RiskLevel to,
            double riskScore,
            double activityScore,
            long nowMs
    ) {
        if (key == null) {
            return Decision.none();
        }
        RiskLevel previous = from == null ? RiskLevel.NONE : from;
        RiskLevel next = to == null ? RiskLevel.NONE : to;
        List<Summary> flushed = new ArrayList<>();
        if (isImmediate(previous, next)) {
            Acc existing = pending.remove(key);
            if (existing != null) {
                flushed.add(existing.toSummary(nowMs));
            }
            return new Decision(true, flushed);
        }
        Acc existing = pending.get(key);
        if (existing == null) {
            if (pending.size() >= maxEntries) {
                dropped++;
                return Decision.none();
            }
            pending.put(key, Acc.start(key, previous, next, riskScore, activityScore, nowMs));
            return Decision.none();
        }
        existing.add(previous, next, riskScore, activityScore);
        if (nowMs - existing.windowStartMs >= WINDOW_MS) {
            flushed.add(existing.toSummary(nowMs));
            pending.remove(key);
        }
        return new Decision(false, flushed);
    }

    public List<Summary> pollDue(long nowMs) {
        List<Summary> due = new ArrayList<>();
        Iterator<Map.Entry<ChunkKey, Acc>> iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            Acc acc = iterator.next().getValue();
            if (nowMs - acc.windowStartMs >= WINDOW_MS) {
                due.add(acc.toSummary(nowMs));
                iterator.remove();
            }
        }
        return due;
    }

    public List<Summary> flushAll(long nowMs) {
        List<Summary> all = new ArrayList<>(pending.size());
        for (Acc acc : pending.values()) {
            all.add(acc.toSummary(nowMs));
        }
        pending.clear();
        return all;
    }

    public void clear() {
        pending.clear();
    }

    public int size() {
        return pending.size();
    }

    public long dropped() {
        return dropped;
    }

    public boolean isEmpty() {
        return pending.isEmpty();
    }

    private static boolean high(RiskLevel level) {
        return level != null && level.atLeast(RiskLevel.HIGH);
    }

    public record Decision(boolean writeImmediate, List<Summary> flushed) {
        static Decision none() {
            return new Decision(false, List.of());
        }
    }

    public record Summary(
            ChunkKey chunk,
            int windowSeconds,
            int transitionCount,
            RiskLevel firstLevel,
            RiskLevel lastLevel,
            RiskLevel highestLevel,
            double minRiskScore,
            double maxRiskScore,
            double activityMin,
            double activityMax
    ) {
    }

    private static final class Acc {
        private final ChunkKey chunk;
        private final long windowStartMs;
        private final RiskLevel firstLevel;
        private RiskLevel lastLevel;
        private RiskLevel highestLevel;
        private int transitionCount;
        private double minRiskScore;
        private double maxRiskScore;
        private double activityMin;
        private double activityMax;

        private Acc(
                ChunkKey chunk,
                long windowStartMs,
                RiskLevel firstLevel,
                RiskLevel lastLevel,
                RiskLevel highestLevel,
                int transitionCount,
                double minRiskScore,
                double maxRiskScore,
                double activityMin,
                double activityMax
        ) {
            this.chunk = chunk;
            this.windowStartMs = windowStartMs;
            this.firstLevel = firstLevel;
            this.lastLevel = lastLevel;
            this.highestLevel = highestLevel;
            this.transitionCount = transitionCount;
            this.minRiskScore = minRiskScore;
            this.maxRiskScore = maxRiskScore;
            this.activityMin = activityMin;
            this.activityMax = activityMax;
        }

        static Acc start(ChunkKey chunk, RiskLevel from, RiskLevel to, double riskScore, double activity, long nowMs) {
            return new Acc(
                    chunk,
                    nowMs,
                    from,
                    to,
                    higher(from, to),
                    1,
                    riskScore,
                    riskScore,
                    activity,
                    activity
            );
        }

        void add(RiskLevel from, RiskLevel to, double riskScore, double activity) {
            transitionCount++;
            lastLevel = to;
            highestLevel = higher(highestLevel, higher(from, to));
            minRiskScore = Math.min(minRiskScore, riskScore);
            maxRiskScore = Math.max(maxRiskScore, riskScore);
            activityMin = Math.min(activityMin, activity);
            activityMax = Math.max(activityMax, activity);
        }

        Summary toSummary(long nowMs) {
            int seconds = (int) Math.max(1L, Math.round((nowMs - windowStartMs) / 1000.0));
            return new Summary(
                    chunk,
                    seconds,
                    transitionCount,
                    firstLevel,
                    lastLevel,
                    highestLevel,
                    minRiskScore,
                    maxRiskScore,
                    activityMin,
                    activityMax
            );
        }

        private static RiskLevel higher(RiskLevel a, RiskLevel b) {
            RiskLevel left = a == null ? RiskLevel.NONE : a;
            RiskLevel right = b == null ? RiskLevel.NONE : b;
            return left.ordinal() >= right.ordinal() ? left : right;
        }
    }
}
