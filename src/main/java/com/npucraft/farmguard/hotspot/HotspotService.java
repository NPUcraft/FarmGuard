package dev.farmguard.hotspot;

import dev.farmguard.config.FarmGuardSettings;
import dev.farmguard.model.ChunkKey;
import dev.farmguard.model.RiskAssessment;
import dev.farmguard.model.RiskLevel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class HotspotService {

    private List<RiskAssessment> ranked = List.of();
    private int activeChunks;
    private int highRiskChunks;

    public void update(List<RiskAssessment> assessments, FarmGuardSettings settings) {
        List<RiskAssessment> filtered = new ArrayList<>();
        int high = 0;
        for (RiskAssessment assessment : assessments) {
            if (assessment.activityScore() < settings.hotspotMinActivityScore() && assessment.level() == RiskLevel.NONE) {
                continue;
            }
            filtered.add(assessment);
            if (assessment.level().atLeast(RiskLevel.HIGH)) {
                high++;
            }
        }
        filtered.sort(Comparator
                .comparingDouble(RiskAssessment::riskScore)
                .reversed()
                .thenComparing(Comparator.comparingDouble(RiskAssessment::activityScore).reversed()));
        this.ranked = List.copyOf(filtered);
        this.activeChunks = filtered.size();
        this.highRiskChunks = high;
    }

    public List<RiskAssessment> top(int limit) {
        if (limit <= 0 || ranked.isEmpty()) {
            return List.of();
        }
        return ranked.subList(0, Math.min(limit, ranked.size()));
    }

    public RiskAssessment find(ChunkKey key) {
        for (RiskAssessment assessment : ranked) {
            if (assessment.chunk().equals(key)) {
                return assessment;
            }
        }
        return null;
    }

    public int activeChunks() {
        return activeChunks;
    }

    public int highRiskChunks() {
        return highRiskChunks;
    }

    public List<RiskAssessment> all() {
        return ranked;
    }
}
