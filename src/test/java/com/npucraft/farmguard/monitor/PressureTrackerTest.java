package com.npucraft.farmguard.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.npucraft.farmguard.config.FarmGuardSettings;
import com.npucraft.farmguard.model.ServerPressure;
import org.junit.jupiter.api.Test;

class PressureTrackerTest {

    private final FarmGuardSettings settings = FarmGuardSettings.builder()
            .pressureEnterSeconds(3)
            .pressureExitSeconds(4)
            .pressureCooldownSeconds(2)
            .build();

    @Test
    void briefSpikeDoesNotEnterCritical() {
        PressureTracker tracker = new PressureTracker();
        long t = 10_000L;
        tracker.tick(t, 80.0, 10.0, settings);
        tracker.tick(t + 1000L, 80.0, 10.0, settings);
        assertEquals(ServerPressure.NORMAL, tracker.current());
    }

    @Test
    void sustainedHighMsptEntersThenRequiresHysteresisToLeave() {
        PressureTracker tracker = new PressureTracker();
        long t = 20_000L;
        ServerPressure pressure = ServerPressure.NORMAL;
        for (int i = 0; i < 8; i++) {
            pressure = tracker.tick(t + i * 1000L, 70.0, 11.0, settings);
        }
        assertEquals(ServerPressure.CRITICAL, pressure);
        assertTrue(tracker.lagIncident());

        ServerPressure afterDrop = pressure;
        for (int i = 0; i < 3; i++) {
            afterDrop = tracker.tick(t + 12_000L + i * 1000L, 20.0, 20.0, settings);
        }
        assertEquals(ServerPressure.CRITICAL, afterDrop);

        for (int i = 0; i < 8; i++) {
            afterDrop = tracker.tick(t + 20_000L + i * 1000L, 20.0, 20.0, settings);
        }
        assertTrue(afterDrop.ordinal() < ServerPressure.CRITICAL.ordinal());
    }

    @Test
    void recoversOneLevelAtATime() {
        PressureTracker tracker = new PressureTracker();
        long t = 30_000L;
        for (int i = 0; i < 8; i++) {
            tracker.tick(t + i * 1000L, 80.0, 10.0, settings);
        }
        assertEquals(ServerPressure.CRITICAL, tracker.current());
        ServerPressure next = ServerPressure.CRITICAL;
        for (int i = 0; i < 20 && next == ServerPressure.CRITICAL; i++) {
            next = tracker.tick(t + 20_000L + i * 1000L, 18.0, 20.0, settings);
        }
        assertEquals(ServerPressure.HIGH, next);
    }

    @Test
    void msptCriticalIsNotLoweredByHealthyTps() {
        PressureTracker tracker = new PressureTracker();
        FarmGuardSettings settings = FarmGuardSettings.builder()
                .pressureEnterSeconds(1)
                .pressureCooldownSeconds(0)
                .build();
        tracker.tick(1_000L, 70.0, 20.0, settings);
        assertEquals(ServerPressure.CRITICAL, tracker.tick(2_000L, 70.0, 20.0, settings));
    }

    @Test
    void highMsptBoundaryUsesInclusiveEnterThreshold() {
        FarmGuardSettings settings = FarmGuardSettings.builder()
                .warningMspt(35.0)
                .highMspt(50.0)
                .criticalMspt(65.0)
                .pressureEnterSeconds(1)
                .pressureCooldownSeconds(0)
                .build();
        PressureTracker low = new PressureTracker();
        low.tick(1_000L, 49.9, 20.0, settings);
        assertEquals(ServerPressure.WARNING, low.tick(2_000L, 49.9, 20.0, settings));

        PressureTracker exact = new PressureTracker();
        exact.tick(1_000L, 50.0, 20.0, settings);
        assertEquals(ServerPressure.HIGH, exact.tick(2_000L, 50.0, 20.0, settings));

        PressureTracker above = new PressureTracker();
        above.tick(1_000L, 50.1, 20.0, settings);
        assertEquals(ServerPressure.HIGH, above.tick(2_000L, 50.1, 20.0, settings));
    }
}
