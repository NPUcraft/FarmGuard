package dev.farmguard.incident;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HistoryStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void corruptHistoryIsQuarantinedAndIgnored() throws Exception {
        Path file = tempDir.resolve("incidents.yml");
        Files.writeString(file, "this is not yaml {{{{ leftover");
        HistoryStore store = new HistoryStore(file.toFile(), Logger.getAnonymousLogger());
        assertTrue(store.load().isEmpty());
        assertFalse(Files.exists(file));
        try (var stream = Files.list(tempDir)) {
            assertTrue(stream.anyMatch(path -> path.getFileName().toString().contains("corrupt")));
        }
    }
}
