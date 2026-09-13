package com.npucraft.farmguard.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.npucraft.farmguard.TestData;
import com.npucraft.farmguard.config.FarmGuardSettings;
import com.npucraft.farmguard.model.OperatingMode;
import com.npucraft.farmguard.model.RiskAssessment;
import com.npucraft.farmguard.model.RiskLevel;
import com.npucraft.farmguard.model.ServerMetrics;
import com.npucraft.farmguard.model.ServerPressure;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DebugDiagnosticsFlapTest {

    @TempDir
    Path temp;

    @Test
    void debugOffDoesNotAccumulateTransitionsOrChunkLoads() {
        DebugLogService log = new DebugLogService(temp.toFile(), Logger.getAnonymousLogger());
        DebugWorldCounters counters = new DebugWorldCounters();
        DebugDiagnostics diagnostics = new DebugDiagnostics(log, new DebugActionCounters(), counters);
        counters.setEnabled(true);
        counters.onLoad(true);
        tick(diagnostics, 0L, RiskLevel.NONE);
        tick(diagnostics, 1_000L, RiskLevel.LOW);
        assertEquals(0, diagnostics.pendingTransitionChunks());
        counters.setEnabled(false);
        assertEquals(0L, counters.drain().chunkLoads());
    }

    @Test
    void highTransitionIsWrittenImmediatelyAndLowFlapsBecomeSummary() throws Exception {
        DebugLogService log = new DebugLogService(temp.toFile(), Logger.getAnonymousLogger());
        DebugWorldCounters counters = new DebugWorldCounters();
        DebugActionCounters actions = new DebugActionCounters();
        DebugDiagnostics diagnostics = new DebugDiagnostics(log, actions, counters);
        FarmGuardSettings settings = FarmGuardSettings.builder()
                .debugLogEnabled(true)
                .debugSnapshotIntervalSeconds(300)
                .build();
        log.start(settings);
        actions.setEnabled(true);
        counters.setEnabled(true);
        diagnostics.onWriterStarted(true, "paper", "21", OperatingMode.MONITOR, 0);
        long start = 10_000L;
        tick(diagnostics, settings, start, RiskLevel.NONE);
        for (int i = 0; i < 8; i++) {
            tick(diagnostics, settings, start + (i + 1) * 1_000L, i % 2 == 0 ? RiskLevel.LOW : RiskLevel.NONE);
        }
        assertTrue(diagnostics.pendingTransitionChunks() >= 1);
        tick(diagnostics, settings, start + 9_000L, RiskLevel.HIGH);
        assertEquals(0, diagnostics.pendingTransitionChunks());
        diagnostics.flushPendingSummaries();
        log.stop();
        String text = Files.readString(temp.resolve("logs").resolve("debug.log"));
        assertTrue(text.contains("\"type\":\"risk_transition\""));
        assertTrue(text.contains("\"to\":\"HIGH\""));
        assertTrue(text.contains("\"type\":\"risk_transition_summary\""));
        assertTrue(text.contains("\"type\":\"server_snapshot\""));
        assertTrue(text.contains("\"onlinePlayers\""));
        assertTrue(text.contains("\"chunkLoads\""));
        assertTrue(text.contains("\"playersByWorld\""));
        assertTrue(text.contains("\"trackedChunksDelta\""));
        assertFalse(text.contains("漏斗"));
    }

    @Test
    void shutdownFlushWritesPendingSummary() throws Exception {
        DebugLogService log = new DebugLogService(temp.toFile(), Logger.getAnonymousLogger());
        DebugDiagnostics diagnostics = new DebugDiagnostics(log, new DebugActionCounters(), new DebugWorldCounters());
        FarmGuardSettings settings = FarmGuardSettings.builder().debugLogEnabled(true).debugSnapshotIntervalSeconds(300).build();
        log.start(settings);
        diagnostics.onWriterStarted(false, "paper", "21", OperatingMode.MONITOR, 0);
        tick(diagnostics, settings, 1_000L, RiskLevel.NONE);
        tick(diagnostics, settings, 2_000L, RiskLevel.LOW);
        diagnostics.flushPendingSummaries();
        log.stop();
        String text = Files.readString(temp.resolve("logs").resolve("debug.log"));
        assertTrue(text.contains("risk_transition_summary"));
        assertEquals(0, diagnostics.pendingTransitionChunks());
    }

    private void tick(DebugDiagnostics diagnostics, long now, RiskLevel level) {
        tick(diagnostics, FarmGuardSettings.defaults(), now, level);
    }

    private void tick(DebugDiagnostics diagnostics, FarmGuardSettings settings, long now, RiskLevel level) {
        var key = TestData.chunk(9, 34);
        RiskAssessment assessment = TestData.risk(key, level, 20.0, TestData.snapshot(key, 40, 0, 0, 0));
        diagnostics.onTick(
                settings,
                new ServerMetrics(now, 20.0, 4.0, 4.0, ServerPressure.NORMAL, false),
                List.of(assessment),
                List.of(assessment),
                Map.of(),
                List.of(),
                List.of(),
                12,
                1,
                0,
                0,
                null,
                false,
                null,
                false,
                null,
                1L,
                OperatingMode.MONITOR,
                3,
                Map.of("world", 3)
        );
    }
}
