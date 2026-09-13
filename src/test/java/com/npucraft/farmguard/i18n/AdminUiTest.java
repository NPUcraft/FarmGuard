package com.npucraft.farmguard.i18n;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.npucraft.farmguard.TestData;
import com.npucraft.farmguard.cluster.ClusterManager;
import com.npucraft.farmguard.config.FarmGuardSettings;
import com.npucraft.farmguard.model.AutomationCluster;
import com.npucraft.farmguard.model.ClusterType;
import com.npucraft.farmguard.model.LagCorrelation;
import com.npucraft.farmguard.model.MetricType;
import com.npucraft.farmguard.model.OperatingMode;
import com.npucraft.farmguard.model.ProtectionLevel;
import com.npucraft.farmguard.model.ProtectionState;
import com.npucraft.farmguard.model.RiskAssessment;
import com.npucraft.farmguard.model.RiskLevel;
import com.npucraft.farmguard.model.RiskReason;
import com.npucraft.farmguard.model.ServerMetrics;
import com.npucraft.farmguard.model.ServerPressure;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdminUiTest {

    @TempDir
    Path temp;

    @Test
    void chineseTopUsesActivityNotBareScoreOrEnums() {
        String text = String.join("\n", renderTop("zh_CN", RiskLevel.LOW, ProtectionLevel.NORMAL, OperatingMode.MONITOR));
        assertTrue(text.contains("活跃"));
        assertTrue(text.contains("低风险"));
        assertTrue(text.contains("漏斗活动较高") || text.contains("持续活跃"));
        assertTrue(text.contains("没有高风险热点"));
        assertTrue(text.contains("设施"));
        assertFalse(text.contains("Score"));
        assertFalse(text.contains("漏斗过多"));
        assertFalse(text.contains("持续负载"));
        assertNoBareEnums(text);
    }

    @Test
    void englishTopUsesActivityAndAttentionCopy() {
        String low = String.join("\n", renderTop("en_US", RiskLevel.LOW, ProtectionLevel.NORMAL, OperatingMode.MONITOR));
        assertTrue(low.contains("Activity"));
        assertTrue(low.contains("No high-risk hotspots"));
        assertTrue(low.contains("facilities"));
        assertFalse(low.matches("(?s).*\\bScore\\s+\\d.*"));
        String medium = String.join("\n", renderTop("en_US", RiskLevel.MEDIUM, ProtectionLevel.NORMAL, OperatingMode.MONITOR));
        assertTrue(medium.contains("require attention"));
        String high = String.join("\n", renderTop("en_US", RiskLevel.HIGH, ProtectionLevel.THROTTLE, OperatingMode.PROTECT));
        assertTrue(high.contains("THROTTLED"));
        String critical = String.join("\n", renderTop("en_US", RiskLevel.CRITICAL, ProtectionLevel.EMERGENCY, OperatingMode.PROTECT));
        assertTrue(critical.contains("EMERGENCY"));
        String monitorThrottle = String.join("\n", renderTop("en_US", RiskLevel.HIGH, ProtectionLevel.THROTTLE, OperatingMode.MONITOR));
        assertFalse(monitorThrottle.contains("THROTTLED"));
        assertNoBareEnums(low + medium + high + critical);
    }

    @Test
    void reasonTruncationShowsPlusCount() {
        String text = String.join("\n", renderTop("zh_CN", RiskLevel.HIGH, ProtectionLevel.NORMAL, OperatingMode.PROTECT));
        assertTrue(text.contains("+3"));
        assertTrue(text.contains("持续活跃"));
    }

    @Test
    void inspectIsTranslatedInBothLocales() {
        String zh = String.join("\n", renderInspect("zh_CN"));
        assertTrue(zh.contains("区块诊断"));
        assertTrue(zh.contains("活动评分"));
        assertTrue(zh.contains("风险评分"));
        assertTrue(zh.contains("风险因素"));
        assertTrue(zh.contains("当前分类"));
        assertTrue(zh.contains("存储设施"));
        assertTrue(zh.contains("/s") || zh.contains("（估算）"));
        assertTrue(zh.contains("未限制") || zh.contains("限流"));
        assertFalse(zh.contains("STORAGE"));
        String en = String.join("\n", renderInspect("en_US"));
        assertTrue(en.contains("Chunk Inspection"));
        assertTrue(en.contains("Activity Score"));
        assertTrue(en.contains("Risk Score"));
        assertTrue(en.contains("Risk Factors"));
        assertTrue(en.contains("Facility Type"));
        assertTrue(en.contains("Storage"));
        assertNoBareEnums(zh + "\n" + en);
    }

    @Test
    void clusterDedupIsVisibleInTopOutput() {
        LanguageManager manager = load("zh_CN");
        AdminUi ui = new AdminUi(manager, new MessageService(manager));
        ClusterManager clusters = new ClusterManager();
        RiskAssessment a = hopper(0, 0, 50);
        RiskAssessment b = hopper(0, 1, 49);
        RiskAssessment standalone = quietHopper(40, 40, 12);
        clusters.refresh(List.of(a, b, standalone), Map.of(), FarmGuardSettings.defaults());
        List<Component> lines = ui.top(
                10,
                new ServerMetrics(1L, 20.0, 7.1, 7.1, ServerPressure.NORMAL, false),
                List.of(a, b, standalone),
                clusters.current(),
                List.of(),
                OperatingMode.MONITOR,
                false
        );
        String text = String.join("\n", ui.plain(lines));
        assertTrue(text.contains("#1"));
        assertTrue(text.contains("#2"));
        assertFalse(text.contains("#3"));
        assertTrue(text.contains("2 个区块"));
        assertTrue(text.contains("存储设施") || text.contains("未知"));
    }

    private List<String> renderTop(String locale, RiskLevel level, ProtectionLevel protection, OperatingMode mode) {
        LanguageManager manager = load(locale);
        AdminUi ui = new AdminUi(manager, new MessageService(manager));
        var key = TestData.chunk(-7, -12);
        var snapshot = TestData.snapshotRates(key, MetricType.HOPPER, 80, 60);
        RiskAssessment assessment = new RiskAssessment(
                key,
                28.3,
                8.4,
                1.0,
                level,
                LagCorrelation.NONE,
                List.of(
                        RiskReason.SUSTAINED_LOAD,
                        RiskReason.EXCESSIVE_ENTITIES,
                        RiskReason.RAPID_OSCILLATION,
                        RiskReason.EXCESSIVE_HOPPERS,
                        RiskReason.SUDDEN_SPIKE
                ),
                List.of(),
                snapshot
        );
        ProtectionState state = new ProtectionState(key, protection, protection, 1L, EnumSet.noneOf(RiskReason.class), false, false);
        List<Component> lines = ui.top(
                10,
                new ServerMetrics(1L, 20.0, 4.2, 4.2, ServerPressure.NORMAL, false),
                List.of(assessment),
                List.of(),
                List.of(state),
                mode,
                false
        );
        return ui.plain(lines);
    }

    private List<String> renderInspect(String locale) {
        LanguageManager manager = load(locale);
        AdminUi ui = new AdminUi(manager, new MessageService(manager));
        var key = TestData.chunk(-7, -12);
        var snapshot = TestData.mixed(key, 6.1, 0.6, 0.0, 0, 0, 0);
        RiskAssessment assessment = new RiskAssessment(
                key,
                28.3,
                8.4,
                1.0,
                RiskLevel.LOW,
                LagCorrelation.NONE,
                List.of(RiskReason.SUSTAINED_LOAD, RiskReason.EXCESSIVE_ENTITIES),
                List.of(),
                snapshot
        );
        AutomationCluster cluster = new AutomationCluster(
                "world:-8,-13",
                key.worldId(),
                "world",
                Set.of(key),
                ClusterType.STORAGE,
                28.3,
                8.4,
                RiskLevel.LOW,
                LagCorrelation.NONE,
                List.of(),
                ProtectionLevel.NORMAL
        );
        return ui.plain(ui.inspect(key, assessment, null, cluster, false, 30, false));
    }

    private static RiskAssessment hopper(int x, int z, double score) {
        var key = TestData.chunk(x, z);
        return TestData.risk(key, RiskLevel.HIGH, score, TestData.snapshotRates(key, MetricType.HOPPER, 80.0, 70.0));
    }

    private static RiskAssessment quietHopper(int x, int z, double score) {
        var key = TestData.chunk(x, z);
        return TestData.risk(key, RiskLevel.LOW, score, TestData.snapshotRates(key, MetricType.HOPPER, 4.0, 3.5));
    }

    private LanguageManager load(String locale) {
        LanguageManager manager = new LanguageManager(Logger.getAnonymousLogger());
        manager.load(temp.toFile(), locale);
        return manager;
    }

    private static void assertNoBareEnums(String text) {
        for (String token : List.of(
                "STORAGE", "REDSTONE_MACHINE", "SUSTAINED_LOAD", "RAPID_OSCILLATION",
                "UNKNOWN_AUTOMATION", "EXCESSIVE_HOPPERS"
        )) {
            assertFalse(text.contains(token), "bare enum leaked: " + token + " in " + text);
        }
    }
}
