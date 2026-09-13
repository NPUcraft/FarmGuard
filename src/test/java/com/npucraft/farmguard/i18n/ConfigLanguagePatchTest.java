package com.npucraft.farmguard.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigLanguagePatchTest {

    @TempDir
    Path temp;

    @Test
    void writesLanguageWithoutDestroyingMode() throws Exception {
        Path file = temp.resolve("config.yml");
        Files.writeString(file, """
                mode: MONITOR

                monitoring:
                  debug: false
                """, StandardCharsets.UTF_8);
        assertTrue(ConfigLanguagePatch.write(file, "en_US", Logger.getAnonymousLogger()));
        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(text.contains("mode: MONITOR"));
        assertTrue(text.contains("language: en_US"));
        assertTrue(ConfigLanguagePatch.write(file, "zh_CN", Logger.getAnonymousLogger()));
        text = Files.readString(file, StandardCharsets.UTF_8);
        assertEquals(1, text.split("language:", -1).length - 1);
        assertTrue(text.contains("language: zh_CN"));
    }
}
