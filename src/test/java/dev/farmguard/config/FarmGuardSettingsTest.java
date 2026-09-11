package dev.farmguard.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.farmguard.model.OperatingMode;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class FarmGuardSettingsTest {

    @Test
    void builderSanitizesImpossibleWindowsAndLimits() {
        FarmGuardSettings settings = FarmGuardSettings.builder()
                .shortWindowSeconds(40)
                .longWindowSeconds(5)
                .maxTrackedChunks(0)
                .inactiveTtlSeconds(0)
                .notificationCooldownSeconds(-8)
                .censusPerCycle(0)
                .build();
        assertTrue(settings.shortWindowSeconds() <= settings.longWindowSeconds());
        assertEquals(4096, settings.maxTrackedChunks());
        assertEquals(180, settings.inactiveTtlSeconds());
        assertEquals(45, settings.notificationCooldownSeconds());
        assertEquals(4, settings.censusPerCycle());
    }

    @Test
    void defaultSpawnThrottleIsConservative() {
        FarmGuardSettings settings = FarmGuardSettings.defaults();
        assertTrue(settings.spawnReasonThrottled("SPAWNER"));
        assertTrue(settings.spawnReasonThrottled("TRIAL_SPAWNER"));
        assertTrue(settings.spawnReasonThrottled("SLIME_SPLIT"));
        assertFalse(settings.spawnReasonThrottled("NATURAL"));
        assertFalse(settings.spawnReasonThrottled("COMMAND"));
        assertFalse(settings.spawnReasonThrottled("CUSTOM"));
        assertFalse(settings.spawnReasonThrottled("RAID"));
        assertFalse(settings.spawnReasonThrottled("BREEDING"));
        assertFalse(settings.spawnReasonThrottled("SPAWNER_EGG"));
        assertFalse(settings.spawnReasonThrottled("DEFAULT"));
    }

    @Test
    void loaderFallsBackOnInvalidYamlValues() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("mode", "NOPE");
        yaml.set("monitoring.short-window-seconds", 40);
        yaml.set("monitoring.long-window-seconds", 5);
        yaml.set("monitoring.max-tracked-chunks", 0);
        yaml.set("monitoring.inactive-ttl-seconds", 0);
        yaml.set("notifications.cooldown-seconds", -3);
        yaml.set("server-pressure.warning-mspt", Double.NaN);
        yaml.set("protection.throttle-rates.hopper", -4);
        ConfigLoader.Result result = new ConfigLoader(Logger.getAnonymousLogger()).load(yaml);
        assertTrue(result.hadErrors());
        assertEquals(OperatingMode.MONITOR, result.settings().mode());
        assertTrue(result.settings().shortWindowSeconds() <= result.settings().longWindowSeconds());
        assertEquals(4096, result.settings().maxTrackedChunks());
        assertEquals(180, result.settings().inactiveTtlSeconds());
        assertEquals(45, result.settings().notificationCooldownSeconds());
        assertEquals(35.0, result.settings().warningMspt());
        assertEquals(8, result.settings().throttleRate(dev.farmguard.model.ThrottleType.HOPPER, false));
    }
}
