package com.npucraft.farmguard.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class LanguageSettingsTest {

    @Test
    void defaultLanguageIsZhCn() {
        assertEquals("zh_CN", FarmGuardSettings.defaults().language());
    }

    @Test
    void loaderNormalizesAndRejectsInvalidLanguage() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("language", "en-us");
        ConfigLoader.Result ok = new ConfigLoader(Logger.getAnonymousLogger()).load(yaml);
        assertEquals("en_US", ok.settings().language());

        yaml.set("language", "abc");
        ConfigLoader.Result bad = new ConfigLoader(Logger.getAnonymousLogger()).load(yaml);
        assertTrue(bad.hadErrors());
        assertEquals("zh_CN", bad.settings().language());
    }
}
