package com.npucraft.farmguard.config;

import com.npucraft.farmguard.model.MetricType;
import com.npucraft.farmguard.model.OperatingMode;
import com.npucraft.farmguard.model.RiskLevel;
import com.npucraft.farmguard.model.ServerPressure;
import com.npucraft.farmguard.model.ThrottleType;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

public final class ConfigLoader {

    public record Result(FarmGuardSettings settings, List<String> errors) {
        public boolean hadErrors() {
            return !errors.isEmpty();
        }
    }

    private final Logger logger;
    private final List<String> errors = new ArrayList<>();

    public ConfigLoader(Logger logger) {
        this.logger = logger;
    }

    public Result load(FileConfiguration config) {
        errors.clear();
        FarmGuardSettings.Builder builder = FarmGuardSettings.builder();
        FarmGuardSettings defaults = FarmGuardSettings.defaults();
        String modeRaw = config.getString("mode");
        if (modeRaw == null || modeRaw.isBlank()) {
            builder.mode(defaults.mode());
        } else {
            OperatingMode parsed = OperatingMode.parse(modeRaw, null);
            if (parsed == null) {
                errors.add("Invalid mode '" + modeRaw + "', using MONITOR");
                builder.mode(OperatingMode.MONITOR);
            } else {
                builder.mode(parsed);
            }
        }
        builder.debug(bool(config, "debug", defaults.debug()) || bool(config, "monitoring.debug", false));
        builder.aggregationIntervalTicks(intValue(config, "monitoring.aggregation-interval-ticks", defaults.aggregationIntervalTicks(), 1, 200));
        int shortWindow = intValue(config, "monitoring.short-window-seconds", defaults.shortWindowSeconds(), 1, 60);
        int longWindow = intValue(config, "monitoring.long-window-seconds", defaults.longWindowSeconds(), 2, 300);
        if (shortWindow > longWindow) {
            errors.add("monitoring.short-window-seconds > long-window-seconds, swapping to keep a valid window pair");
            int swap = shortWindow;
            shortWindow = longWindow;
            longWindow = swap;
        }
        builder.shortWindowSeconds(shortWindow);
        builder.longWindowSeconds(longWindow);
        builder.bucketSeconds(intValue(config, "monitoring.bucket-seconds", defaults.bucketSeconds(), 1, 30));
        builder.inactiveTtlSeconds(intValue(config, "monitoring.inactive-ttl-seconds", defaults.inactiveTtlSeconds(), 1, 3600));
        builder.maxTrackedChunks(intValue(config, "monitoring.max-tracked-chunks", defaults.maxTrackedChunks(), 1, 50_000));
        builder.hotspotCensusLimit(intValue(config, "monitoring.hotspot-census-limit", defaults.hotspotCensusLimit(), 1, 256));
        builder.hotspotCensusIntervalSeconds(intValue(config, "monitoring.hotspot-census-interval-seconds", defaults.hotspotCensusIntervalSeconds(), 1, 60));
        builder.censusPerCycle(intValue(config, "monitoring.census-per-cycle", defaults.censusPerCycle(), 1, 32));
        builder.censusEnabled(bool(config, "monitoring.census-enabled", defaults.censusEnabled()));
        builder.analysisMaxChunks(intValue(config, "monitoring.analysis-max-chunks", defaults.analysisMaxChunks(), 32, 50_000));
        builder.clusterRefreshIntervalSeconds(intValue(config, "monitoring.cluster-refresh-interval-seconds", defaults.clusterRefreshIntervalSeconds(), 1, 60));

        builder.warningMspt(decimal(config, "server-pressure.warning-mspt", defaults.warningMspt(), 1.0, 1000.0));
        builder.highMspt(decimal(config, "server-pressure.high-mspt", defaults.highMspt(), 1.0, 1000.0));
        builder.criticalMspt(decimal(config, "server-pressure.critical-mspt", defaults.criticalMspt(), 1.0, 1000.0));
        builder.warningTps(decimal(config, "server-pressure.warning-tps", defaults.warningTps(), 1.0, 20.0));
        builder.highTps(decimal(config, "server-pressure.high-tps", defaults.highTps(), 1.0, 20.0));
        builder.criticalTps(decimal(config, "server-pressure.critical-tps", defaults.criticalTps(), 1.0, 20.0));
        builder.warningExitMspt(decimal(config, "server-pressure.warning-exit-mspt", defaults.warningExitMspt(), 1.0, 1000.0));
        builder.highExitMspt(decimal(config, "server-pressure.high-exit-mspt", defaults.highExitMspt(), 1.0, 1000.0));
        builder.criticalExitMspt(decimal(config, "server-pressure.critical-exit-mspt", defaults.criticalExitMspt(), 1.0, 1000.0));
        builder.warningExitTps(decimal(config, "server-pressure.warning-exit-tps", defaults.warningExitTps(), 1.0, 20.0));
        builder.highExitTps(decimal(config, "server-pressure.high-exit-tps", defaults.highExitTps(), 1.0, 20.0));
        builder.criticalExitTps(decimal(config, "server-pressure.critical-exit-tps", defaults.criticalExitTps(), 1.0, 20.0));
        builder.pressureEnterSeconds(intValue(config, "server-pressure.enter-min-duration-seconds", defaults.pressureEnterSeconds(), 1, 120));
        builder.pressureExitSeconds(intValue(config, "server-pressure.exit-stability-seconds", defaults.pressureExitSeconds(), 1, 180));
        builder.pressureCooldownSeconds(intValue(config, "server-pressure.cooldown-seconds", defaults.pressureCooldownSeconds(), 0, 180));
        builder.msptAverageSamples(intValue(config, "server-pressure.mspt-average-samples", defaults.msptAverageSamples(), 3, 120));

        builder.rateThreshold(MetricType.REDSTONE, decimal(config, "activity-thresholds.redstone-per-second", defaults.rateThreshold(MetricType.REDSTONE), 1.0, 10_000.0));
        builder.rateThreshold(MetricType.PISTON, decimal(config, "activity-thresholds.piston-per-second", defaults.rateThreshold(MetricType.PISTON), 1.0, 10_000.0));
        builder.rateThreshold(MetricType.HOPPER, decimal(config, "activity-thresholds.hopper-per-second", defaults.rateThreshold(MetricType.HOPPER), 1.0, 10_000.0));
        builder.rateThreshold(MetricType.ENTITY_SPAWN, decimal(config, "activity-thresholds.entity-spawn-per-second", defaults.rateThreshold(MetricType.ENTITY_SPAWN), 0.1, 10_000.0));
        builder.rateThreshold(MetricType.ENTITY_DEATH, decimal(config, "activity-thresholds.entity-death-per-second", defaults.rateThreshold(MetricType.ENTITY_DEATH), 0.1, 10_000.0));
        builder.rateThreshold(MetricType.ITEM, decimal(config, "activity-thresholds.item-per-second", defaults.rateThreshold(MetricType.ITEM), 0.1, 10_000.0));
        builder.rateThreshold(MetricType.VILLAGER, decimal(config, "activity-thresholds.villager-per-second", defaults.rateThreshold(MetricType.VILLAGER), 0.1, 10_000.0));
        builder.rateThreshold(MetricType.MINECART, decimal(config, "activity-thresholds.minecart-per-second", defaults.rateThreshold(MetricType.MINECART), 0.1, 10_000.0));
        builder.rateThreshold(MetricType.BREEDING, decimal(config, "activity-thresholds.breeding-per-second", defaults.rateThreshold(MetricType.BREEDING), 0.1, 10_000.0));
        builder.rateThreshold(MetricType.BLOCK_UPDATE, decimal(config, "activity-thresholds.block-update-per-second", defaults.rateThreshold(MetricType.BLOCK_UPDATE), 0.1, 10_000.0));
        builder.excessiveItems(intValue(config, "activity-thresholds.excessive-items", defaults.excessiveItems(), 8, 10_000));
        builder.excessiveVillagers(intValue(config, "activity-thresholds.excessive-villagers", defaults.excessiveVillagers(), 4, 10_000));
        builder.excessiveMinecarts(intValue(config, "activity-thresholds.excessive-minecarts", defaults.excessiveMinecarts(), 4, 10_000));
        builder.excessiveEntities(intValue(config, "activity-thresholds.excessive-entities", defaults.excessiveEntities(), 8, 20_000));
        builder.redstoneOscillationPerSecond(decimal(config, "activity-thresholds.redstone-oscillation-per-second", defaults.redstoneOscillationPerSecond(), 10.0, 10_000.0));
        builder.spikeRatio(decimal(config, "activity-thresholds.spike-ratio", defaults.spikeRatio(), 1.1, 20.0));
        builder.spikeMinShortPerSecond(decimal(config, "activity-thresholds.spike-min-short-per-second", defaults.spikeMinShortPerSecond(), 1.0, 10_000.0));

        builder.activityWeight(MetricType.REDSTONE, decimal(config, "risk.weights.redstone", defaults.activityWeight(MetricType.REDSTONE), 0.0, 100.0));
        builder.activityWeight(MetricType.PISTON, decimal(config, "risk.weights.piston", defaults.activityWeight(MetricType.PISTON), 0.0, 100.0));
        builder.activityWeight(MetricType.HOPPER, decimal(config, "risk.weights.hopper", defaults.activityWeight(MetricType.HOPPER), 0.0, 100.0));
        builder.activityWeight(MetricType.ENTITY_SPAWN, decimal(config, "risk.weights.entity-spawn", defaults.activityWeight(MetricType.ENTITY_SPAWN), 0.0, 100.0));
        builder.activityWeight(MetricType.ENTITY_DEATH, decimal(config, "risk.weights.entity-death", defaults.activityWeight(MetricType.ENTITY_DEATH), 0.0, 100.0));
        builder.activityWeight(MetricType.ITEM, decimal(config, "risk.weights.item", defaults.activityWeight(MetricType.ITEM), 0.0, 100.0));
        builder.activityWeight(MetricType.VILLAGER, decimal(config, "risk.weights.villager", defaults.activityWeight(MetricType.VILLAGER), 0.0, 100.0));
        builder.activityWeight(MetricType.MINECART, decimal(config, "risk.weights.minecart", defaults.activityWeight(MetricType.MINECART), 0.0, 100.0));
        builder.activityWeight(MetricType.BREEDING, decimal(config, "risk.weights.breeding", defaults.activityWeight(MetricType.BREEDING), 0.0, 100.0));
        builder.activityWeight(MetricType.BLOCK_UPDATE, decimal(config, "risk.weights.block-update", defaults.activityWeight(MetricType.BLOCK_UPDATE), 0.0, 100.0));
        builder.itemDensityWeight(decimal(config, "risk.weights.item-density", defaults.itemDensityWeight(), 0.0, 100.0));
        builder.villagerDensityWeight(decimal(config, "risk.weights.villager-density", defaults.villagerDensityWeight(), 0.0, 100.0));
        builder.minecartDensityWeight(decimal(config, "risk.weights.minecart-density", defaults.minecartDensityWeight(), 0.0, 100.0));
        builder.entityDensityWeight(decimal(config, "risk.weights.entity-density", defaults.entityDensityWeight(), 0.0, 100.0));
        builder.pressureMultiplierNormal(decimal(config, "risk.pressure-multipliers.normal", defaults.pressureMultiplier(ServerPressure.NORMAL), 0.1, 5.0));
        builder.pressureMultiplierWarning(decimal(config, "risk.pressure-multipliers.warning", defaults.pressureMultiplier(ServerPressure.WARNING), 0.1, 5.0));
        builder.pressureMultiplierHigh(decimal(config, "risk.pressure-multipliers.high", defaults.pressureMultiplier(ServerPressure.HIGH), 0.1, 5.0));
        builder.pressureMultiplierCritical(decimal(config, "risk.pressure-multipliers.critical", defaults.pressureMultiplier(ServerPressure.CRITICAL), 0.1, 5.0));
        builder.lagPossibleBonus(decimal(config, "risk.lag-correlation-bonus.possible", defaults.lagBonus(com.npucraft.farmguard.model.LagCorrelation.POSSIBLE), 0.0, 50.0));
        builder.lagStrongBonus(decimal(config, "risk.lag-correlation-bonus.strong", defaults.lagBonus(com.npucraft.farmguard.model.LagCorrelation.STRONG), 0.0, 50.0));
        builder.lowScore(decimal(config, "risk.low-score", defaults.lowScore(), 0.0, 100.0));
        builder.mediumScore(decimal(config, "risk.medium-score", defaults.mediumScore(), 0.0, 100.0));
        builder.highScore(decimal(config, "risk.high-score", defaults.highScore(), 0.0, 100.0));
        builder.criticalScore(decimal(config, "risk.critical-score", defaults.criticalScore(), 0.0, 100.0));
        builder.maxScore(decimal(config, "risk.max-score", defaults.maxScore(), 10.0, 1000.0));
        builder.hotspotMinActivityScore(decimal(config, "risk.hotspot-min-activity-score", defaults.hotspotMinActivityScore(), 0.0, 100.0));
        builder.topLimit(intValue(config, "risk.top-limit", defaults.topLimit(), 1, 50));

        builder.correlationSamples(intValue(config, "lag-correlation.sample-seconds", defaults.correlationSamples(), 4, 120));
        builder.correlationMinSamples(intValue(config, "lag-correlation.min-samples", defaults.correlationMinSamples(), 3, 120));
        builder.possibleActivityDelta(decimal(config, "lag-correlation.possible-activity-delta", defaults.possibleActivityDelta(), 0.1, 1000.0));
        builder.possibleMsptDelta(decimal(config, "lag-correlation.possible-mspt-delta", defaults.possibleMsptDelta(), 0.1, 1000.0));
        builder.strongPearson(decimal(config, "lag-correlation.strong-pearson", defaults.strongPearson(), 0.1, 1.0));
        builder.strongActivityScore(decimal(config, "lag-correlation.strong-activity-score", defaults.strongActivityScore(), 0.0, 1000.0));
        builder.strongMspt(decimal(config, "lag-correlation.strong-mspt", defaults.strongMspt(), 0.0, 1000.0));
        builder.strongHoldSeconds(intValue(config, "lag-correlation.strong-hold-seconds", defaults.strongHoldSeconds(), 1, 120));
        builder.possibleHoldSeconds(intValue(config, "lag-correlation.possible-hold-seconds", defaults.possibleHoldSeconds(), 0, 120));

        builder.clusterNeighborhood(intValue(config, "cluster.neighborhood", defaults.clusterNeighborhood(), 1, 3));
        builder.clusterMinActivityScore(decimal(config, "cluster.min-activity-score", defaults.clusterMinActivityScore(), 0.0, 100.0));
        builder.classificationMargin(decimal(config, "cluster.classification-margin", defaults.classificationMargin(), 0.0, 1.0));

        builder.protectionEnterSeconds(intValue(config, "protection.enter-min-duration-seconds", defaults.protectionEnterSeconds(), 1, 180));
        builder.protectionExitSeconds(intValue(config, "protection.exit-stability-seconds", defaults.protectionExitSeconds(), 1, 180));
        builder.protectionCooldownSeconds(intValue(config, "protection.cooldown-seconds", defaults.protectionCooldownSeconds(), 0, 180));
        for (ThrottleType type : ThrottleType.values()) {
            String path = type.name().toLowerCase(Locale.ROOT);
            builder.throttleRate(type, intValue(config, "protection.throttle-rates." + path, defaults.throttleRate(type, false), 0, 1000));
            builder.emergencyRate(type, intValue(config, "protection.emergency-rates." + path, defaults.throttleRate(type, true), 0, 1000));
        }
        builder.throttleMinPressure(enumValue(config, "protection.throttle-min-pressure", ServerPressure.class, defaults.throttleMinPressure()));
        builder.emergencyMinPressure(enumValue(config, "protection.emergency-min-pressure", ServerPressure.class, defaults.emergencyMinPressure()));
        builder.throttleMinRisk(enumValue(config, "protection.throttle-min-risk", RiskLevel.class, defaults.throttleMinRisk()));
        builder.emergencyMinRisk(enumValue(config, "protection.emergency-min-risk", RiskLevel.class, defaults.emergencyMinRisk()));
        builder.requireLagCorrelationForEmergency(bool(config, "protection.require-lag-correlation-for-emergency", defaults.requireLagCorrelationForEmergency()));
        EnumSet<ThrottleType> enabled = EnumSet.noneOf(ThrottleType.class);
        for (ThrottleType type : ThrottleType.values()) {
            boolean fallback = type != ThrottleType.ITEM;
            if (bool(config, "protection.enabled-throttles." + type.name().toLowerCase(Locale.ROOT), fallback)) {
                enabled.add(type);
            }
        }
        builder.enabledThrottles(enabled);
        EnumSet<ThrottleType> emergencyOnly = EnumSet.noneOf(ThrottleType.class);
        if (!config.contains("protection.emergency-only-throttles")) {
            emergencyOnly.add(ThrottleType.REDSTONE);
            emergencyOnly.add(ThrottleType.PISTON);
            emergencyOnly.add(ThrottleType.ITEM);
            emergencyOnly.add(ThrottleType.MINECART);
        } else {
            for (String raw : config.getStringList("protection.emergency-only-throttles")) {
                try {
                    emergencyOnly.add(ThrottleType.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
                } catch (RuntimeException exception) {
                    errors.add("Unknown emergency-only throttle '" + raw + "', ignored");
                }
            }
        }
        builder.emergencyOnlyThrottles(emergencyOnly);
        if (!config.contains("protection.spawn-throttle-reasons")) {
            builder.spawnThrottleReasons(defaults.spawnThrottleReasons());
        } else {
            java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
            for (String raw : config.getStringList("protection.spawn-throttle-reasons")) {
                if (raw != null && !raw.isBlank()) {
                    names.add(raw.trim().toUpperCase(Locale.ROOT));
                }
            }
            builder.spawnThrottleReasons(names);
        }

        builder.ignoredWorlds(stringSet(config, "whitelist.ignored-worlds"));
        builder.monitoredWorlds(stringSet(config, "whitelist.monitored-worlds"));

        builder.notifyConsole(bool(config, "notifications.console", defaults.notifyConsole()));
        builder.notifyAdmins(bool(config, "notifications.notify-admins", defaults.notifyAdmins()));
        builder.notificationCooldownSeconds(intValue(config, "notifications.cooldown-seconds", defaults.notificationCooldownSeconds(), 1, 600));
        builder.notificationPersistSeconds(intValue(config, "notifications.persist-seconds", defaults.notificationPersistSeconds(), 5, 600));
        builder.maxRiskNotificationsPerCycle(intValue(config, "notifications.max-per-cycle", defaults.maxRiskNotificationsPerCycle(), 1, 20));
        builder.adminPermission(text(config, "notifications.admin-permission", defaults.adminPermission()));

        builder.historyEnabled(bool(config, "history.enabled", defaults.historyEnabled()));
        builder.historyFile(text(config, "history.file", defaults.historyFile()));
        builder.maxIncidents(intValue(config, "history.max-incidents", defaults.maxIncidents(), 5, 500));
        builder.maxIncidentAgeHours(intValue(config, "history.max-age-hours", defaults.maxIncidentAgeHours(), 1, 720));
        builder.historyFlushSeconds(intValue(config, "history.flush-interval-seconds", defaults.historyFlushSeconds(), 10, 600));

        builder.debugLogEnabled(bool(config, "debug-log.enabled", defaults.debugLogEnabled()));
        builder.debugSnapshotIntervalSeconds(intValue(config, "debug-log.snapshot-interval-seconds", defaults.debugSnapshotIntervalSeconds(), 5, 300));
        builder.debugHotspotTopN(intValue(config, "debug-log.hotspot-top-n", defaults.debugHotspotTopN(), 0, 50));
        builder.debugMaxFileSizeMb(intValue(config, "debug-log.max-file-size-mb", defaults.debugMaxFileSizeMb(), 1, 512));
        builder.debugMaxFiles(intValue(config, "debug-log.max-files", defaults.debugMaxFiles(), 1, 20));

        Result result = new Result(builder.build(), List.copyOf(errors));
        for (String error : result.errors()) {
            logger.warning("[FarmGuard] " + error);
        }
        return result;
    }

    private String text(FileConfiguration config, String path, String fallback) {
        try {
            String value = config.getString(path);
            return value == null || value.isBlank() ? fallback : value;
        } catch (RuntimeException exception) {
            errors.add("Invalid string at " + path + ", using " + fallback + ": " + exception.getMessage());
            return fallback;
        }
    }

    private boolean bool(FileConfiguration config, String path, boolean fallback) {
        try {
            if (!config.contains(path)) {
                return fallback;
            }
            return config.getBoolean(path);
        } catch (RuntimeException exception) {
            errors.add("Invalid boolean at " + path + ", using " + fallback + ": " + exception.getMessage());
            return fallback;
        }
    }

    private int intValue(FileConfiguration config, String path, int fallback, int min, int max) {
        try {
            if (!config.contains(path)) {
                return fallback;
            }
            int value = config.getInt(path);
            if (value < min || value > max) {
                errors.add("Value at " + path + " out of range " + min + "-" + max + ", using " + fallback);
                return fallback;
            }
            return value;
        } catch (RuntimeException exception) {
            errors.add("Invalid integer at " + path + ", using " + fallback + ": " + exception.getMessage());
            return fallback;
        }
    }

    private double decimal(FileConfiguration config, String path, double fallback, double min, double max) {
        try {
            if (!config.contains(path)) {
                return fallback;
            }
            double value = config.getDouble(path);
            if (Double.isNaN(value) || Double.isInfinite(value) || value < min || value > max) {
                errors.add("Value at " + path + " out of range " + min + "-" + max + ", using " + fallback);
                return fallback;
            }
            return value;
        } catch (RuntimeException exception) {
            errors.add("Invalid number at " + path + ", using " + fallback + ": " + exception.getMessage());
            return fallback;
        }
    }

    private <E extends Enum<E>> E enumValue(FileConfiguration config, String path, Class<E> type, E fallback) {
        try {
            String raw = config.getString(path);
            if (raw == null || raw.isBlank()) {
                return fallback;
            }
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            errors.add("Invalid enum at " + path + ", using " + fallback + ": " + exception.getMessage());
            return fallback;
        }
    }

    private Set<String> stringSet(FileConfiguration config, String path) {
        try {
            List<String> values = config.getStringList(path);
            return Set.copyOf(values);
        } catch (RuntimeException exception) {
            errors.add("Invalid list at " + path + ", using empty list: " + exception.getMessage());
            return Set.of();
        }
    }

    public List<String> configChunks(FileConfiguration config) {
        ConfigurationSection section = config.getConfigurationSection("whitelist");
        if (section == null) {
            return List.of();
        }
        return section.getStringList("chunks");
    }

    public List<String> configClusters(FileConfiguration config) {
        ConfigurationSection section = config.getConfigurationSection("whitelist");
        if (section == null) {
            return List.of();
        }
        return section.getStringList("clusters");
    }
}
