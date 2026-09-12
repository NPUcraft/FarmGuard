package com.npucraft.farmguard.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.npucraft.farmguard.config.FarmGuardSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DebugLogServiceTest {

    @TempDir
    Path temp;

    @Test
    void disabledIsNoOpAndDoesNotCreateWriter() {
        DebugLogService service = new DebugLogService(temp.toFile(), Logger.getAnonymousLogger());
        assertEquals(4096, DebugLogService.DEFAULT_QUEUE_CAPACITY);
        assertFalse(service.enabled());
        assertFalse(service.writerAlive());
        assertFalse(service.offer(DebugLogRecord.normal("server_snapshot", Map.of("tps", 20.0))));
        assertFalse(Files.exists(temp.resolve("logs").resolve("debug.log")));
    }

    @Test
    void startWritesSchemaSessionAndStopsCleanly() throws Exception {
        DebugLogService service = new DebugLogService(temp.toFile(), Logger.getAnonymousLogger());
        FarmGuardSettings settings = FarmGuardSettings.builder().debugLogEnabled(true).build();
        service.start(settings);
        assertTrue(service.enabled());
        assertTrue(service.writerAlive());
        String session = service.sessionId();
        assertEquals(8, session.length());
        assertTrue(session.matches("[0-9a-f]{8}"));
        service.offer(DebugLogRecord.high("plugin_start", Map.of(
                "pluginVersion", "1.0.0-beta.1",
                "mode", "MONITOR"
        )));
        service.offer(DebugLogRecord.normal("server_snapshot", Map.of(
                "tps", 20.0,
                "mspt", 18.4,
                "droppedLogEntries", 0L
        )));
        service.stop();
        assertFalse(service.writerAlive());
        assertFalse(service.enabled());
        assertTrue(JsonlFileGate.validate(service.logFile().toPath()).isEmpty());
        String text = Files.readString(service.logFile().toPath());
        assertTrue(text.contains("\"schema\":1"));
        assertTrue(text.contains("\"session\":\"" + session + "\""));
        assertTrue(text.contains("\"type\":\"plugin_start\""));
        assertTrue(text.contains("\"type\":\"server_snapshot\""));
        assertTrue(text.matches("(?s).*\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*"));
        assertTrue(text.contains("+") || text.contains("Z"));
        assertTrue(DebugLogRecord.SCHEMA == 1);
    }

    @Test
    void queueOverflowDropsNewestNormalAndCountsDrops() throws Exception {
        DebugLogService service = new DebugLogService(temp.toFile(), Logger.getAnonymousLogger(), 8);
        service.start(FarmGuardSettings.builder().debugLogEnabled(true).build());
        int acceptedHigh = 0;
        for (int i = 0; i < 40; i++) {
            service.offer(DebugLogRecord.normal("server_snapshot", Map.of("n", i)));
        }
        for (int i = 0; i < 6; i++) {
            if (service.offer(DebugLogRecord.high("server_pressure_transition", Map.of("from", "HIGH", "to", "CRITICAL")))) {
                acceptedHigh++;
            }
        }
        service.stop();
        assertTrue(service.droppedLogEntries() > 0);
        assertTrue(acceptedHigh > 0);
        String text = Files.readString(service.logFile().toPath());
        assertTrue(text.contains("server_pressure_transition"));
    }

    @Test
    void rotationRespectsMaxFiles() throws Exception {
        DebugLogService service = new DebugLogService(temp.toFile(), Logger.getAnonymousLogger());
        service.start(FarmGuardSettings.builder().debugLogEnabled(true).debugMaxFiles(3).build());
        service.overrideLimitsForTest(180, 3);
        String payload = "x".repeat(80);
        for (int i = 0; i < 40; i++) {
            service.offer(DebugLogRecord.normal("server_snapshot", Map.of("blob", payload, "n", i)));
        }
        service.stop();
        Path logs = temp.resolve("logs");
        assertTrue(Files.exists(logs.resolve("debug.log")));
        try (Stream<Path> files = Files.list(logs)) {
            List<Path> logFiles = files.filter(path -> path.getFileName().toString().startsWith("debug")).toList();
            assertTrue(logFiles.size() <= 3);
            assertTrue(logFiles.size() >= 2);
            assertFalse(Files.exists(logs.resolve("debug.3.log")));
            for (Path file : logFiles) {
                List<String> errors = JsonlFileGate.validate(file);
                assertTrue(errors.isEmpty(), file.getFileName() + " " + errors);
            }
        }
    }

    @Test
    void shutdownWritesCompleteLastRecordWithNewline() throws Exception {
        DebugLogService service = new DebugLogService(temp.toFile(), Logger.getAnonymousLogger());
        service.start(FarmGuardSettings.builder().debugLogEnabled(true).build());
        assertTrue(service.offer(DebugLogRecord.high("incident_start", Map.of("incidentId", "abcd1234"))));
        assertTrue(service.offer(DebugLogRecord.high("internal_error", Map.of("component", "Test", "error", "IOException"))));
        service.stop();
        String text = Files.readString(service.logFile().toPath());
        assertTrue(text.endsWith("\n") || text.endsWith("\r\n"));
        assertTrue(JsonlFileGate.validate(service.logFile().toPath()).isEmpty());
        assertTrue(text.contains("\"type\":\"internal_error\""));
        String last = text.stripTrailing();
        last = last.substring(last.lastIndexOf('\n') < 0 ? 0 : last.lastIndexOf('\n') + 1);
        assertTrue(last.contains("internal_error"));
        assertFalse(last.contains("{") && !last.trim().endsWith("}"));
    }

    @Test
    void unwritableLogsDirectoryDoesNotThrowOrLeakWriter() throws Exception {
        Files.writeString(temp.resolve("logs"), "not-a-directory");
        DebugLogService service = new DebugLogService(temp.toFile(), Logger.getAnonymousLogger());
        service.start(FarmGuardSettings.builder().debugLogEnabled(true).build());
        long deadline = System.currentTimeMillis() + 2000L;
        while (service.writerAlive() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        assertFalse(service.isActive());
        assertFalse(service.writerAlive());
        assertEquals(0, JsonlFileGate.debugLogThreadCount());
        assertFalse(service.offer(DebugLogRecord.high("internal_error", Map.of("component", "x"))));
    }

    @Test
    void rotationFailureKeepsWriterAlive() throws Exception {
        DebugLogService service = new DebugLogService(temp.toFile(), Logger.getAnonymousLogger());
        service.start(FarmGuardSettings.builder().debugLogEnabled(true).debugMaxFiles(3).build());
        service.overrideLimitsForTest(120, 3);
        service.failNextRotationForTest();
        String payload = "y".repeat(90);
        for (int i = 0; i < 20; i++) {
            service.offer(DebugLogRecord.normal("server_snapshot", Map.of("blob", payload, "n", i)));
        }
        Thread.sleep(400);
        assertTrue(service.writerAlive(), "rotation IOException must not kill the writer silently");
        assertTrue(service.offer(DebugLogRecord.high("server_pressure_transition", Map.of("from", "HIGH", "to", "CRITICAL"))));
        service.stop();
        assertTrue(JsonlFileGate.validate(service.logFile().toPath()).isEmpty());
        assertTrue(Files.readString(service.logFile().toPath()).contains("server_pressure_transition"));
    }

    @Test
    void fiveStartStopCyclesLeaveNoWriterThread() throws Exception {
        DebugLogService service = new DebugLogService(temp.toFile(), Logger.getAnonymousLogger());
        FarmGuardSettings on = FarmGuardSettings.builder().debugLogEnabled(true).build();
        for (int i = 0; i < 5; i++) {
            service.start(on);
            assertEquals(1, JsonlFileGate.debugLogThreadCount(), "cycle " + i);
            assertTrue(service.isActive());
            service.offer(DebugLogRecord.high("session_start", Map.of("n", i)));
            service.stop();
            assertEquals(0, JsonlFileGate.debugLogThreadCount(), "cycle " + i + " after stop");
            assertFalse(service.isActive());
        }
        assertTrue(JsonlFileGate.validate(service.logFile().toPath()).isEmpty());
    }

    @Test
    void reloadOffToOnThenOnToOff() throws Exception {
        DebugLogService service = new DebugLogService(temp.toFile(), Logger.getAnonymousLogger());
        FarmGuardSettings on = FarmGuardSettings.builder().debugLogEnabled(true).build();
        FarmGuardSettings off = FarmGuardSettings.builder().debugLogEnabled(false).build();
        assertFalse(service.offer(DebugLogRecord.normal("server_snapshot", Map.of("n", 1))));
        service.start(on);
        assertTrue(service.offer(DebugLogRecord.high("debug_logging_enabled", Map.of("mode", "MONITOR"))));
        service.stop();
        long sizeAfterStop = Files.size(service.logFile().toPath());
        assertFalse(service.offer(DebugLogRecord.normal("server_snapshot", Map.of("n", 2))));
        Thread.sleep(50);
        assertEquals(sizeAfterStop, Files.size(service.logFile().toPath()));
        service.start(off);
        service.start(on);
        assertTrue(service.writerAlive());
        service.offer(DebugLogRecord.high("debug_logging_disabled", Map.of("mode", "MONITOR")));
        service.stop();
        assertFalse(service.writerAlive());
    }
}
