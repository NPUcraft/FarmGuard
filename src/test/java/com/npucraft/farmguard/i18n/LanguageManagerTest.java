package com.npucraft.farmguard.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.npucraft.farmguard.model.ClusterType;
import com.npucraft.farmguard.model.LagCorrelation;
import com.npucraft.farmguard.model.MetricType;
import com.npucraft.farmguard.model.ProtectionLevel;
import com.npucraft.farmguard.model.RiskLevel;
import com.npucraft.farmguard.model.RiskReason;
import com.npucraft.farmguard.model.ServerPressure;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LanguageManagerTest {

    @TempDir
    Path temp;

    @Test
    void defaultLocaleIsZhCnAndUtf8Loads() {
        LanguageManager manager = load("zh_CN");
        assertEquals("zh_CN", manager.currentLocale());
        assertEquals("简体中文", manager.currentDisplayName());
        assertEquals("低风险", manager.riskLevel(RiskLevel.LOW));
        assertEquals("漏斗活动较高", manager.reason(RiskReason.EXCESSIVE_HOPPERS));
        assertEquals("持续活跃", manager.reason(RiskReason.SUSTAINED_LOAD));
        assertEquals("否", manager.raw("common.no-text"));
        assertEquals("是", manager.raw("common.yes-text"));
        assertTrue(manager.raw("command.top.no-high-risk").contains("高风险热点"));
    }

    @Test
    void switchZhToEnAndBack() {
        LanguageManager manager = load("zh_CN");
        manager.select("en_US");
        assertEquals("en_US", manager.currentLocale());
        assertEquals("Low", manager.riskLevel(RiskLevel.LOW));
        assertEquals("High hopper activity", manager.reason(RiskReason.EXCESSIVE_HOPPERS));
        manager.select("zh_CN");
        assertEquals("低风险", manager.riskLevel(RiskLevel.LOW));
    }

    @Test
    void invalidLocaleFallsBackToZhCn() {
        LanguageManager manager = load("abc");
        assertEquals("zh_CN", manager.currentLocale());
    }

    @Test
    void missingSelectedKeyFallsBackToZhCn() throws Exception {
        LanguageManager manager = load("zh_CN");
        Files.writeString(temp.resolve("lang").resolve("xx_XX.yml"), """
                _meta:
                  locale: xx_XX
                  name: Test
                prefix: "[T] "
                """, StandardCharsets.UTF_8);
        manager.load(temp.toFile(), "xx_XX");
        assertEquals("xx_XX", manager.currentLocale());
        assertEquals("当前没有足够活跃的区块。", manager.raw("command.top.empty"));
        assertTrue(manager.warnedKeyCount() >= 1);
        int warned = manager.warnedKeyCount();
        manager.raw("command.top.empty");
        assertEquals(warned, manager.warnedKeyCount());
    }

    @Test
    void missingAllKeysReturnsPlaceholderOnce() {
        LanguageManager manager = load("zh_CN");
        String first = manager.raw("does.not.exist");
        assertTrue(first.contains("does.not.exist"));
        int warned = manager.warnedKeyCount();
        manager.raw("does.not.exist");
        assertEquals(warned, manager.warnedKeyCount());
    }

    @Test
    void zhAndEnKeysMatch() {
        LanguageManager manager = load("zh_CN");
        Set<String> zh = new HashSet<>(manager.keys("zh_CN"));
        Set<String> en = new HashSet<>(manager.keys("en_US"));
        assertFalse(zh.isEmpty());
        assertEquals(zh, en);
    }

    @Test
    void enumTranslationsAreComplete() {
        LanguageManager manager = load("en_US");
        for (String locale : new String[] {"zh_CN", "en_US"}) {
            manager.select(locale);
            for (RiskLevel level : RiskLevel.values()) {
                assertFalse(missing(manager.riskLevel(level)), locale + " " + level);
            }
            for (ProtectionLevel level : ProtectionLevel.values()) {
                assertFalse(missing(manager.protection(level)), locale + " " + level);
            }
            for (LagCorrelation value : LagCorrelation.values()) {
                assertFalse(missing(manager.correlation(value)), locale + " " + value);
            }
            for (ServerPressure value : ServerPressure.values()) {
                assertFalse(missing(manager.pressure(value)), locale + " " + value);
            }
            for (ClusterType type : ClusterType.values()) {
                assertFalse(missing(manager.clusterType(type)), locale + " " + type);
            }
            for (MetricType type : MetricType.values()) {
                assertFalse(missing(manager.activity(type)), locale + " " + type);
            }
            for (RiskReason reason : RiskReason.values()) {
                assertFalse(missing(manager.reason(reason)), locale + " " + reason);
                assertFalse(missing(manager.reasonDetail(reason)), locale + " detail " + reason);
            }
        }
    }

    @Test
    void placeholdersReplaceSafely() {
        LanguageManager manager = load("zh_CN");
        assertEquals("发现 3 个需要关注的热点。", manager.raw("command.top.attention-count", java.util.Map.of("count", "3")));
        assertEquals("发现 3 个需要关注的热点。", manager.raw("command.top.attention-count", java.util.Map.of("count", "3", "unused", "x")));
    }

    @Test
    void reloadPicksUpLanguageFileEdits() throws Exception {
        LanguageManager manager = load("zh_CN");
        Path file = temp.resolve("lang").resolve("zh_CN.yml");
        String text = Files.readString(file, StandardCharsets.UTF_8);
        Files.writeString(file, text.replace("当前没有足够活跃的区块。", "自定义空列表"), StandardCharsets.UTF_8);
        manager.load(temp.toFile(), "zh_CN");
        assertEquals("自定义空列表", manager.raw("command.top.empty"));
    }

    @Test
    void legacyMessagesYmlMigratesWithoutDeletingBackup() throws Exception {
        Files.writeString(temp.resolve("messages.yml"), """
                prefix: "<gray>[CUSTOM]</gray> "
                player-only: "<red>stay custom</red>"
                """, StandardCharsets.UTF_8);
        LanguageManager manager = new LanguageManager(Logger.getAnonymousLogger());
        LanguageManager.LoadResult result = manager.load(temp.toFile(), "zh_CN");
        assertTrue(result.migratedLegacy());
        assertEquals("<red>stay custom</red>", manager.raw("command.player-only"));
        assertEquals("<gray>[CUSTOM]</gray> ", manager.raw("prefix"));
        assertTrue(Files.exists(temp.resolve("messages.yml")));
        assertTrue(manager.raw("command.top.empty").contains("活跃") || manager.raw("command.top.empty").contains("区块"));
    }

    @Test
    void existingTranslationIsNotOverwritten() throws Exception {
        LanguageManager first = load("zh_CN");
        Path file = temp.resolve("lang").resolve("zh_CN.yml");
        String text = Files.readString(file, StandardCharsets.UTF_8);
        Files.writeString(file, text.replace("当前没有足够活跃的区块。", "管理员自定义"), StandardCharsets.UTF_8);
        first.load(temp.toFile(), "zh_CN");
        assertEquals("管理员自定义", first.raw("command.top.empty"));
        assertTrue(first.raw("command.language.current").contains("当前语言"));
    }

    @Test
    void brokenLocaleFileFallsBackWithoutThrowing() throws Exception {
        load("zh_CN");
        Files.writeString(temp.resolve("lang").resolve("en_US.yml"), "this: [is: : broken", StandardCharsets.UTF_8);
        LanguageManager manager = new LanguageManager(Logger.getAnonymousLogger());
        manager.load(temp.toFile(), "en_US");
        assertEquals("Low", manager.riskLevel(RiskLevel.LOW));
    }

    @Test
    void runtimeLookupDoesNotRereadYaml() throws Exception {
        LanguageManager manager = load("zh_CN");
        Files.deleteIfExists(temp.resolve("lang").resolve("zh_CN.yml"));
        Files.deleteIfExists(temp.resolve("lang").resolve("en_US.yml"));
        assertEquals("低风险", manager.riskLevel(RiskLevel.LOW));
        manager.select("en_US");
        assertEquals("Low", manager.riskLevel(RiskLevel.LOW));
    }

    @Test
    void tabCompleteLanguagesComeFromManager() {
        LanguageManager manager = load("zh_CN");
        assertTrue(manager.availableLocales().contains("zh_CN"));
        assertTrue(manager.availableLocales().contains("en_US"));
    }

    private LanguageManager load(String locale) {
        LanguageManager manager = new LanguageManager(Logger.getAnonymousLogger());
        manager.load(temp.toFile(), locale);
        return manager;
    }

    private static boolean missing(String value) {
        return value == null || value.startsWith("<missing:");
    }
}
