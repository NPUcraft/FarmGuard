package dev.farmguard.risk;

import dev.farmguard.config.FarmGuardSettings;
import dev.farmguard.model.LagCorrelation;
import dev.farmguard.util.Numbers;

public final class LagCorrelationAnalyzer {

    public LagCorrelation analyze(double[] activity, double[] mspt, int samples, FarmGuardSettings settings) {
        int n = samples;
        if (activity == null || mspt == null || n < settings.correlationMinSamples()) {
            return LagCorrelation.NONE;
        }
        n = Math.min(n, Math.min(activity.length, mspt.length));
        if (n < settings.correlationMinSamples()) {
            return LagCorrelation.NONE;
        }

        double firstActivity = activity[0];
        double lastActivity = activity[n - 1];
        double firstMspt = mspt[0];
        double lastMspt = mspt[n - 1];
        double activityDelta = lastActivity - firstActivity;
        double msptDelta = lastMspt - firstMspt;
        double pearson = Numbers.pearson(activity, mspt, n);

        // Co-movement is required. A chunk that was already busy while the
        // server was healthy must not inherit STRONG just because MSPT later rose.
        boolean movedTogether = activityDelta >= settings.possibleActivityDelta()
                && msptDelta >= settings.possibleMsptDelta();
        if (!movedTogether) {
            return LagCorrelation.NONE;
        }
        boolean strong = pearson >= settings.strongPearson()
                && lastActivity >= settings.strongActivityScore()
                && lastMspt >= settings.strongMspt();
        if (strong) {
            return LagCorrelation.STRONG;
        }
        return LagCorrelation.POSSIBLE;
    }
}
