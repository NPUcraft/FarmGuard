package com.npucraft.farmguard.i18n;

import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Maps FarmGuard beta.2 flat {@code messages.yml} keys onto the nested lang files.
 */
public final class LegacyMessageMigration {

    private static final Map<String, String> OLD_TO_NEW = Map.ofEntries(
            Map.entry("prefix", "prefix"),
            Map.entry("no-permission", "command.no-permission"),
            Map.entry("invalid-command", "command.unknown"),
            Map.entry("invalid-number", "command.invalid-number"),
            Map.entry("player-only", "command.player-only"),
            Map.entry("inspect-console-usage", "command.inspect.console-usage"),
            Map.entry("world-not-found", "command.inspect.world-not-found"),
            Map.entry("reload-success", "command.reload.success"),
            Map.entry("reload-partial", "command.reload.partial"),
            Map.entry("mode-current", "command.mode.current"),
            Map.entry("mode-changed", "command.mode.changed"),
            Map.entry("mode-invalid", "command.mode.invalid"),
            Map.entry("whitelist-added", "command.whitelist.added"),
            Map.entry("whitelist-removed", "command.whitelist.removed"),
            Map.entry("whitelist-exists", "command.whitelist.exists"),
            Map.entry("whitelist-missing", "command.whitelist.missing"),
            Map.entry("whitelist-empty", "command.whitelist.empty"),
            Map.entry("whitelist-header", "command.whitelist.header"),
            Map.entry("status-header", "command.status.header"),
            Map.entry("top-empty", "command.top.empty"),
            Map.entry("inspect-missing", "command.inspect.missing"),
            Map.entry("limits-header", "command.limits.header"),
            Map.entry("limits-empty", "command.limits.empty"),
            Map.entry("help-header", "command.help.header"),
            Map.entry("protection-start", "notify.protection-start"),
            Map.entry("protection-escalated", "notify.protection-escalated"),
            Map.entry("protection-end", "notify.protection-end"),
            Map.entry("notify-server-lag", "notify.server-lag"),
            Map.entry("notify-high-risk", "notify.high-risk"),
            Map.entry("notify-critical-risk", "notify.critical-risk"),
            Map.entry("notify-lag-recovered", "notify.lag-recovered"),
            Map.entry("notify-risk-suppressed", "notify.risk-suppressed")
    );

    private LegacyMessageMigration() {
    }

    public static Map<String, String> mapping() {
        return OLD_TO_NEW;
    }

    public static int overlay(YamlConfiguration target, YamlConfiguration legacy) {
        if (target == null || legacy == null) {
            return 0;
        }
        int applied = 0;
        for (Map.Entry<String, String> entry : OLD_TO_NEW.entrySet()) {
            if (!legacy.contains(entry.getKey())) {
                continue;
            }
            Object value = legacy.get(entry.getKey());
            if (value != null) {
                target.set(entry.getValue(), value);
                applied++;
            }
        }
        return applied;
    }

    public static Map<String, String> overlayFlat(Map<String, String> bundled, Map<String, String> legacy) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>(bundled);
        if (legacy == null) {
            return out;
        }
        for (Map.Entry<String, String> entry : OLD_TO_NEW.entrySet()) {
            String oldValue = legacy.get(entry.getKey());
            if (oldValue != null) {
                out.put(entry.getValue(), oldValue);
            }
        }
        return out;
    }
}
