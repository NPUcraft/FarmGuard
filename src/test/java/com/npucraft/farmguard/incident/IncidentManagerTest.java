package com.npucraft.farmguard.incident;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.npucraft.farmguard.config.FarmGuardSettings;
import java.util.List;
import org.junit.jupiter.api.Test;

class IncidentManagerTest {

    @Test
    void pressureWaveIsOneIncident() {
        IncidentManager manager = new IncidentManager();
        FarmGuardSettings settings = FarmGuardSettings.defaults();
        long t = System.currentTimeMillis() - 30_000L;
        manager.onLagStarted(t, 50.0, 16.0, settings);
        manager.onLagSample(72.0, 11.0, List.of("world (1,1) [HOPPER]"), settings);
        manager.addAction("STARTED world (1,1) EMERGENCY");
        manager.onLagSample(52.0, 15.0, List.of("world (1,1) [HOPPER]"), settings);
        var closed = manager.onRecovered(t + 30_000L, settings);
        assertEquals(1, manager.history().size());
        assertNull(manager.current());
        assertTrue(closed.recovered());
        assertEquals(t, closed.startMs());
        assertEquals(t + 30_000L, closed.endMs());
        assertEquals(72.0, closed.peakMspt());
        assertEquals(11.0, closed.lowestTps());
        assertEquals(1, closed.topSuspects().size());
        assertEquals(1, closed.protectionActions().size());
    }
}
