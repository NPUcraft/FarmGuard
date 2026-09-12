package com.npucraft.farmguard.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuarantineTest {

    @TempDir
    Path tempDir;

    @Test
    void pruneKeepsOnlyTheNewestBackups() throws Exception {
        Path parent = tempDir;
        for (int i = 0; i < 10; i++) {
            Path backup = parent.resolve("state.yml.corrupt-" + i);
            Files.writeString(backup, "x" + i);
            Files.setLastModifiedTime(backup, FileTime.fromMillis(1_700_000_000_000L + i * 1_000L));
        }
        Quarantine.prune(parent.toFile(), "state.yml.corrupt-", 8, Logger.getAnonymousLogger());
        try (var stream = Files.list(parent)) {
            long remaining = stream.filter(path -> path.getFileName().toString().startsWith("state.yml.corrupt-")).count();
            assertEquals(8, remaining);
        }
        assertTrue(Files.exists(parent.resolve("state.yml.corrupt-9")));
        assertTrue(Files.exists(parent.resolve("state.yml.corrupt-2")));
    }
}
