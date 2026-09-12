package dev.farmguard.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class AutomationCluster {

    private final String id;
    private final UUID worldId;
    private final String worldName;
    private final Set<ChunkKey> chunks;
    private final ClusterType type;
    private final double activityScore;
    private final double riskScore;
    private final RiskLevel riskLevel;
    private final LagCorrelation correlation;
    private final List<RiskReason> reasons;
    private final ProtectionLevel protectionLevel;

    public AutomationCluster(
            String id,
            UUID worldId,
            String worldName,
            Set<ChunkKey> chunks,
            ClusterType type,
            double activityScore,
            double riskScore,
            RiskLevel riskLevel,
            LagCorrelation correlation,
            List<RiskReason> reasons,
            ProtectionLevel protectionLevel
    ) {
        this.id = id;
        this.worldId = worldId;
        this.worldName = worldName;
        this.chunks = Collections.unmodifiableSet(new LinkedHashSet<>(chunks));
        this.type = type;
        this.activityScore = activityScore;
        this.riskScore = riskScore;
        this.riskLevel = riskLevel;
        this.correlation = correlation;
        this.reasons = List.copyOf(reasons == null ? List.of() : reasons);
        this.protectionLevel = protectionLevel;
    }

    public String id() {
        return id;
    }

    public UUID worldId() {
        return worldId;
    }

    public String worldName() {
        return worldName;
    }

    public Set<ChunkKey> chunks() {
        return chunks;
    }

    public ClusterType type() {
        return type;
    }

    public double activityScore() {
        return activityScore;
    }

    public double riskScore() {
        return riskScore;
    }

    public RiskLevel riskLevel() {
        return riskLevel;
    }

    public LagCorrelation correlation() {
        return correlation;
    }

    public List<RiskReason> reasons() {
        return reasons;
    }

    public ProtectionLevel protectionLevel() {
        return protectionLevel;
    }

    public ChunkKey origin() {
        ChunkKey best = null;
        for (ChunkKey chunk : chunks) {
            if (best == null || chunk.x() < best.x() || (chunk.x() == best.x() && chunk.z() < best.z())) {
                best = chunk;
            }
        }
        return best;
    }

    public List<String> reasonLabels() {
        List<String> labels = new ArrayList<>();
        for (RiskReason reason : reasons) {
            labels.add(RiskAssessment.formatReason(reason));
        }
        return labels;
    }
}
