package com.npucraft.farmguard.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MessagesTest {

    @TempDir
    Path tempDir;

    @Test
    void missingKeysAreFilledFromBundledDefaultsWithoutOverwritingCustomText() throws Exception {
        Path file = tempDir.resolve("messages.yml");
        Files.writeString(file, """
                prefix: "<gray>[CUSTOM]</gray> "
                player-only: "<red>stay custom</red>"
                """);
        YamlConfiguration bundled = new YamlConfiguration();
        bundled.set("prefix", "<gray>[<green>FarmGuard</green>]</gray> ");
        bundled.set("player-only", "<red>该子命令需要在游戏内执行。</red>");
        bundled.set("inspect-console-usage", "<red>控制台请使用 /fg inspect 世界名 x z。</red>");

        Messages messages = new Messages();
        int added = messages.load(file.toFile(), Logger.getAnonymousLogger(), bundled);

        assertEquals(1, added);
        assertEquals("<red>控制台请使用 /fg inspect 世界名 x z。</red>", messages.raw("inspect-console-usage"));
        assertEquals("<red>stay custom</red>", messages.raw("player-only"));
        assertEquals("<gray>[CUSTOM]</gray> ", messages.raw("prefix"));
        String saved = Files.readString(file);
        assertTrue(saved.contains("inspect-console-usage"));
        assertTrue(saved.contains("stay custom"));
        assertFalse(saved.contains("该子命令需要在游戏内执行"));
    }
}
