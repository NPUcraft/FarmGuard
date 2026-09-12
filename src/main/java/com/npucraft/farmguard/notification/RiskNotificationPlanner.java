package dev.farmguard.notification;

import dev.farmguard.model.RiskAssessment;
import dev.farmguard.model.RiskLevel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Caps noisy per-chunk risk alerts. One cooldown window may emit a small
 * top-N batch plus a summary, not one line per hotspot.
 */
public final class RiskNotificationPlanner {

    public static final String DETAILS_KEY = "risk-details";
    public static final String SUMMARY_KEY = "risk-summary";

    private RiskNotificationPlanner() {
    }

    public record Plan(List<RiskAssessment> send, int suppressed) {
    }

    public static Plan plan(
            List<RiskAssessment> assessments,
            int budget,
            int cooldownSeconds,
            NotificationLimiter limiter,
            long nowMs
    ) {
        List<RiskAssessment> high = new ArrayList<>();
        for (RiskAssessment assessment : assessments) {
            if (assessment.level().atLeast(RiskLevel.HIGH)) {
                high.add(assessment);
            } else {
                limiter.clear("risk:" + assessment.chunk().compact());
            }
        }
        if (high.isEmpty()) {
            return new Plan(List.of(), 0);
        }
        high.sort(Comparator.comparing((RiskAssessment a) -> a.level().ordinal()).reversed()
                .thenComparing(Comparator.comparingDouble(RiskAssessment::riskScore).reversed()));
        if (!limiter.allow(DETAILS_KEY, nowMs, cooldownSeconds)) {
            return new Plan(List.of(), 0);
        }
        int limit = Math.max(1, budget);
        int sendCount = Math.min(limit, high.size());
        int suppressed = high.size() - sendCount;
        if (suppressed > 0 && !limiter.allow(SUMMARY_KEY, nowMs, cooldownSeconds)) {
            suppressed = 0;
        }
        return new Plan(List.copyOf(high.subList(0, sendCount)), suppressed);
    }
}
