package dev.farmguard.risk;

import dev.farmguard.config.FarmGuardSettings;
import dev.farmguard.model.ChunkActivitySnapshot;
import dev.farmguard.model.LagCorrelation;
import dev.farmguard.model.MetricType;
import dev.farmguard.model.RiskAssessment;
import dev.farmguard.model.RiskLevel;
import dev.farmguard.model.RiskReason;
import dev.farmguard.model.ScoreContribution;
import dev.farmguard.model.ServerMetrics;
import dev.farmguard.util.Numbers;
import java.util.ArrayList;
import java.util.List;

public final class RiskEngine {

    public RiskAssessment assess(
            ChunkActivitySnapshot snapshot,
            ServerMetrics server,
            LagCorrelation correlation,
            FarmGuardSettings settings
    ) {
        List<ScoreContribution> contributions = new ArrayList<>();
        List<RiskReason> reasons = new ArrayList<>();
        double activity = 0.0;

        activity += addRate(snapshot, MetricType.REDSTONE, "Redstone", settings, contributions, reasons, RiskReason.EXCESSIVE_REDSTONE);
        activity += addRate(snapshot, MetricType.PISTON, "Piston", settings, contributions, reasons, RiskReason.EXCESSIVE_PISTONS);
        activity += addRate(snapshot, MetricType.HOPPER, "Hopper", settings, contributions, reasons, RiskReason.EXCESSIVE_HOPPERS);
        activity += addRate(snapshot, MetricType.ENTITY_SPAWN, "Entity spawn", settings, contributions, reasons, RiskReason.EXCESSIVE_SPAWNS);
        activity += addRate(snapshot, MetricType.ENTITY_DEATH, "Entity death", settings, contributions, reasons, null);
        activity += addRate(snapshot, MetricType.ITEM, "Item spawn", settings, contributions, reasons, RiskReason.EXCESSIVE_ITEMS);
        activity += addRate(snapshot, MetricType.VILLAGER, "Villager activity", settings, contributions, reasons, RiskReason.EXCESSIVE_VILLAGERS);
        activity += addRate(snapshot, MetricType.MINECART, "Minecart", settings, contributions, reasons, RiskReason.EXCESSIVE_MINECARTS);
        activity += addRate(snapshot, MetricType.BREEDING, "Breeding", settings, contributions, reasons, RiskReason.EXCESSIVE_SPAWNS);
        activity += addRate(snapshot, MetricType.BLOCK_UPDATE, "Block activity", settings, contributions, reasons, RiskReason.EXCESSIVE_BLOCK_UPDATES);

        activity += addDensity("Item density", snapshot.items(), settings.excessiveItems(), settings.itemDensityWeight(), contributions, reasons, RiskReason.EXCESSIVE_ITEMS);
        activity += addDensity("Villager density", snapshot.villagers(), settings.excessiveVillagers(), settings.villagerDensityWeight(), contributions, reasons, RiskReason.EXCESSIVE_VILLAGERS);
        activity += addDensity("Minecart density", snapshot.minecarts(), settings.excessiveMinecarts(), settings.minecartDensityWeight(), contributions, reasons, RiskReason.EXCESSIVE_MINECARTS);
        activity += addDensity("Entity density", snapshot.totalEntities(), settings.excessiveEntities(), settings.entityDensityWeight(), contributions, reasons, RiskReason.EXCESSIVE_ENTITIES);

        if (snapshot.shortPerSecond(MetricType.REDSTONE) >= settings.redstoneOscillationPerSecond()) {
            addReason(reasons, RiskReason.RAPID_OSCILLATION);
            activity += 10.0;
            contributions.add(new ScoreContribution("Rapid oscillation", 10.0));
        }

        if (isSpike(snapshot, settings)) {
            addReason(reasons, RiskReason.SUDDEN_SPIKE);
            activity += 8.0;
            contributions.add(new ScoreContribution("Sudden spike", 8.0));
        }

        if (isSustained(snapshot, settings)) {
            addReason(reasons, RiskReason.SUSTAINED_LOAD);
            activity += 6.0;
            contributions.add(new ScoreContribution("Sustained load", 6.0));
        }

        if (snapshot.items() >= settings.excessiveItems() && snapshot.items() > snapshot.previousItems() + Math.max(8, settings.excessiveItems() / 10)) {
            addReason(reasons, RiskReason.RUNAWAY_GROWTH);
            activity += 8.0;
            contributions.add(new ScoreContribution("Runaway item growth", 8.0));
        }

        activity = Numbers.clamp(activity, 0.0, settings.maxScore());
        double multiplier = settings.pressureMultiplier(server.pressure());
        double bonus = settings.lagBonus(correlation);
        if (correlation == LagCorrelation.POSSIBLE) {
            addReason(reasons, RiskReason.LAG_SUSPECT);
            contributions.add(new ScoreContribution("MSPT pressure correlation", bonus));
        } else if (correlation == LagCorrelation.STRONG) {
            addReason(reasons, RiskReason.STRONG_LAG_CORRELATION);
            contributions.add(new ScoreContribution("MSPT pressure correlation", bonus));
        }
        contributions.add(new ScoreContribution("MSPT pressure multiplier x" + Numbers.oneDecimal(multiplier), 0.0));

        double risk = Numbers.clamp(activity * multiplier + bonus, 0.0, settings.maxScore());
        RiskLevel level = levelFor(risk, settings);
        return new RiskAssessment(
                snapshot.key(),
                round1(activity),
                round1(risk),
                multiplier,
                level,
                correlation,
                reasons,
                contributions,
                snapshot
        );
    }

    private static double addRate(
            ChunkActivitySnapshot snapshot,
            MetricType type,
            String label,
            FarmGuardSettings settings,
            List<ScoreContribution> contributions,
            List<RiskReason> reasons,
            RiskReason reason
    ) {
        double rate = snapshot.shortPerSecond(type);
        double threshold = Math.max(0.0001, settings.rateThreshold(type));
        double weight = settings.activityWeight(type);
        double ratio = Numbers.clamp(rate / threshold, 0.0, 2.0);
        double points = weight * ratio;
        if (points >= 0.5) {
            contributions.add(new ScoreContribution(label, round1(points)));
        }
        if (reason != null && rate >= threshold) {
            addReason(reasons, reason);
        }
        return points;
    }

    private static double addDensity(
            String label,
            int count,
            int threshold,
            double weight,
            List<ScoreContribution> contributions,
            List<RiskReason> reasons,
            RiskReason reason
    ) {
        if (threshold <= 0) {
            return 0.0;
        }
        double ratio = Numbers.clamp(count / (double) threshold, 0.0, 2.0);
        double points = weight * ratio;
        if (points >= 0.5) {
            contributions.add(new ScoreContribution(label, round1(points)));
        }
        if (count >= threshold) {
            addReason(reasons, reason);
        }
        return points;
    }

    private static boolean isSpike(ChunkActivitySnapshot snapshot, FarmGuardSettings settings) {
        double shortTotal = snapshot.totalShortActivity();
        double longTotal = 0.0;
        for (MetricType type : MetricType.values()) {
            longTotal += snapshot.longPerSecond(type);
        }
        return shortTotal >= settings.spikeMinShortPerSecond()
                && longTotal > 0.5
                && shortTotal >= longTotal * settings.spikeRatio();
    }

    private static boolean isSustained(ChunkActivitySnapshot snapshot, FarmGuardSettings settings) {
        int over = 0;
        for (MetricType type : MetricType.values()) {
            if (snapshot.longPerSecond(type) >= settings.rateThreshold(type)) {
                over++;
            }
        }
        return over > 0;
    }

    private static void addReason(List<RiskReason> reasons, RiskReason reason) {
        if (!reasons.contains(reason)) {
            reasons.add(reason);
        }
    }

    public static RiskLevel levelFor(double score, FarmGuardSettings settings) {
        if (score >= settings.criticalScore()) {
            return RiskLevel.CRITICAL;
        }
        if (score >= settings.highScore()) {
            return RiskLevel.HIGH;
        }
        if (score >= settings.mediumScore()) {
            return RiskLevel.MEDIUM;
        }
        if (score >= settings.lowScore()) {
            return RiskLevel.LOW;
        }
        return RiskLevel.NONE;
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
