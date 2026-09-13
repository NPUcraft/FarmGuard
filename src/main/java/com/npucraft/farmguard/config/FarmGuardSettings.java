package com.npucraft.farmguard.config;

import com.npucraft.farmguard.model.MetricType;
import com.npucraft.farmguard.model.OperatingMode;
import com.npucraft.farmguard.model.RiskLevel;
import com.npucraft.farmguard.model.ServerPressure;
import com.npucraft.farmguard.model.ThrottleType;
import java.util.EnumMap;
import java.util.Set;

/**
 * Immutable runtime settings. Bukkit YAML is parsed into this object so scoring,
 * pressure, and protection logic can be unit-tested without a Minecraft server.
 */
public final class FarmGuardSettings {

    private final OperatingMode mode;
    private final int aggregationIntervalTicks;
    private final int shortWindowSeconds;
    private final int longWindowSeconds;
    private final int bucketSeconds;
    private final int inactiveTtlSeconds;
    private final int maxTrackedChunks;
    private final int hotspotCensusLimit;
    private final int hotspotCensusIntervalSeconds;
    private final int censusPerCycle;
    private final boolean censusEnabled;
    private final int analysisMaxChunks;
    private final int clusterRefreshIntervalSeconds;
    private final boolean debug;
    private final int maxRiskNotificationsPerCycle;

    private final double warningMspt;
    private final double highMspt;
    private final double criticalMspt;
    private final double warningTps;
    private final double highTps;
    private final double criticalTps;
    private final double warningExitMspt;
    private final double highExitMspt;
    private final double criticalExitMspt;
    private final double warningExitTps;
    private final double highExitTps;
    private final double criticalExitTps;
    private final int pressureEnterSeconds;
    private final int pressureExitSeconds;
    private final int pressureCooldownSeconds;
    private final int msptAverageSamples;

    private final EnumMap<MetricType, Double> rateThresholds;
    private final int excessiveItems;
    private final int excessiveVillagers;
    private final int excessiveMinecarts;
    private final int excessiveEntities;
    private final double redstoneOscillationPerSecond;
    private final double spikeRatio;
    private final double spikeMinShortPerSecond;

    private final EnumMap<MetricType, Double> activityWeights;
    private final double itemDensityWeight;
    private final double villagerDensityWeight;
    private final double minecartDensityWeight;
    private final double entityDensityWeight;
    private final double pressureMultiplierNormal;
    private final double pressureMultiplierWarning;
    private final double pressureMultiplierHigh;
    private final double pressureMultiplierCritical;
    private final double lagNoneBonus;
    private final double lagPossibleBonus;
    private final double lagStrongBonus;
    private final double lowScore;
    private final double mediumScore;
    private final double highScore;
    private final double criticalScore;
    private final double maxScore;
    private final double hotspotMinActivityScore;
    private final int topLimit;

    private final int correlationSamples;
    private final int correlationMinSamples;
    private final double possibleActivityDelta;
    private final double possibleMsptDelta;
    private final double strongPearson;
    private final double strongActivityScore;
    private final double strongMspt;
    private final int strongHoldSeconds;
    private final int possibleHoldSeconds;

    private final int clusterNeighborhood;
    private final double clusterMinActivityScore;
    private final double classificationMargin;

    private final int protectionEnterSeconds;
    private final int protectionExitSeconds;
    private final int protectionCooldownSeconds;
    private final EnumMap<ThrottleType, Integer> throttleRates;
    private final EnumMap<ThrottleType, Integer> emergencyRates;
    private final ServerPressure throttleMinPressure;
    private final ServerPressure emergencyMinPressure;
    private final RiskLevel throttleMinRisk;
    private final RiskLevel emergencyMinRisk;
    private final boolean requireLagCorrelationForEmergency;
    private final Set<ThrottleType> enabledThrottles;
    private final Set<ThrottleType> emergencyOnlyThrottles;
    private final Set<String> spawnThrottleReasons;

    private final Set<String> ignoredWorlds;
    private final Set<String> monitoredWorlds;

    private final boolean notifyConsole;
    private final boolean notifyAdmins;
    private final int notificationCooldownSeconds;
    private final int notificationPersistSeconds;
    private final String adminPermission;

    private final boolean historyEnabled;
    private final String historyFile;
    private final int maxIncidents;
    private final int maxIncidentAgeHours;
    private final int historyFlushSeconds;
    private final boolean debugLogEnabled;
    private final int debugSnapshotIntervalSeconds;
    private final int debugHotspotTopN;
    private final int debugMaxFileSizeMb;
    private final int debugMaxFiles;
    private final String language;

    private FarmGuardSettings(Builder builder) {
        this.mode = builder.mode;
        this.aggregationIntervalTicks = builder.aggregationIntervalTicks;
        this.shortWindowSeconds = builder.shortWindowSeconds;
        this.longWindowSeconds = builder.longWindowSeconds;
        this.bucketSeconds = builder.bucketSeconds;
        this.inactiveTtlSeconds = builder.inactiveTtlSeconds;
        this.maxTrackedChunks = builder.maxTrackedChunks;
        this.hotspotCensusLimit = builder.hotspotCensusLimit;
        this.hotspotCensusIntervalSeconds = builder.hotspotCensusIntervalSeconds;
        this.censusPerCycle = builder.censusPerCycle;
        this.censusEnabled = builder.censusEnabled;
        this.analysisMaxChunks = builder.analysisMaxChunks;
        this.clusterRefreshIntervalSeconds = builder.clusterRefreshIntervalSeconds;
        this.debug = builder.debug;
        this.maxRiskNotificationsPerCycle = builder.maxRiskNotificationsPerCycle;
        this.warningMspt = builder.warningMspt;
        this.highMspt = builder.highMspt;
        this.criticalMspt = builder.criticalMspt;
        this.warningTps = builder.warningTps;
        this.highTps = builder.highTps;
        this.criticalTps = builder.criticalTps;
        this.warningExitMspt = builder.warningExitMspt;
        this.highExitMspt = builder.highExitMspt;
        this.criticalExitMspt = builder.criticalExitMspt;
        this.warningExitTps = builder.warningExitTps;
        this.highExitTps = builder.highExitTps;
        this.criticalExitTps = builder.criticalExitTps;
        this.pressureEnterSeconds = builder.pressureEnterSeconds;
        this.pressureExitSeconds = builder.pressureExitSeconds;
        this.pressureCooldownSeconds = builder.pressureCooldownSeconds;
        this.msptAverageSamples = builder.msptAverageSamples;
        this.rateThresholds = builder.rateThresholds;
        this.excessiveItems = builder.excessiveItems;
        this.excessiveVillagers = builder.excessiveVillagers;
        this.excessiveMinecarts = builder.excessiveMinecarts;
        this.excessiveEntities = builder.excessiveEntities;
        this.redstoneOscillationPerSecond = builder.redstoneOscillationPerSecond;
        this.spikeRatio = builder.spikeRatio;
        this.spikeMinShortPerSecond = builder.spikeMinShortPerSecond;
        this.activityWeights = builder.activityWeights;
        this.itemDensityWeight = builder.itemDensityWeight;
        this.villagerDensityWeight = builder.villagerDensityWeight;
        this.minecartDensityWeight = builder.minecartDensityWeight;
        this.entityDensityWeight = builder.entityDensityWeight;
        this.pressureMultiplierNormal = builder.pressureMultiplierNormal;
        this.pressureMultiplierWarning = builder.pressureMultiplierWarning;
        this.pressureMultiplierHigh = builder.pressureMultiplierHigh;
        this.pressureMultiplierCritical = builder.pressureMultiplierCritical;
        this.lagNoneBonus = builder.lagNoneBonus;
        this.lagPossibleBonus = builder.lagPossibleBonus;
        this.lagStrongBonus = builder.lagStrongBonus;
        this.lowScore = builder.lowScore;
        this.mediumScore = builder.mediumScore;
        this.highScore = builder.highScore;
        this.criticalScore = builder.criticalScore;
        this.maxScore = builder.maxScore;
        this.hotspotMinActivityScore = builder.hotspotMinActivityScore;
        this.topLimit = builder.topLimit;
        this.correlationSamples = builder.correlationSamples;
        this.correlationMinSamples = builder.correlationMinSamples;
        this.possibleActivityDelta = builder.possibleActivityDelta;
        this.possibleMsptDelta = builder.possibleMsptDelta;
        this.strongPearson = builder.strongPearson;
        this.strongActivityScore = builder.strongActivityScore;
        this.strongMspt = builder.strongMspt;
        this.strongHoldSeconds = builder.strongHoldSeconds;
        this.possibleHoldSeconds = builder.possibleHoldSeconds;
        this.clusterNeighborhood = builder.clusterNeighborhood;
        this.clusterMinActivityScore = builder.clusterMinActivityScore;
        this.classificationMargin = builder.classificationMargin;
        this.protectionEnterSeconds = builder.protectionEnterSeconds;
        this.protectionExitSeconds = builder.protectionExitSeconds;
        this.protectionCooldownSeconds = builder.protectionCooldownSeconds;
        this.throttleRates = builder.throttleRates;
        this.emergencyRates = builder.emergencyRates;
        this.throttleMinPressure = builder.throttleMinPressure;
        this.emergencyMinPressure = builder.emergencyMinPressure;
        this.throttleMinRisk = builder.throttleMinRisk;
        this.emergencyMinRisk = builder.emergencyMinRisk;
        this.requireLagCorrelationForEmergency = builder.requireLagCorrelationForEmergency;
        this.enabledThrottles = Set.copyOf(builder.enabledThrottles);
        this.emergencyOnlyThrottles = Set.copyOf(builder.emergencyOnlyThrottles);
        this.spawnThrottleReasons = Set.copyOf(builder.spawnThrottleReasons);
        this.ignoredWorlds = Set.copyOf(builder.ignoredWorlds);
        this.monitoredWorlds = Set.copyOf(builder.monitoredWorlds);
        this.notifyConsole = builder.notifyConsole;
        this.notifyAdmins = builder.notifyAdmins;
        this.notificationCooldownSeconds = builder.notificationCooldownSeconds;
        this.notificationPersistSeconds = builder.notificationPersistSeconds;
        this.adminPermission = builder.adminPermission;
        this.historyEnabled = builder.historyEnabled;
        this.historyFile = builder.historyFile;
        this.maxIncidents = builder.maxIncidents;
        this.maxIncidentAgeHours = builder.maxIncidentAgeHours;
        this.historyFlushSeconds = builder.historyFlushSeconds;
        this.debugLogEnabled = builder.debugLogEnabled;
        this.debugSnapshotIntervalSeconds = builder.debugSnapshotIntervalSeconds;
        this.debugHotspotTopN = builder.debugHotspotTopN;
        this.debugMaxFileSizeMb = builder.debugMaxFileSizeMb;
        this.debugMaxFiles = builder.debugMaxFiles;
        this.language = builder.language == null || builder.language.isBlank()
                ? "zh_CN"
                : builder.language;
    }

    public static FarmGuardSettings defaults() {
        return new Builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public OperatingMode mode() {
        return mode;
    }

    public int aggregationIntervalTicks() {
        return aggregationIntervalTicks;
    }

    public int shortWindowSeconds() {
        return shortWindowSeconds;
    }

    public int longWindowSeconds() {
        return longWindowSeconds;
    }

    public int bucketSeconds() {
        return bucketSeconds;
    }

    public int bucketCount() {
        return Math.max(1, (int) Math.ceil((double) longWindowSeconds / Math.max(1, bucketSeconds)));
    }

    public int shortBucketCount() {
        return Math.max(1, (int) Math.ceil((double) shortWindowSeconds / Math.max(1, bucketSeconds)));
    }

    public int inactiveTtlSeconds() {
        return inactiveTtlSeconds;
    }

    public int maxTrackedChunks() {
        return maxTrackedChunks;
    }

    public int hotspotCensusLimit() {
        return hotspotCensusLimit;
    }

    public int hotspotCensusIntervalSeconds() {
        return hotspotCensusIntervalSeconds;
    }

    public int censusPerCycle() {
        return censusPerCycle;
    }

    public boolean censusEnabled() {
        return censusEnabled;
    }

    public int analysisMaxChunks() {
        return analysisMaxChunks;
    }

    public int maxRiskNotificationsPerCycle() {
        return maxRiskNotificationsPerCycle;
    }

    public int clusterRefreshIntervalSeconds() {
        return clusterRefreshIntervalSeconds;
    }

    public boolean debug() {
        return debug;
    }

    public double warningMspt() {
        return warningMspt;
    }

    public double highMspt() {
        return highMspt;
    }

    public double criticalMspt() {
        return criticalMspt;
    }

    public double warningTps() {
        return warningTps;
    }

    public double highTps() {
        return highTps;
    }

    public double criticalTps() {
        return criticalTps;
    }

    public double warningExitMspt() {
        return warningExitMspt;
    }

    public double highExitMspt() {
        return highExitMspt;
    }

    public double criticalExitMspt() {
        return criticalExitMspt;
    }

    public double warningExitTps() {
        return warningExitTps;
    }

    public double highExitTps() {
        return highExitTps;
    }

    public double criticalExitTps() {
        return criticalExitTps;
    }

    public int pressureEnterSeconds() {
        return pressureEnterSeconds;
    }

    public int pressureExitSeconds() {
        return pressureExitSeconds;
    }

    public int pressureCooldownSeconds() {
        return pressureCooldownSeconds;
    }

    public int msptAverageSamples() {
        return msptAverageSamples;
    }

    public double rateThreshold(MetricType type) {
        return rateThresholds.getOrDefault(type, 1.0);
    }

    public int excessiveItems() {
        return excessiveItems;
    }

    public int excessiveVillagers() {
        return excessiveVillagers;
    }

    public int excessiveMinecarts() {
        return excessiveMinecarts;
    }

    public int excessiveEntities() {
        return excessiveEntities;
    }

    public double redstoneOscillationPerSecond() {
        return redstoneOscillationPerSecond;
    }

    public double spikeRatio() {
        return spikeRatio;
    }

    public double spikeMinShortPerSecond() {
        return spikeMinShortPerSecond;
    }

    public double activityWeight(MetricType type) {
        return activityWeights.getOrDefault(type, 0.0);
    }

    public double itemDensityWeight() {
        return itemDensityWeight;
    }

    public double villagerDensityWeight() {
        return villagerDensityWeight;
    }

    public double minecartDensityWeight() {
        return minecartDensityWeight;
    }

    public double entityDensityWeight() {
        return entityDensityWeight;
    }

    public double pressureMultiplier(ServerPressure pressure) {
        return switch (pressure) {
            case NORMAL -> pressureMultiplierNormal;
            case WARNING -> pressureMultiplierWarning;
            case HIGH -> pressureMultiplierHigh;
            case CRITICAL -> pressureMultiplierCritical;
        };
    }

    public double lagBonus(com.npucraft.farmguard.model.LagCorrelation correlation) {
        return switch (correlation) {
            case NONE -> lagNoneBonus;
            case POSSIBLE -> lagPossibleBonus;
            case STRONG -> lagStrongBonus;
        };
    }

    public double lowScore() {
        return lowScore;
    }

    public double mediumScore() {
        return mediumScore;
    }

    public double highScore() {
        return highScore;
    }

    public double criticalScore() {
        return criticalScore;
    }

    public double maxScore() {
        return maxScore;
    }

    public double hotspotMinActivityScore() {
        return hotspotMinActivityScore;
    }

    public int topLimit() {
        return topLimit;
    }

    public int correlationSamples() {
        return correlationSamples;
    }

    public int correlationMinSamples() {
        return correlationMinSamples;
    }

    public double possibleActivityDelta() {
        return possibleActivityDelta;
    }

    public double possibleMsptDelta() {
        return possibleMsptDelta;
    }

    public double strongPearson() {
        return strongPearson;
    }

    public double strongActivityScore() {
        return strongActivityScore;
    }

    public double strongMspt() {
        return strongMspt;
    }

    public int strongHoldSeconds() {
        return strongHoldSeconds;
    }

    public int possibleHoldSeconds() {
        return possibleHoldSeconds;
    }

    public int clusterNeighborhood() {
        return clusterNeighborhood;
    }

    public double clusterMinActivityScore() {
        return clusterMinActivityScore;
    }

    public double classificationMargin() {
        return classificationMargin;
    }

    public int protectionEnterSeconds() {
        return protectionEnterSeconds;
    }

    public int protectionExitSeconds() {
        return protectionExitSeconds;
    }

    public int protectionCooldownSeconds() {
        return protectionCooldownSeconds;
    }

    public int throttleRate(ThrottleType type, boolean emergency) {
        EnumMap<ThrottleType, Integer> source = emergency ? emergencyRates : throttleRates;
        return source.getOrDefault(type, 0);
    }

    public ServerPressure throttleMinPressure() {
        return throttleMinPressure;
    }

    public ServerPressure emergencyMinPressure() {
        return emergencyMinPressure;
    }

    public RiskLevel throttleMinRisk() {
        return throttleMinRisk;
    }

    public RiskLevel emergencyMinRisk() {
        return emergencyMinRisk;
    }

    public boolean requireLagCorrelationForEmergency() {
        return requireLagCorrelationForEmergency;
    }

    public boolean throttleEnabled(ThrottleType type) {
        return enabledThrottles.contains(type);
    }

    public boolean emergencyOnly(ThrottleType type) {
        return emergencyOnlyThrottles.contains(type);
    }

    public boolean spawnReasonThrottled(String reasonName) {
        return reasonName != null && spawnThrottleReasons.contains(reasonName);
    }

    public Set<String> spawnThrottleReasons() {
        return spawnThrottleReasons;
    }

    public Set<String> ignoredWorlds() {
        return ignoredWorlds;
    }

    public Set<String> monitoredWorlds() {
        return monitoredWorlds;
    }

    public boolean monitorsWorld(String worldName) {
        if (worldName == null) {
            return false;
        }
        if (!monitoredWorlds.isEmpty() && !containsIgnoreCase(monitoredWorlds, worldName)) {
            return false;
        }
        return true;
    }

    public boolean ignoresWorld(String worldName) {
        return containsIgnoreCase(ignoredWorlds, worldName);
    }

    public boolean notifyConsole() {
        return notifyConsole;
    }

    public boolean notifyAdmins() {
        return notifyAdmins;
    }

    public int notificationCooldownSeconds() {
        return notificationCooldownSeconds;
    }

    public int notificationPersistSeconds() {
        return notificationPersistSeconds;
    }

    public String adminPermission() {
        return adminPermission;
    }

    public boolean historyEnabled() {
        return historyEnabled;
    }

    public String historyFile() {
        return historyFile;
    }

    public int maxIncidents() {
        return maxIncidents;
    }

    public int maxIncidentAgeHours() {
        return maxIncidentAgeHours;
    }

    public int historyFlushSeconds() {
        return historyFlushSeconds;
    }

    public boolean debugLogEnabled() {
        return debugLogEnabled;
    }

    public int debugSnapshotIntervalSeconds() {
        return debugSnapshotIntervalSeconds;
    }

    public int debugHotspotTopN() {
        return debugHotspotTopN;
    }

    public int debugMaxFileSizeMb() {
        return debugMaxFileSizeMb;
    }

    public int debugMaxFiles() {
        return debugMaxFiles;
    }

    public String language() {
        return language;
    }

    private static boolean containsIgnoreCase(Set<String> values, String candidate) {
        for (String value : values) {
            if (value.equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        return false;
    }

    public static final class Builder {
        private OperatingMode mode = OperatingMode.MONITOR;
        private int aggregationIntervalTicks = 20;
        private int shortWindowSeconds = 5;
        private int longWindowSeconds = 30;
        private int bucketSeconds = 1;
        private int inactiveTtlSeconds = 180;
        private int maxTrackedChunks = 4096;
        private int hotspotCensusLimit = 32;
        private int hotspotCensusIntervalSeconds = 5;
        private int censusPerCycle = 4;
        private boolean censusEnabled = true;
        private int analysisMaxChunks = 768;
        private int clusterRefreshIntervalSeconds = 5;
        private boolean debug = false;
        private int maxRiskNotificationsPerCycle = 3;

        private double warningMspt = 35.0;
        private double highMspt = 48.0;
        private double criticalMspt = 65.0;
        private double warningTps = 18.0;
        private double highTps = 15.5;
        private double criticalTps = 12.0;
        private double warningExitMspt = 28.0;
        private double highExitMspt = 40.0;
        private double criticalExitMspt = 52.0;
        private double warningExitTps = 19.0;
        private double highExitTps = 17.0;
        private double criticalExitTps = 14.5;
        private int pressureEnterSeconds = 5;
        private int pressureExitSeconds = 10;
        private int pressureCooldownSeconds = 8;
        private int msptAverageSamples = 12;

        private final EnumMap<MetricType, Double> rateThresholds = defaultRates();
        private int excessiveItems = 220;
        private int excessiveVillagers = 48;
        private int excessiveMinecarts = 36;
        private int excessiveEntities = 180;
        private double redstoneOscillationPerSecond = 120.0;
        private double spikeRatio = 2.4;
        private double spikeMinShortPerSecond = 20.0;

        private final EnumMap<MetricType, Double> activityWeights = defaultWeights();
        private double itemDensityWeight = 14.0;
        private double villagerDensityWeight = 18.0;
        private double minecartDensityWeight = 14.0;
        private double entityDensityWeight = 12.0;
        private double pressureMultiplierNormal = 0.55;
        private double pressureMultiplierWarning = 1.00;
        private double pressureMultiplierHigh = 1.30;
        private double pressureMultiplierCritical = 1.60;
        private double lagNoneBonus = 0.0;
        private double lagPossibleBonus = 8.0;
        private double lagStrongBonus = 16.0;
        private double lowScore = 18.0;
        private double mediumScore = 36.0;
        private double highScore = 58.0;
        private double criticalScore = 78.0;
        private double maxScore = 100.0;
        private double hotspotMinActivityScore = 8.0;
        private int topLimit = 10;

        private int correlationSamples = 16;
        private int correlationMinSamples = 6;
        private double possibleActivityDelta = 12.0;
        private double possibleMsptDelta = 8.0;
        private double strongPearson = 0.62;
        private double strongActivityScore = 28.0;
        private double strongMspt = 40.0;
        private int strongHoldSeconds = 15;
        private int possibleHoldSeconds = 8;

        private int clusterNeighborhood = 1;
        private double clusterMinActivityScore = 16.0;
        private double classificationMargin = 0.18;

        private int protectionEnterSeconds = 6;
        private int protectionExitSeconds = 12;
        private int protectionCooldownSeconds = 10;
        private final EnumMap<ThrottleType, Integer> throttleRates = defaultThrottleRates();
        private final EnumMap<ThrottleType, Integer> emergencyRates = defaultEmergencyRates();
        private ServerPressure throttleMinPressure = ServerPressure.HIGH;
        private ServerPressure emergencyMinPressure = ServerPressure.CRITICAL;
        private RiskLevel throttleMinRisk = RiskLevel.HIGH;
        private RiskLevel emergencyMinRisk = RiskLevel.CRITICAL;
        private boolean requireLagCorrelationForEmergency = true;
        private Set<ThrottleType> enabledThrottles = Set.of(ThrottleType.values());
        private Set<ThrottleType> emergencyOnlyThrottles = Set.of(
                ThrottleType.REDSTONE, ThrottleType.PISTON, ThrottleType.ITEM, ThrottleType.MINECART);
        private Set<String> spawnThrottleReasons = Set.of("SPAWNER", "TRIAL_SPAWNER", "SLIME_SPLIT");

        private Set<String> ignoredWorlds = Set.of();
        private Set<String> monitoredWorlds = Set.of();

        private boolean notifyConsole = true;
        private boolean notifyAdmins = true;
        private int notificationCooldownSeconds = 45;
        private int notificationPersistSeconds = 60;
        private String adminPermission = "farmguard.status";

        private boolean historyEnabled = true;
        private String historyFile = "incidents.yml";
        private int maxIncidents = 40;
        private int maxIncidentAgeHours = 72;
        private int historyFlushSeconds = 60;
        private boolean debugLogEnabled = false;
        private int debugSnapshotIntervalSeconds = 10;
        private int debugHotspotTopN = 10;
        private int debugMaxFileSizeMb = 32;
        private int debugMaxFiles = 5;
        private String language = "zh_CN";

        public Builder mode(OperatingMode mode) {
            this.mode = mode == null ? OperatingMode.MONITOR : mode;
            return this;
        }

        public Builder debug(boolean debug) {
            this.debug = debug;
            return this;
        }

        public Builder rateThreshold(MetricType type, double value) {
            rateThresholds.put(type, value);
            return this;
        }

        public Builder activityWeight(MetricType type, double value) {
            activityWeights.put(type, value);
            return this;
        }

        public Builder throttleRate(ThrottleType type, int value) {
            throttleRates.put(type, value);
            return this;
        }

        public Builder emergencyRate(ThrottleType type, int value) {
            emergencyRates.put(type, value);
            return this;
        }

        public Builder ignoredWorlds(Set<String> worlds) {
            this.ignoredWorlds = worlds == null ? Set.of() : worlds;
            return this;
        }

        public Builder monitoredWorlds(Set<String> worlds) {
            this.monitoredWorlds = worlds == null ? Set.of() : worlds;
            return this;
        }

        public Builder enabledThrottles(Set<ThrottleType> types) {
            this.enabledThrottles = types == null ? Set.of(ThrottleType.values()) : types;
            return this;
        }

        public Builder warningMspt(double value) {
            this.warningMspt = value;
            return this;
        }

        public Builder highMspt(double value) {
            this.highMspt = value;
            return this;
        }

        public Builder criticalMspt(double value) {
            this.criticalMspt = value;
            return this;
        }

        public Builder warningExitMspt(double value) {
            this.warningExitMspt = value;
            return this;
        }

        public Builder highExitMspt(double value) {
            this.highExitMspt = value;
            return this;
        }

        public Builder criticalExitMspt(double value) {
            this.criticalExitMspt = value;
            return this;
        }

        public Builder warningTps(double value) {
            this.warningTps = value;
            return this;
        }

        public Builder highTps(double value) {
            this.highTps = value;
            return this;
        }

        public Builder criticalTps(double value) {
            this.criticalTps = value;
            return this;
        }

        public Builder warningExitTps(double value) {
            this.warningExitTps = value;
            return this;
        }

        public Builder highExitTps(double value) {
            this.highExitTps = value;
            return this;
        }

        public Builder criticalExitTps(double value) {
            this.criticalExitTps = value;
            return this;
        }

        public Builder pressureEnterSeconds(int value) {
            this.pressureEnterSeconds = value;
            return this;
        }

        public Builder pressureExitSeconds(int value) {
            this.pressureExitSeconds = value;
            return this;
        }

        public Builder pressureCooldownSeconds(int value) {
            this.pressureCooldownSeconds = value;
            return this;
        }

        public Builder pressureMultiplierNormal(double value) {
            this.pressureMultiplierNormal = value;
            return this;
        }

        public Builder pressureMultiplierHigh(double value) {
            this.pressureMultiplierHigh = value;
            return this;
        }

        public Builder pressureMultiplierCritical(double value) {
            this.pressureMultiplierCritical = value;
            return this;
        }

        public Builder excessiveItems(int value) {
            this.excessiveItems = value;
            return this;
        }

        public Builder excessiveVillagers(int value) {
            this.excessiveVillagers = value;
            return this;
        }

        public Builder excessiveMinecarts(int value) {
            this.excessiveMinecarts = value;
            return this;
        }

        public Builder excessiveEntities(int value) {
            this.excessiveEntities = value;
            return this;
        }

        public Builder redstoneOscillationPerSecond(double value) {
            this.redstoneOscillationPerSecond = value;
            return this;
        }

        public Builder spikeRatio(double value) {
            this.spikeRatio = value;
            return this;
        }

        public Builder lowScore(double value) {
            this.lowScore = value;
            return this;
        }

        public Builder mediumScore(double value) {
            this.mediumScore = value;
            return this;
        }

        public Builder highScore(double value) {
            this.highScore = value;
            return this;
        }

        public Builder criticalScore(double value) {
            this.criticalScore = value;
            return this;
        }

        public Builder maxScore(double value) {
            this.maxScore = value;
            return this;
        }

        public Builder protectionEnterSeconds(int value) {
            this.protectionEnterSeconds = value;
            return this;
        }

        public Builder protectionExitSeconds(int value) {
            this.protectionExitSeconds = value;
            return this;
        }

        public Builder protectionCooldownSeconds(int value) {
            this.protectionCooldownSeconds = value;
            return this;
        }

        public Builder requireLagCorrelationForEmergency(boolean value) {
            this.requireLagCorrelationForEmergency = value;
            return this;
        }

        public Builder throttleMinPressure(ServerPressure value) {
            this.throttleMinPressure = value;
            return this;
        }

        public Builder emergencyMinPressure(ServerPressure value) {
            this.emergencyMinPressure = value;
            return this;
        }

        public Builder throttleMinRisk(RiskLevel value) {
            this.throttleMinRisk = value;
            return this;
        }

        public Builder emergencyMinRisk(RiskLevel value) {
            this.emergencyMinRisk = value;
            return this;
        }

        public Builder shortWindowSeconds(int value) {
            this.shortWindowSeconds = value;
            return this;
        }

        public Builder longWindowSeconds(int value) {
            this.longWindowSeconds = value;
            return this;
        }

        public Builder bucketSeconds(int value) {
            this.bucketSeconds = value;
            return this;
        }

        public Builder inactiveTtlSeconds(int value) {
            this.inactiveTtlSeconds = value;
            return this;
        }

        public Builder maxTrackedChunks(int value) {
            this.maxTrackedChunks = value;
            return this;
        }

        public Builder correlationSamples(int value) {
            this.correlationSamples = value;
            return this;
        }

        public Builder correlationMinSamples(int value) {
            this.correlationMinSamples = value;
            return this;
        }

        public Builder possibleActivityDelta(double value) {
            this.possibleActivityDelta = value;
            return this;
        }

        public Builder possibleMsptDelta(double value) {
            this.possibleMsptDelta = value;
            return this;
        }

        public Builder strongPearson(double value) {
            this.strongPearson = value;
            return this;
        }

        public Builder clusterNeighborhood(int value) {
            this.clusterNeighborhood = value;
            return this;
        }

        public Builder clusterMinActivityScore(double value) {
            this.clusterMinActivityScore = value;
            return this;
        }

        public Builder classificationMargin(double value) {
            this.classificationMargin = value;
            return this;
        }

        public Builder hotspotMinActivityScore(double value) {
            this.hotspotMinActivityScore = value;
            return this;
        }

        public Builder topLimit(int value) {
            this.topLimit = value;
            return this;
        }

        public Builder notifyConsole(boolean value) {
            this.notifyConsole = value;
            return this;
        }

        public Builder notifyAdmins(boolean value) {
            this.notifyAdmins = value;
            return this;
        }

        public Builder notificationCooldownSeconds(int value) {
            this.notificationCooldownSeconds = value;
            return this;
        }

        public Builder historyEnabled(boolean value) {
            this.historyEnabled = value;
            return this;
        }

        public Builder historyFile(String value) {
            this.historyFile = value == null || value.isBlank() ? "incidents.yml" : value;
            return this;
        }

        public Builder maxIncidents(int value) {
            this.maxIncidents = value;
            return this;
        }

        public Builder maxIncidentAgeHours(int value) {
            this.maxIncidentAgeHours = value;
            return this;
        }

        public Builder aggregationIntervalTicks(int value) {
            this.aggregationIntervalTicks = value;
            return this;
        }

        public Builder msptAverageSamples(int value) {
            this.msptAverageSamples = value;
            return this;
        }

        public Builder itemDensityWeight(double value) {
            this.itemDensityWeight = value;
            return this;
        }

        public Builder villagerDensityWeight(double value) {
            this.villagerDensityWeight = value;
            return this;
        }

        public Builder minecartDensityWeight(double value) {
            this.minecartDensityWeight = value;
            return this;
        }

        public Builder entityDensityWeight(double value) {
            this.entityDensityWeight = value;
            return this;
        }

        public Builder pressureMultiplierWarning(double value) {
            this.pressureMultiplierWarning = value;
            return this;
        }

        public Builder lagPossibleBonus(double value) {
            this.lagPossibleBonus = value;
            return this;
        }

        public Builder lagStrongBonus(double value) {
            this.lagStrongBonus = value;
            return this;
        }

        public Builder spikeMinShortPerSecond(double value) {
            this.spikeMinShortPerSecond = value;
            return this;
        }

        public Builder strongActivityScore(double value) {
            this.strongActivityScore = value;
            return this;
        }

        public Builder strongMspt(double value) {
            this.strongMspt = value;
            return this;
        }

        public Builder strongHoldSeconds(int value) {
            this.strongHoldSeconds = value;
            return this;
        }

        public Builder possibleHoldSeconds(int value) {
            this.possibleHoldSeconds = value;
            return this;
        }

        public Builder hotspotCensusLimit(int value) {
            this.hotspotCensusLimit = value;
            return this;
        }

        public Builder hotspotCensusIntervalSeconds(int value) {
            this.hotspotCensusIntervalSeconds = value;
            return this;
        }

        public Builder censusPerCycle(int value) {
            this.censusPerCycle = value;
            return this;
        }

        public Builder censusEnabled(boolean value) {
            this.censusEnabled = value;
            return this;
        }

        public Builder analysisMaxChunks(int value) {
            this.analysisMaxChunks = value;
            return this;
        }

        public Builder maxRiskNotificationsPerCycle(int value) {
            this.maxRiskNotificationsPerCycle = value;
            return this;
        }

        public Builder emergencyOnlyThrottles(Set<ThrottleType> types) {
            this.emergencyOnlyThrottles = types == null ? Set.of() : types;
            return this;
        }

        public Builder spawnThrottleReasons(Set<String> reasons) {
            this.spawnThrottleReasons = reasons == null ? Set.of() : reasons;
            return this;
        }

        public Builder clusterRefreshIntervalSeconds(int value) {
            this.clusterRefreshIntervalSeconds = value;
            return this;
        }

        public Builder notificationPersistSeconds(int value) {
            this.notificationPersistSeconds = value;
            return this;
        }

        public Builder adminPermission(String value) {
            this.adminPermission = value == null || value.isBlank() ? "farmguard.status" : value;
            return this;
        }

        public Builder historyFlushSeconds(int value) {
            this.historyFlushSeconds = value;
            return this;
        }

        public Builder debugLogEnabled(boolean value) {
            this.debugLogEnabled = value;
            return this;
        }

        public Builder debugSnapshotIntervalSeconds(int value) {
            this.debugSnapshotIntervalSeconds = value;
            return this;
        }

        public Builder debugHotspotTopN(int value) {
            this.debugHotspotTopN = value;
            return this;
        }

        public Builder debugMaxFileSizeMb(int value) {
            this.debugMaxFileSizeMb = value;
            return this;
        }

        public Builder debugMaxFiles(int value) {
            this.debugMaxFiles = value;
            return this;
        }

        public Builder language(String value) {
            this.language = value == null || value.isBlank() ? "zh_CN" : value;
            return this;
        }

        public FarmGuardSettings build() {
            if (shortWindowSeconds > longWindowSeconds) {
                int swap = shortWindowSeconds;
                shortWindowSeconds = longWindowSeconds;
                longWindowSeconds = swap;
            }
            if (maxTrackedChunks < 1) {
                maxTrackedChunks = 4096;
            }
            if (inactiveTtlSeconds < 1) {
                inactiveTtlSeconds = 180;
            }
            if (notificationCooldownSeconds < 1) {
                notificationCooldownSeconds = 45;
            }
            if (censusPerCycle < 1) {
                censusPerCycle = 4;
            }
            if (analysisMaxChunks < 1) {
                analysisMaxChunks = 768;
            }
            if (maxRiskNotificationsPerCycle < 1) {
                maxRiskNotificationsPerCycle = 3;
            }
            if (bucketSeconds < 1) {
                bucketSeconds = 1;
            }
            if (strongHoldSeconds < 1) {
                strongHoldSeconds = 15;
            }
            if (possibleHoldSeconds < 0) {
                possibleHoldSeconds = 8;
            }
            if (debugSnapshotIntervalSeconds < 5 || debugSnapshotIntervalSeconds > 300) {
                debugSnapshotIntervalSeconds = 10;
            }
            if (debugHotspotTopN < 0 || debugHotspotTopN > 50) {
                debugHotspotTopN = 10;
            }
            if (debugMaxFileSizeMb < 1 || debugMaxFileSizeMb > 512) {
                debugMaxFileSizeMb = 32;
            }
            if (debugMaxFiles < 1 || debugMaxFiles > 20) {
                debugMaxFiles = 5;
            }
            if (language == null || language.isBlank()) {
                language = "zh_CN";
            }
            return new FarmGuardSettings(this);
        }

        private static EnumMap<MetricType, Double> defaultRates() {
            EnumMap<MetricType, Double> map = new EnumMap<>(MetricType.class);
            map.put(MetricType.REDSTONE, 80.0);
            map.put(MetricType.PISTON, 16.0);
            map.put(MetricType.HOPPER, 36.0);
            map.put(MetricType.ENTITY_SPAWN, 12.0);
            map.put(MetricType.ENTITY_DEATH, 12.0);
            map.put(MetricType.ITEM, 40.0);
            map.put(MetricType.VILLAGER, 8.0);
            map.put(MetricType.MINECART, 12.0);
            map.put(MetricType.BREEDING, 4.0);
            map.put(MetricType.BLOCK_UPDATE, 40.0);
            return map;
        }

        private static EnumMap<MetricType, Double> defaultWeights() {
            EnumMap<MetricType, Double> map = new EnumMap<>(MetricType.class);
            map.put(MetricType.REDSTONE, 18.0);
            map.put(MetricType.PISTON, 16.0);
            map.put(MetricType.HOPPER, 22.0);
            map.put(MetricType.ENTITY_SPAWN, 14.0);
            map.put(MetricType.ENTITY_DEATH, 8.0);
            map.put(MetricType.ITEM, 16.0);
            map.put(MetricType.VILLAGER, 20.0);
            map.put(MetricType.BREEDING, 10.0);
            map.put(MetricType.MINECART, 16.0);
            map.put(MetricType.BLOCK_UPDATE, 8.0);
            return map;
        }

        private static EnumMap<ThrottleType, Integer> defaultThrottleRates() {
            EnumMap<ThrottleType, Integer> map = new EnumMap<>(ThrottleType.class);
            map.put(ThrottleType.HOPPER, 8);
            map.put(ThrottleType.SPAWN, 2);
            map.put(ThrottleType.BREEDING, 1);
            map.put(ThrottleType.PISTON, 4);
            map.put(ThrottleType.REDSTONE, 20);
            map.put(ThrottleType.ITEM, 10);
            map.put(ThrottleType.MINECART, 2);
            return map;
        }

        private static EnumMap<ThrottleType, Integer> defaultEmergencyRates() {
            EnumMap<ThrottleType, Integer> map = new EnumMap<>(ThrottleType.class);
            map.put(ThrottleType.HOPPER, 2);
            map.put(ThrottleType.SPAWN, 0);
            map.put(ThrottleType.BREEDING, 0);
            map.put(ThrottleType.PISTON, 1);
            map.put(ThrottleType.REDSTONE, 4);
            map.put(ThrottleType.ITEM, 2);
            map.put(ThrottleType.MINECART, 0);
            return map;
        }
    }
}
