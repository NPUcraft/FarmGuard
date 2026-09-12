package com.npucraft.farmguard.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.npucraft.farmguard.config.FarmGuardSettings;
import com.npucraft.farmguard.model.LagCorrelation;
import org.junit.jupiter.api.Test;

class LagCorrelationAnalyzerTest {

    private final LagCorrelationAnalyzer analyzer = new LagCorrelationAnalyzer();
    private final FarmGuardSettings settings = FarmGuardSettings.defaults();

    @Test
    void returnsNoneWithoutEnoughSamples() {
        assertEquals(LagCorrelation.NONE, analyzer.analyze(new double[]{1, 2}, new double[]{1, 2}, 2, settings));
    }

    @Test
    void risingActivityAndMsptIsPossible() {
        double[] activity = {5, 8, 12, 16, 20, 24};
        double[] mspt = {18, 21, 25, 29, 33, 37};
        assertEquals(LagCorrelation.POSSIBLE, analyzer.analyze(activity, mspt, activity.length, settings));
    }

    @Test
    void tightlyCoupledHighSeriesIsStrong() {
        double[] activity = {20, 28, 36, 44, 52, 60, 68, 76};
        double[] mspt = {30, 36, 42, 50, 58, 66, 74, 82};
        assertEquals(LagCorrelation.STRONG, analyzer.analyze(activity, mspt, activity.length, settings));
    }

    @Test
    void unrelatedSeriesStayNone() {
        double[] activity = {40, 42, 41, 43, 40, 42, 41, 43};
        double[] mspt = {18, 17, 19, 18, 17, 19, 18, 17};
        assertEquals(LagCorrelation.NONE, analyzer.analyze(activity, mspt, activity.length, settings));
    }

    @Test
    void alreadyBusyChunkDoesNotInheritLagFromAnotherMachine() {
        double[] activity = {60, 60, 60, 61, 60, 61, 60, 60};
        double[] mspt = {18, 19, 22, 30, 42, 55, 68, 80};
        assertEquals(LagCorrelation.NONE, analyzer.analyze(activity, mspt, activity.length, settings));
    }
}
