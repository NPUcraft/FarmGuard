package com.npucraft.farmguard.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.npucraft.farmguard.config.FarmGuardSettings;
import com.npucraft.farmguard.model.OperatingMode;
import java.util.List;
import org.junit.jupiter.api.Test;

class DebugDiagnosticsTest {

    @Test
    void changedSectionsStayStableAndDoNotDumpSecrets() {
        FarmGuardSettings previous = FarmGuardSettings.defaults();
        FarmGuardSettings next = FarmGuardSettings.builder()
                .debugLogEnabled(true)
                .debugSnapshotIntervalSeconds(15)
                .mode(OperatingMode.PROTECT)
                .build();
        List<String> changed = DebugDiagnostics.changedSections(previous, next);
        assertTrue(changed.contains("debug-log"));
        assertTrue(changed.contains("mode"));
        assertEquals(1, changed.stream().filter("debug-log"::equals).count());
    }

    @Test
    void round1IsStableForLogFields() {
        assertEquals(18.4, DebugDiagnostics.round1(18.41), 0.0001);
        assertEquals(20.0, DebugDiagnostics.round1(20.0), 0.0001);
        assertEquals(0.0, DebugDiagnostics.round1(Double.NaN), 0.0001);
    }
}
