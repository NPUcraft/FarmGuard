package dev.farmguard.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.farmguard.TestData;
import dev.farmguard.config.FarmGuardSettings;
import dev.farmguard.model.LagCorrelation;
import dev.farmguard.model.OperatingMode;
import dev.farmguard.model.ProtectionLevel;
import dev.farmguard.model.RiskAssessment;
import dev.farmguard.model.RiskLevel;
import dev.farmguard.model.ServerMetrics;
import dev.farmguard.model.ServerPressure;
import dev.farmguard.model.ThrottleType;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProtectionManagerTest {

    private final FarmGuardSettings settings = FarmGuardSettings.builder()
            .protectionEnterSeconds(2)
            .protectionExitSeconds(3)
            .protectionCooldownSeconds(1)
            .build();

    @Test
    void monitorModeNeverAppliesGameplayLimits() {
        ProtectionManager manager = new ProtectionManager();
        RiskAssessment assessment = assessment(RiskLevel.CRITICAL, LagCorrelation.STRONG);
        ServerMetrics lag = metrics(ServerPressure.CRITICAL);
        long t = 100_000L;
        manager.tick(List.of(assessment), lag, OperatingMode.MONITOR, settings, key -> false, t);
        manager.tick(List.of(assessment), lag, OperatingMode.MONITOR, settings, key -> false, t + 3_000L);
        assertEquals(ProtectionLevel.NORMAL, manager.applied(assessment.chunk()));
        assertEquals(ProtectionLevel.EMERGENCY, manager.stateOf(assessment.chunk()).recommended());
        assertFalse(manager.shouldThrottle(assessment.chunk(), ThrottleType.HOPPER, settings, t + 3_000L));
    }

    @Test
    void protectModeEscalatesThenRecoversGradually() {
        ProtectionManager manager = new ProtectionManager();
        RiskAssessment hot = assessment(RiskLevel.CRITICAL, LagCorrelation.STRONG);
        ServerMetrics lag = metrics(ServerPressure.CRITICAL);
        long t = 200_000L;
        manager.tick(List.of(hot), lag, OperatingMode.PROTECT, settings, key -> false, t);
        manager.tick(List.of(hot), lag, OperatingMode.PROTECT, settings, key -> false, t + 3_000L);
        assertTrue(manager.applied(hot.chunk()).restrictsGameplay());

        RiskAssessment calm = assessment(RiskLevel.NONE, LagCorrelation.NONE);
        ServerMetrics healthy = metrics(ServerPressure.NORMAL);
        ProtectionLevel after = manager.applied(hot.chunk());
        for (int i = 1; i <= 12; i++) {
            manager.tick(List.of(calm), healthy, OperatingMode.PROTECT, settings, key -> false, t + 3_000L + i * 4_000L);
            after = manager.applied(hot.chunk());
        }
        assertEquals(ProtectionLevel.NORMAL, after);
    }

    @Test
    void whitelistBlocksAutomaticRestriction() {
        ProtectionManager manager = new ProtectionManager();
        RiskAssessment hot = assessment(RiskLevel.CRITICAL, LagCorrelation.STRONG);
        ServerMetrics lag = metrics(ServerPressure.CRITICAL);
        long t = 300_000L;
        manager.tick(List.of(hot), lag, OperatingMode.PROTECT, settings, key -> true, t);
        manager.tick(List.of(hot), lag, OperatingMode.PROTECT, settings, key -> true, t + 5_000L);
        assertEquals(ProtectionLevel.NORMAL, manager.applied(hot.chunk()));
    }

    @Test
    void protectToMonitorReleasesImmediately() {
        ProtectionManager manager = new ProtectionManager();
        RiskAssessment hot = assessment(RiskLevel.CRITICAL, LagCorrelation.STRONG);
        ServerMetrics lag = metrics(ServerPressure.CRITICAL);
        long t = 400_000L;
        manager.tick(List.of(hot), lag, OperatingMode.PROTECT, settings, key -> false, t);
        manager.tick(List.of(hot), lag, OperatingMode.PROTECT, settings, key -> false, t + 3_000L);
        assertTrue(manager.shouldThrottle(hot.chunk(), ThrottleType.HOPPER, settings, t + 3_000L)
                || manager.applied(hot.chunk()).restrictsGameplay());
        manager.setRestrictionsEnabled(false);
        assertFalse(manager.shouldThrottle(hot.chunk(), ThrottleType.HOPPER, settings, t + 3_001L));
        assertFalse(manager.applied(hot.chunk()).restrictsGameplay());
    }

    @Test
    void redstoneStaysUntouchedAtThrottleLevel() {
        FarmGuardSettings conservative = FarmGuardSettings.builder()
                .protectionEnterSeconds(1)
                .protectionCooldownSeconds(0)
                .throttleRate(ThrottleType.HOPPER, 0)
                .build();
        ProtectionManager manager = new ProtectionManager();
        RiskAssessment hot = assessment(RiskLevel.HIGH, LagCorrelation.NONE);
        ServerMetrics pressure = metrics(ServerPressure.HIGH);
        long t = 500_000L;
        manager.tick(List.of(hot), pressure, OperatingMode.PROTECT, conservative, key -> false, t);
        manager.tick(List.of(hot), pressure, OperatingMode.PROTECT, conservative, key -> false, t + 1_000L);
        assertEquals(ProtectionLevel.THROTTLE, manager.applied(hot.chunk()));
        assertTrue(manager.shouldThrottle(hot.chunk(), ThrottleType.HOPPER, conservative, t + 1_000L));
        assertFalse(manager.shouldThrottle(hot.chunk(), ThrottleType.REDSTONE, conservative, t + 1_000L));
        assertFalse(manager.shouldThrottle(hot.chunk(), ThrottleType.PISTON, conservative, t + 1_000L));
        assertFalse(manager.shouldThrottle(hot.chunk(), ThrottleType.ITEM, conservative, t + 1_000L));
        assertFalse(manager.shouldThrottle(hot.chunk(), ThrottleType.MINECART, conservative, t + 1_000L));
    }

    @Test
    void tokenBucketIsDeterministic() {
        TokenBucket bucket = new TokenBucket();
        int allowed = 0;
        long now = 0L;
        for (int i = 0; i < 10; i++) {
            if (bucket.tryConsume(now, 2)) {
                allowed++;
            }
        }
        assertEquals(2, allowed);
        now += 1000L;
        assertTrue(bucket.tryConsume(now, 2));
    }

    private static RiskAssessment assessment(RiskLevel level, LagCorrelation correlation) {
        var snapshot = TestData.snapshot(TestData.chunk(4, 4), 80, 10, 50, 40);
        double score = switch (level) {
            case CRITICAL -> 90;
            case HIGH -> 70;
            case MEDIUM -> 40;
            case LOW -> 20;
            case NONE -> 0;
        };
        return new RiskAssessment(snapshot.key(), 50, score, 1.3, level, correlation, List.of(), List.of(), snapshot);
    }

    private static ServerMetrics metrics(ServerPressure pressure) {
        boolean lag = pressure.atLeast(ServerPressure.HIGH);
        double mspt = switch (pressure) {
            case CRITICAL -> 70;
            case HIGH -> 50;
            case WARNING -> 36;
            case NORMAL -> 18;
        };
        return new ServerMetrics(1L, lag ? 14.0 : 20.0, mspt, mspt, pressure, lag);
    }
}
