package dev.farmguard.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.farmguard.TestData;
import dev.farmguard.config.FarmGuardSettings;
import dev.farmguard.model.ChunkKey;
import dev.farmguard.model.LagCorrelation;
import dev.farmguard.model.ServerPressure;
import org.junit.jupiter.api.Test;

class LagCorrelationTrackerTest {

    private final LagCorrelationAnalyzer analyzer = new LagCorrelationAnalyzer();
    private final LagCorrelationTracker tracker = new LagCorrelationTracker();
    private final FarmGuardSettings settings = FarmGuardSettings.defaults();
    private final ChunkKey farmA = TestData.chunk(64, 0);
    private final ChunkKey farmB = TestData.chunk(96, 0);

    @Test
    void defaultStrongHoldExceedsEmergencyEnterDuration() {
        assertTrue(settings.strongHoldSeconds() > settings.protectionEnterSeconds());
        assertEquals(15, settings.strongHoldSeconds());
        assertEquals(8, settings.possibleHoldSeconds());
        assertEquals(6, settings.protectionEnterSeconds());
    }

    @Test
    void realRiseIsStrong() {
        double[] activity = {10, 22, 40, 55, 70, 85, 95, 100};
        double[] mspt = {20, 28, 36, 45, 54, 62, 68, 70};
        LagCorrelation instant = analyzer.analyze(activity, mspt, activity.length, settings);
        assertEquals(LagCorrelation.STRONG, instant);
        assertEquals(
                LagCorrelation.STRONG,
                tracker.update(farmB, instant, 100, ServerPressure.CRITICAL, settings, 1_000L)
        );
    }

    @Test
    void sustainedPlateauKeepsStrongInsideHoldWindow() {
        seedStrong(farmB, 1_000L);
        LagCorrelation held = tracker.update(
                farmB,
                LagCorrelation.NONE,
                100,
                ServerPressure.CRITICAL,
                settings,
                1_000L + 8_000L
        );
        assertEquals(LagCorrelation.STRONG, held);
    }

    @Test
    void plateauBelowStrongScoreStillHoldsIfChunkIsHot() {
        seedStrong(farmB, 1_000L);
        double belowStrong = settings.strongActivityScore() - 6.0;
        assertTrue(belowStrong >= settings.hotspotMinActivityScore());
        LagCorrelation held = tracker.update(
                farmB,
                LagCorrelation.NONE,
                belowStrong,
                ServerPressure.CRITICAL,
                settings,
                1_000L + 8_000L
        );
        assertEquals(LagCorrelation.STRONG, held);
    }

    @Test
    void pressureRecoveryDecaysTowardNone() {
        seedStrong(farmB, 1_000L);
        LagCorrelation step = tracker.update(
                farmB,
                LagCorrelation.NONE,
                100,
                ServerPressure.NORMAL,
                settings,
                2_000L
        );
        assertEquals(LagCorrelation.POSSIBLE, step);
        LagCorrelation gone = tracker.update(
                farmB,
                LagCorrelation.NONE,
                100,
                ServerPressure.NORMAL,
                settings,
                3_000L
        );
        assertEquals(LagCorrelation.NONE, gone);
    }

    @Test
    void activityStopDecaysCorrelation() {
        seedStrong(farmB, 1_000L);
        LagCorrelation step = tracker.update(
                farmB,
                LagCorrelation.NONE,
                0,
                ServerPressure.CRITICAL,
                settings,
                2_000L
        );
        assertEquals(LagCorrelation.POSSIBLE, step);
        LagCorrelation gone = tracker.update(
                farmB,
                LagCorrelation.NONE,
                0,
                ServerPressure.CRITICAL,
                settings,
                3_000L
        );
        assertEquals(LagCorrelation.NONE, gone);
    }

    @Test
    void farmAStableHighActivityDoesNotInheritStrong() {
        for (int i = 0; i < 8; i++) {
            LagCorrelation value = tracker.update(
                    farmA,
                    LagCorrelation.NONE,
                    100,
                    i < 4 ? ServerPressure.NORMAL : ServerPressure.CRITICAL,
                    settings,
                    1_000L + i * 1_000L
            );
            assertEquals(LagCorrelation.NONE, value);
        }
        seedStrong(farmB, 10_000L);
        assertEquals(
                LagCorrelation.NONE,
                tracker.update(farmA, LagCorrelation.NONE, 100, ServerPressure.CRITICAL, settings, 11_000L)
        );
        assertEquals(
                LagCorrelation.STRONG,
                tracker.update(farmB, LagCorrelation.NONE, 100, ServerPressure.CRITICAL, settings, 11_000L)
        );
    }

    @Test
    void strongHoldEventuallyExpires() {
        seedStrong(farmB, 1_000L);
        long afterStrong = 1_000L + settings.strongHoldSeconds() * 1000L + 50L;
        LagCorrelation afterHold = tracker.update(
                farmB,
                LagCorrelation.NONE,
                100,
                ServerPressure.CRITICAL,
                settings,
                afterStrong
        );
        assertEquals(LagCorrelation.POSSIBLE, afterHold);
        long afterPossible = afterHold == LagCorrelation.POSSIBLE
                ? afterStrong + settings.possibleHoldSeconds() * 1000L + 50L
                : afterStrong;
        LagCorrelation expired = tracker.update(
                farmB,
                LagCorrelation.NONE,
                100,
                ServerPressure.CRITICAL,
                settings,
                afterPossible
        );
        assertEquals(LagCorrelation.NONE, expired);
    }

    private void seedStrong(ChunkKey key, long nowMs) {
        assertEquals(
                LagCorrelation.STRONG,
                tracker.update(key, LagCorrelation.STRONG, 100, ServerPressure.CRITICAL, settings, nowMs)
        );
    }
}
