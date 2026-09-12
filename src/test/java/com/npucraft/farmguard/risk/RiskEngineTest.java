package com.npucraft.farmguard.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.npucraft.farmguard.TestData;
import com.npucraft.farmguard.config.FarmGuardSettings;
import com.npucraft.farmguard.model.LagCorrelation;
import com.npucraft.farmguard.model.MetricType;
import com.npucraft.farmguard.model.RiskLevel;
import com.npucraft.farmguard.model.RiskReason;
import com.npucraft.farmguard.model.ServerMetrics;
import com.npucraft.farmguard.model.ServerPressure;
import org.junit.jupiter.api.Test;

class RiskEngineTest {

    private final RiskEngine engine = new RiskEngine();
    private final FarmGuardSettings settings = FarmGuardSettings.defaults();

    @Test
    void largeMachineIsNotAutomaticallyCriticalOnHealthyServer() {
        var snapshot = TestData.snapshot(TestData.chunk(120, -84), 40.0, 6.0, 40, 20);
        ServerMetrics healthy = new ServerMetrics(1L, 20.0, 18.0, 18.0, ServerPressure.NORMAL, false);
        var assessment = engine.assess(snapshot, healthy, LagCorrelation.NONE, settings);
        assertTrue(assessment.activityScore() > assessment.riskScore());
        assertTrue(assessment.level().ordinal() <= RiskLevel.MEDIUM.ordinal());
    }

    @Test
    void sameMachineBecomesHighRiskWhenServerIsCriticalAndCorrelated() {
        var snapshot = TestData.mixed(TestData.chunk(120, -84), 80.0, 10.0, 20.0, 60, 80, 4);
        ServerMetrics lag = new ServerMetrics(1L, 14.0, 70.0, 60.0, ServerPressure.CRITICAL, true);
        var assessment = engine.assess(snapshot, lag, LagCorrelation.STRONG, settings);
        assertTrue(assessment.riskScore() >= settings.highScore());
        assertTrue(assessment.level().atLeast(RiskLevel.HIGH));
        assertTrue(assessment.reasons().contains(RiskReason.STRONG_LAG_CORRELATION));
        assertTrue(assessment.reasons().contains(RiskReason.EXCESSIVE_HOPPERS)
                || assessment.reasons().contains(RiskReason.EXCESSIVE_VILLAGERS));
    }

    @Test
    void rapidOscillationAndSpikeAreExplainableReasons() {
        var snapshot = TestData.snapshotRates(TestData.chunk(0, 0), MetricType.REDSTONE, 160.0, 40.0);
        ServerMetrics warning = new ServerMetrics(1L, 18.5, 36.0, 34.0, ServerPressure.WARNING, false);
        var assessment = engine.assess(snapshot, warning, LagCorrelation.NONE, settings);
        assertTrue(assessment.reasons().contains(RiskReason.RAPID_OSCILLATION));
        assertTrue(assessment.reasons().contains(RiskReason.EXCESSIVE_REDSTONE));
        assertTrue(assessment.contributions().stream().anyMatch(c -> c.label().toLowerCase().contains("redstone")));
    }

    @Test
    void riskLevelUsesConfiguredBoundaries() {
        FarmGuardSettings custom = FarmGuardSettings.builder()
                .lowScore(10)
                .mediumScore(20)
                .highScore(40)
                .criticalScore(70)
                .build();
        assertEquals(RiskLevel.NONE, RiskEngine.levelFor(9, custom));
        assertEquals(RiskLevel.LOW, RiskEngine.levelFor(10, custom));
        assertEquals(RiskLevel.MEDIUM, RiskEngine.levelFor(20, custom));
        assertEquals(RiskLevel.HIGH, RiskEngine.levelFor(40, custom));
        assertEquals(RiskLevel.CRITICAL, RiskEngine.levelFor(70, custom));
    }
}
