package com.npucraft.farmguard.i18n;

import com.npucraft.farmguard.model.AutomationCluster;
import com.npucraft.farmguard.model.ChunkKey;
import com.npucraft.farmguard.model.ProtectionLevel;
import com.npucraft.farmguard.model.ProtectionState;
import com.npucraft.farmguard.model.RiskAssessment;
import com.npucraft.farmguard.model.RiskLevel;
import com.npucraft.farmguard.model.RiskReason;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Presentation-only cluster-first view of an already ranked hotspot list.
 * Does not re-score Risk or Activity.
 */
public final class TopDisplay {

    private TopDisplay() {
    }

    public record Entry(
            RiskAssessment representative,
            AutomationCluster cluster,
            ChunkKey location,
            RiskLevel level,
            double activityScore,
            List<RiskReason> reasons,
            int memberChunks,
            ProtectionLevel protection
    ) {
        public boolean clustered() {
            return cluster != null;
        }
    }

    public static List<Entry> of(
            List<RiskAssessment> rankedHotspots,
            List<AutomationCluster> clusters,
            List<ProtectionState> protections,
            int limit
    ) {
        int cap = Math.max(0, limit);
        if (cap == 0 || rankedHotspots == null || rankedHotspots.isEmpty()) {
            return List.of();
        }
        Map<ChunkKey, AutomationCluster> byChunk = index(clusters);
        Set<String> seenClusters = new HashSet<>();
        List<Entry> entries = new ArrayList<>();
        for (RiskAssessment assessment : rankedHotspots) {
            if (assessment == null || assessment.chunk() == null) {
                continue;
            }
            AutomationCluster cluster = byChunk.get(assessment.chunk());
            if (cluster != null) {
                if (!seenClusters.add(cluster.id())) {
                    continue;
                }
                ChunkKey location = cluster.origin() == null ? assessment.chunk() : cluster.origin();
                entries.add(new Entry(
                        assessment,
                        cluster,
                        location,
                        cluster.riskLevel(),
                        cluster.activityScore(),
                        cluster.reasons(),
                        Math.max(1, cluster.chunks().size()),
                        protectionOf(cluster, location, protections)
                ));
            } else {
                entries.add(new Entry(
                        assessment,
                        null,
                        assessment.chunk(),
                        assessment.level(),
                        assessment.activityScore(),
                        assessment.reasons(),
                        1,
                        protectionOf(null, assessment.chunk(), protections)
                ));
            }
            if (entries.size() >= cap) {
                break;
            }
        }
        return List.copyOf(entries);
    }

    public static int attentionCount(List<Entry> entries) {
        int count = 0;
        if (entries == null) {
            return 0;
        }
        for (Entry entry : entries) {
            if (entry.level() != null && entry.level().atLeast(RiskLevel.MEDIUM)) {
                count++;
            }
        }
        return count;
    }

    private static Map<ChunkKey, AutomationCluster> index(List<AutomationCluster> clusters) {
        Map<ChunkKey, AutomationCluster> map = new HashMap<>();
        if (clusters == null) {
            return map;
        }
        for (AutomationCluster cluster : clusters) {
            for (ChunkKey chunk : cluster.chunks()) {
                map.put(chunk, cluster);
            }
        }
        return map;
    }

    private static ProtectionLevel protectionOf(
            AutomationCluster cluster,
            ChunkKey location,
            List<ProtectionState> protections
    ) {
        ProtectionLevel best = cluster == null ? ProtectionLevel.NORMAL : cluster.protectionLevel();
        if (best == null) {
            best = ProtectionLevel.NORMAL;
        }
        if (protections == null) {
            return best;
        }
        for (ProtectionState state : protections) {
            if (state == null || state.chunk() == null) {
                continue;
            }
            boolean match = cluster != null
                    ? cluster.chunks().contains(state.chunk())
                    : state.chunk().equals(location);
            if (match && state.applied() != null && state.applied().ordinal() > best.ordinal()) {
                best = state.applied();
            }
        }
        return best;
    }
}
