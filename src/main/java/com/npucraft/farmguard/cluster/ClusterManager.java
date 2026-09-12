package com.npucraft.farmguard.cluster;

import com.npucraft.farmguard.config.FarmGuardSettings;
import com.npucraft.farmguard.model.AutomationCluster;
import com.npucraft.farmguard.model.ChunkKey;
import com.npucraft.farmguard.model.ClusterType;
import com.npucraft.farmguard.model.LagCorrelation;
import com.npucraft.farmguard.model.MetricType;
import com.npucraft.farmguard.model.ProtectionLevel;
import com.npucraft.farmguard.model.RiskAssessment;
import com.npucraft.farmguard.model.RiskLevel;
import com.npucraft.farmguard.model.RiskReason;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * V1 clustering is a simple adjacent-hotspot union. Chunks merge only when they
 * are neighbors and share a current-window activity family (a proxy for overlap).
 * Adjacent hopper and redstone machines therefore stay separate. IDs are derived
 * from the minimum chunk coordinate so they stay stable across refreshes.
 */
public final class ClusterManager {

    private List<AutomationCluster> clusters = List.of();

    public List<AutomationCluster> current() {
        return clusters;
    }

    public AutomationCluster find(ChunkKey key) {
        for (AutomationCluster cluster : clusters) {
            if (cluster.chunks().contains(key)) {
                return cluster;
            }
        }
        return null;
    }

    public void refresh(List<RiskAssessment> assessments, Map<ChunkKey, ProtectionLevel> protectionByChunk, FarmGuardSettings settings) {
        List<RiskAssessment> seeds = new ArrayList<>();
        for (RiskAssessment assessment : assessments) {
            if (assessment.activityScore() >= settings.clusterMinActivityScore()
                    || assessment.level().atLeast(RiskLevel.MEDIUM)) {
                seeds.add(assessment);
            }
        }
        if (seeds.isEmpty()) {
            clusters = List.of();
            return;
        }

        Map<ChunkKey, RiskAssessment> byKey = new HashMap<>();
        for (RiskAssessment seed : seeds) {
            byKey.put(seed.chunk(), seed);
        }
        List<ChunkKey> keys = new ArrayList<>(byKey.keySet());
        int[] parent = new int[keys.size()];
        for (int i = 0; i < parent.length; i++) {
            parent[i] = i;
        }
        int range = Math.max(1, settings.clusterNeighborhood());
        for (int i = 0; i < keys.size(); i++) {
            for (int j = i + 1; j < keys.size(); j++) {
                if (keys.get(i).isNeighbor(keys.get(j), range) && related(byKey.get(keys.get(i)), byKey.get(keys.get(j)))) {
                    union(parent, i, j);
                }
            }
        }

        Map<Integer, LinkedHashSet<ChunkKey>> groups = new HashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            groups.computeIfAbsent(find(parent, i), ignored -> new LinkedHashSet<>()).add(keys.get(i));
        }

        List<AutomationCluster> next = new ArrayList<>();
        for (Set<ChunkKey> group : groups.values()) {
            List<RiskAssessment> members = new ArrayList<>();
            for (ChunkKey key : group) {
                members.add(byKey.get(key));
            }
            members.sort(Comparator.comparingDouble(RiskAssessment::riskScore).reversed());
            RiskAssessment lead = members.get(0);
            double activity = 0.0;
            double risk = 0.0;
            LagCorrelation correlation = LagCorrelation.NONE;
            RiskLevel level = RiskLevel.NONE;
            ProtectionLevel protection = ProtectionLevel.NORMAL;
            LinkedHashSet<RiskReason> reasons = new LinkedHashSet<>();
            for (RiskAssessment member : members) {
                activity = Math.max(activity, member.activityScore());
                risk = Math.max(risk, member.riskScore());
                if (member.correlation().ordinal() > correlation.ordinal()) {
                    correlation = member.correlation();
                }
                if (member.level().ordinal() > level.ordinal()) {
                    level = member.level();
                }
                reasons.addAll(member.reasons());
                ProtectionLevel applied = protectionByChunk.getOrDefault(member.chunk(), ProtectionLevel.NORMAL);
                if (applied.ordinal() > protection.ordinal()) {
                    protection = applied;
                }
            }
            ClusterType type = classify(members, settings);
            ChunkKey origin = minChunk(group);
            String id = origin.worldName() + ":" + origin.x() + "," + origin.z();
            next.add(new AutomationCluster(
                    id,
                    origin.worldId(),
                    origin.worldName(),
                    group,
                    type,
                    activity,
                    risk,
                    level,
                    correlation,
                    new ArrayList<>(reasons),
                    protection
            ));
        }
        next.sort(Comparator.comparingDouble(AutomationCluster::riskScore).reversed());
        this.clusters = List.copyOf(next);
    }

    public void clear() {
        clusters = List.of();
    }

    static ClusterType classify(List<RiskAssessment> members, FarmGuardSettings settings) {
        double villager = 0.0;
        double hopper = 0.0;
        double redstone = 0.0;
        double mob = 0.0;
        double items = 0.0;
        for (RiskAssessment member : members) {
            villager += member.snapshot().shortPerSecond(MetricType.VILLAGER) + member.snapshot().villagers();
            hopper += member.snapshot().shortPerSecond(MetricType.HOPPER);
            redstone += member.snapshot().shortPerSecond(MetricType.REDSTONE) + member.snapshot().shortPerSecond(MetricType.PISTON);
            mob += member.snapshot().shortPerSecond(MetricType.ENTITY_SPAWN)
                    + member.snapshot().shortPerSecond(MetricType.ENTITY_DEATH)
                    + member.snapshot().shortPerSecond(MetricType.BREEDING);
            items += member.snapshot().shortPerSecond(MetricType.ITEM) + member.snapshot().items();
        }
        double[] scores = {villager, hopper, redstone, mob, items};
        ClusterType[] types = {
                ClusterType.VILLAGER_FACILITY,
                ClusterType.STORAGE,
                ClusterType.REDSTONE_MACHINE,
                ClusterType.MOB_FACILITY,
                ClusterType.FARM
        };
        int best = 0;
        int second = 0;
        for (int i = 1; i < scores.length; i++) {
            if (scores[i] > scores[best]) {
                second = best;
                best = i;
            } else if (scores[i] > scores[second]) {
                second = i;
            }
        }
        if (scores[best] <= 1.0) {
            return ClusterType.UNKNOWN_AUTOMATION;
        }
        if (scores[second] > 0.0 && scores[best] < scores[second] * (1.0 + settings.classificationMargin())) {
            if (hopper > 0 && (villager > 0 || mob > 0 || items > 0) && best != 2) {
                return ClusterType.UNKNOWN_AUTOMATION;
            }
            return ClusterType.UNKNOWN_AUTOMATION;
        }
        if (best == 4 && hopper > items) {
            return ClusterType.UNKNOWN_AUTOMATION;
        }
        return types[best];
    }

    private static boolean related(RiskAssessment a, RiskAssessment b) {
        if (a == null || b == null) {
            return false;
        }
        if (!a.chunk().worldId().equals(b.chunk().worldId())) {
            return false;
        }
        return shareActivity(a, b);
    }

    private static boolean shareActivity(RiskAssessment a, RiskAssessment b) {
        ActivityFamily left = family(a);
        ActivityFamily right = family(b);
        if (left == ActivityFamily.NONE || right == ActivityFamily.NONE) {
            return false;
        }
        return left == right;
    }

    private enum ActivityFamily {
        REDSTONE,
        STORAGE,
        MOB,
        VILLAGER,
        ITEM,
        NONE
    }

    static ActivityFamily family(RiskAssessment assessment) {
        double redstone = rate(assessment, MetricType.REDSTONE) + rate(assessment, MetricType.PISTON);
        double hopper = rate(assessment, MetricType.HOPPER);
        double mob = rate(assessment, MetricType.ENTITY_SPAWN) + rate(assessment, MetricType.ENTITY_DEATH)
                + rate(assessment, MetricType.BREEDING);
        double villager = rate(assessment, MetricType.VILLAGER) + assessment.snapshot().villagers();
        double items = rate(assessment, MetricType.ITEM);
        double best = redstone;
        ActivityFamily family = ActivityFamily.REDSTONE;
        if (hopper > best) {
            best = hopper;
            family = ActivityFamily.STORAGE;
        }
        if (mob > best) {
            best = mob;
            family = ActivityFamily.MOB;
        }
        if (villager > best) {
            best = villager;
            family = ActivityFamily.VILLAGER;
        }
        if (items > best) {
            best = items;
            family = ActivityFamily.ITEM;
        }
        if (best < 1.0) {
            return ActivityFamily.NONE;
        }
        return family;
    }

    private static double rate(RiskAssessment assessment, MetricType type) {
        return assessment.snapshot().shortPerSecond(type);
    }

    private static ChunkKey minChunk(Set<ChunkKey> group) {
        ChunkKey best = null;
        for (ChunkKey key : group) {
            if (best == null || key.x() < best.x() || (key.x() == best.x() && key.z() < best.z())) {
                best = key;
            }
        }
        return best;
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) {
            parent[rb] = ra;
        }
    }
}
