package com.npucraft.farmguard.i18n;

import com.npucraft.farmguard.model.ChunkActivitySnapshot;
import com.npucraft.farmguard.model.MetricType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Picks display-only activity highlights from an already computed snapshot.
 */
public final class ActivityHighlights {

    public record Line(String key, double count, boolean estimated) {
    }

    private ActivityHighlights() {
    }

    public static MetricType primaryMetric(ChunkActivitySnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        MetricType best = null;
        double bestRate = 0.0;
        for (MetricType type : MetricType.values()) {
            double rate = snapshot.longPerSecond(type);
            if (rate > bestRate) {
                bestRate = rate;
                best = type;
            }
        }
        return bestRate >= 0.05 ? best : null;
    }

    public static String primaryActivityKey(ChunkActivitySnapshot snapshot) {
        MetricType metric = primaryMetric(snapshot);
        if (metric != null) {
            return LocaleIds.kebab(metric);
        }
        if (snapshot != null && snapshot.totalEntities() > 0) {
            return "entity-density";
        }
        return null;
    }

    public static List<Line> inspectLines(ChunkActivitySnapshot snapshot, int limit) {
        List<Line> lines = new ArrayList<>();
        if (snapshot == null) {
            return lines;
        }
        for (MetricType type : MetricType.values()) {
            double rate = snapshot.longPerSecond(type);
            if (rate >= 0.05) {
                lines.add(new Line(LocaleIds.kebab(type), rate, false));
            }
        }
        if (snapshot.totalEntities() > 0) {
            lines.add(new Line("entity-density", snapshot.totalEntities(), true));
        }
        lines.sort(Comparator.comparingDouble(Line::count).reversed());
        if (lines.size() > limit) {
            return List.copyOf(lines.subList(0, limit));
        }
        return List.copyOf(lines);
    }
}
