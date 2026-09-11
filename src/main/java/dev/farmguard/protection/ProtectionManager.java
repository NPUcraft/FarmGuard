package dev.farmguard.protection;

import dev.farmguard.config.FarmGuardSettings;
import dev.farmguard.model.ChunkKey;
import dev.farmguard.model.LagCorrelation;
import dev.farmguard.model.OperatingMode;
import dev.farmguard.model.ProtectionLevel;
import dev.farmguard.model.ProtectionState;
import dev.farmguard.model.RiskAssessment;
import dev.farmguard.model.RiskLevel;
import dev.farmguard.model.ServerMetrics;
import dev.farmguard.model.ServerPressure;
import dev.farmguard.model.ThrottleType;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

public final class ProtectionManager {

    public enum ChangeType {
        NONE,
        STARTED,
        ESCALATED,
        RECOVERED
    }

    public record Change(ChangeType type, ProtectionState state, ProtectionLevel previous) {
    }

    private final Map<ChunkKey, Tracked> tracked = new HashMap<>();
    private boolean restrictionsEnabled;

    public void setRestrictionsEnabled(boolean enabled) {
        this.restrictionsEnabled = enabled;
        if (!enabled) {
            releaseImmediately();
        }
    }

    public boolean restrictionsEnabled() {
        return restrictionsEnabled;
    }

    /**
     * MONITOR and plugin disable must drop limits on this tick, not after hysteresis.
     */
    public void releaseImmediately() {
        for (Tracked value : tracked.values()) {
            value.applied = ProtectionLevel.NORMAL;
            value.pending = ProtectionLevel.NORMAL;
            value.buckets.clear();
        }
    }

    public void evictWorld(java.util.UUID worldId) {
        tracked.keySet().removeIf(key -> key.worldId().equals(worldId));
    }

    public Set<ChunkKey> restrictingKeys() {
        Set<ChunkKey> keys = new java.util.HashSet<>();
        for (Tracked value : tracked.values()) {
            if (value.applied.restrictsGameplay()) {
                keys.add(value.chunk);
            }
        }
        return keys;
    }

    public List<Change> tick(
            List<RiskAssessment> assessments,
            ServerMetrics server,
            OperatingMode mode,
            FarmGuardSettings settings,
            Predicate<ChunkKey> whitelist,
            long nowMs
    ) {
        List<Change> changes = new ArrayList<>();
        boolean allow = mode.allowsProtection();
        if (!allow && restrictionsEnabled) {
            releaseImmediately();
        }
        restrictionsEnabled = allow;
        Map<ChunkKey, RiskAssessment> active = new HashMap<>();
        for (RiskAssessment assessment : assessments) {
            active.put(assessment.chunk(), assessment);
            boolean listed = whitelist.test(assessment.chunk());
            ProtectionLevel recommended = recommend(assessment, server, listed, settings);
            Tracked current = tracked.get(assessment.chunk());
            if (current == null) {
                current = new Tracked(assessment.chunk(), nowMs);
                tracked.put(assessment.chunk(), current);
            }
            Change change = current.update(recommended, assessment, restrictionsEnabled && mode.allowsProtection(), listed, settings, nowMs);
            if (change.type() != ChangeType.NONE) {
                changes.add(change);
            }
        }
        Iterator<Map.Entry<ChunkKey, Tracked>> iterator = tracked.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<ChunkKey, Tracked> entry = iterator.next();
            if (active.containsKey(entry.getKey())) {
                continue;
            }
            Tracked idle = entry.getValue();
            Change change = idle.update(ProtectionLevel.NORMAL, null, restrictionsEnabled && mode.allowsProtection(), idle.whitelisted, settings, nowMs);
            if (change.type() != ChangeType.NONE) {
                changes.add(change);
            }
            if (idle.applied == ProtectionLevel.NORMAL && nowMs - idle.lastSeenMs > settings.inactiveTtlSeconds() * 1000L) {
                iterator.remove();
            }
        }
        return changes;
    }

    public ProtectionState stateOf(ChunkKey key) {
        Tracked value = tracked.get(key);
        return value == null ? null : value.snapshot();
    }

    public ProtectionLevel applied(ChunkKey key) {
        Tracked value = tracked.get(key);
        return value == null ? ProtectionLevel.NORMAL : value.applied;
    }

    public boolean shouldThrottle(ChunkKey key, ThrottleType type, FarmGuardSettings settings, long nowMs) {
        if (!restrictionsEnabled) {
            return false;
        }
        Tracked value = tracked.get(key);
        if (value == null || !value.applied.restrictsGameplay()) {
            return false;
        }
        if (!settings.throttleEnabled(type)) {
            return false;
        }
        if (settings.emergencyOnly(type) && value.applied != ProtectionLevel.EMERGENCY) {
            return false;
        }
        if (settings.emergencyOnly(type)
                && settings.requireLagCorrelationForEmergency()
                && (value.latest == null || value.latest.correlation() == LagCorrelation.NONE)) {
            return false;
        }
        boolean emergency = value.applied == ProtectionLevel.EMERGENCY;
        int rate = settings.throttleRate(type, emergency);
        TokenBucket bucket = value.buckets.computeIfAbsent(type, ignored -> new TokenBucket());
        return !bucket.tryConsume(nowMs, rate);
    }

    public List<ProtectionState> activeLimits() {
        List<ProtectionState> list = new ArrayList<>();
        for (Tracked value : tracked.values()) {
            if (value.applied.restrictsGameplay()) {
                list.add(value.snapshot());
            }
        }
        return list;
    }

    public Map<ChunkKey, ProtectionLevel> appliedByChunk() {
        Map<ChunkKey, ProtectionLevel> map = new HashMap<>();
        for (Tracked value : tracked.values()) {
            map.put(value.chunk, value.applied);
        }
        return map;
    }

    public int restrictingCount() {
        int count = 0;
        for (Tracked value : tracked.values()) {
            if (value.applied.restrictsGameplay()) {
                count++;
            }
        }
        return count;
    }

    public void clearAll() {
        tracked.clear();
    }

    static ProtectionLevel recommend(
            RiskAssessment assessment,
            ServerMetrics server,
            boolean whitelisted,
            FarmGuardSettings settings
    ) {
        if (whitelisted || settings.ignoresWorld(assessment.chunk().worldName())) {
            return ProtectionLevel.NORMAL;
        }
        ServerPressure pressure = server.pressure();
        RiskLevel risk = assessment.level();
        LagCorrelation correlation = assessment.correlation();

        boolean emergencyOk = pressure.atLeast(settings.emergencyMinPressure())
                && risk.atLeast(settings.emergencyMinRisk())
                && (!settings.requireLagCorrelationForEmergency() || correlation != LagCorrelation.NONE);
        if (emergencyOk) {
            return ProtectionLevel.EMERGENCY;
        }
        boolean throttleOk = pressure.atLeast(settings.throttleMinPressure())
                && risk.atLeast(settings.throttleMinRisk());
        if (throttleOk) {
            return ProtectionLevel.THROTTLE;
        }
        if (pressure.atLeast(ServerPressure.WARNING) && risk.atLeast(RiskLevel.HIGH)) {
            return ProtectionLevel.WARNING;
        }
        return ProtectionLevel.NORMAL;
    }

    private static final class Tracked {
        private final ChunkKey chunk;
        private ProtectionLevel applied = ProtectionLevel.NORMAL;
        private ProtectionLevel recommended = ProtectionLevel.NORMAL;
        private ProtectionLevel pending = ProtectionLevel.NORMAL;
        private long pendingSinceMs;
        private long lastChangeMs;
        private long lastSeenMs;
        private boolean whitelisted;
        private boolean monitorOnly = true;
        private RiskAssessment latest;
        private final EnumMap<ThrottleType, TokenBucket> buckets = new EnumMap<>(ThrottleType.class);

        private Tracked(ChunkKey chunk, long nowMs) {
            this.chunk = chunk;
            this.lastSeenMs = nowMs;
            this.pendingSinceMs = nowMs;
        }

        private Change update(
                ProtectionLevel nextRecommended,
                RiskAssessment assessment,
                boolean allowProtection,
                boolean listed,
                FarmGuardSettings settings,
                long nowMs
        ) {
            this.latest = assessment;
            this.whitelisted = listed;
            this.monitorOnly = !allowProtection;
            this.lastSeenMs = nowMs;
            this.recommended = nextRecommended;
            ProtectionLevel desired = allowProtection ? nextRecommended : ProtectionLevel.NORMAL;
            ProtectionLevel previous = applied;

            if (!allowProtection && applied.restrictsGameplay()) {
                applied = ProtectionLevel.NORMAL;
                pending = ProtectionLevel.NORMAL;
                buckets.clear();
                lastChangeMs = nowMs;
                return new Change(ChangeType.RECOVERED, snapshot(), previous);
            }

            if (desired == applied) {
                pending = applied;
                pendingSinceMs = nowMs;
                return new Change(ChangeType.NONE, snapshot(), previous);
            }
            if (pending != desired) {
                pending = desired;
                pendingSinceMs = nowMs;
                return new Change(ChangeType.NONE, snapshot(), previous);
            }
            boolean upgrading = desired.ordinal() > applied.ordinal();
            long required = (upgrading ? settings.protectionEnterSeconds() : settings.protectionExitSeconds()) * 1000L;
            if (nowMs - pendingSinceMs < required || nowMs - lastChangeMs < settings.protectionCooldownSeconds() * 1000L) {
                return new Change(ChangeType.NONE, snapshot(), previous);
            }
            if (!upgrading && desired.ordinal() < applied.ordinal() - 1) {
                desired = applied.stepDown();
            }
            applied = desired;
            lastChangeMs = nowMs;
            pending = applied;
            pendingSinceMs = nowMs;
            buckets.clear();
            ChangeType type = ChangeType.NONE;
            if (applied.restrictsGameplay() && !previous.restrictsGameplay()) {
                type = ChangeType.STARTED;
            } else if (applied.ordinal() > previous.ordinal()) {
                type = ChangeType.ESCALATED;
            } else if (previous.restrictsGameplay() && applied.ordinal() < previous.ordinal()) {
                type = ChangeType.RECOVERED;
            }
            return new Change(type, snapshot(), previous);
        }

        private ProtectionState snapshot() {
            Set<dev.farmguard.model.RiskReason> reasons = latest == null
                    ? Set.of()
                    : latest.reasonSet();
            return new ProtectionState(chunk, applied, recommended, lastChangeMs, reasons, whitelisted, monitorOnly);
        }
    }
}
