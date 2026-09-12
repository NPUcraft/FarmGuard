package dev.farmguard.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.farmguard.model.OperatingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StateStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void corruptStateUsesSafeDefaults() throws Exception {
        Path file = tempDir.resolve("state.yml");
        Files.writeString(file, "???? not a state file");
        StateStore store = new StateStore(file.toFile(), Logger.getAnonymousLogger());
        StateStore.LoadedState loaded = store.load(OperatingMode.MONITOR);
        assertEquals(OperatingMode.MONITOR, loaded.mode());
        assertTrue(loaded.chunks().isEmpty());
        assertTrue(loaded.clusters().isEmpty());
        assertFalse(Files.exists(file));
    }
}
