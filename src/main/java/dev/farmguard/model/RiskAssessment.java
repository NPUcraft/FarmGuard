package dev.farmguard.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public final class RiskAssessment {

    private final ChunkKey chunk;
    private final double activityScore;
    private final double riskScore;
    private final double pressureMultiplier;
    private final RiskLevel level;
    private final LagCorrelation correlation;
    private final List<RiskReason> reasons;
    private final List<ScoreContribution> contributions;
    private final ChunkActivitySnapshot snapshot;

    public RiskAssessment(
            ChunkKey chunk,
            double activityScore,
            double riskScore,
            double pressureMultiplier,
            RiskLevel level,
            LagCorrelation correlation,
            List<RiskReason> reasons,
            List<ScoreContribution> contributions,
            ChunkActivitySnapshot snapshot
    ) {
        this.chunk = chunk;
        this.activityScore = activityScore;
        this.riskScore = riskScore;
        this.pressureMultiplier = pressureMultiplier;
        this.level = level;
        this.correlation = correlation;
        this.reasons = List.copyOf(reasons == null ? List.of() : reasons);
        this.contributions = List.copyOf(contributions == null ? List.of() : contributions);
        this.snapshot = snapshot;
    }

    public ChunkKey chunk() {
        return chunk;
    }

    public double activityScore() {
        return activityScore;
    }

    public double riskScore() {
        return riskScore;
    }

    public double pressureMultiplier() {
        return pressureMultiplier;
    }

    public RiskLevel level() {
        return level;
    }

    public LagCorrelation correlation() {
        return correlation;
    }

    public List<RiskReason> reasons() {
        return reasons;
    }

    public Set<RiskReason> reasonSet() {
        return reasons.isEmpty() ? EnumSet.noneOf(RiskReason.class) : EnumSet.copyOf(reasons);
    }

    public List<ScoreContribution> contributions() {
        return contributions;
    }

    public ChunkActivitySnapshot snapshot() {
        return snapshot;
    }

    public List<String> primaryActivityLabels(int limit) {
        List<ScoreContribution> ranked = new ArrayList<>(contributions);
        ranked.sort((a, b) -> Double.compare(b.points(), a.points()));
        List<String> labels = new ArrayList<>();
        for (ScoreContribution contribution : ranked) {
            if (contribution.points() <= 0.5) {
                continue;
            }
            labels.add(contribution.label());
            if (labels.size() >= limit) {
                break;
            }
        }
        return Collections.unmodifiableList(labels);
    }

    public String reasonsSummary() {
        if (reasons.isEmpty()) {
            return "-";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < reasons.size(); i++) {
            if (i > 0) {
                builder.append(" / ");
            }
            builder.append(formatReason(reasons.get(i)));
        }
        return builder.toString();
    }

    public static String formatReason(RiskReason reason) {
        return switch (reason) {
            case EXCESSIVE_REDSTONE -> "Redstone";
            case EXCESSIVE_PISTONS -> "Piston";
            case EXCESSIVE_HOPPERS -> "Hopper";
            case EXCESSIVE_ENTITIES -> "Entity";
            case EXCESSIVE_ITEMS -> "Item";
            case EXCESSIVE_MINECARTS -> "Minecart";
            case EXCESSIVE_VILLAGERS -> "Villager";
            case EXCESSIVE_SPAWNS -> "Spawn";
            case EXCESSIVE_BLOCK_UPDATES -> "Block";
            case RAPID_OSCILLATION -> "Oscillation";
            case SUDDEN_SPIKE -> "Spike";
            case SUSTAINED_LOAD -> "Sustained";
            case LAG_SUSPECT -> "Lag suspect";
            case STRONG_LAG_CORRELATION -> "Strong lag correlation";
            case RUNAWAY_GROWTH -> "Runaway growth";
        };
    }
}
