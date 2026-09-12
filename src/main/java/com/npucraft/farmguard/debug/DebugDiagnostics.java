package com.npucraft.farmguard.debug;

import com.npucraft.farmguard.config.FarmGuardSettings;
import com.npucraft.farmguard.model.AutomationCluster;
import com.npucraft.farmguard.model.ChunkKey;
import com.npucraft.farmguard.model.LagCorrelation;
import com.npucraft.farmguard.model.LagIncident;
import com.npucraft.farmguard.model.OperatingMode;
import com.npucraft.farmguard.model.ProtectionLevel;
import com.npucraft.farmguard.model.RiskAssessment;
import com.npucraft.farmguard.model.RiskLevel;
import com.npucraft.farmguard.model.RiskReason;
import com.npucraft.farmguard.model.ServerMetrics;
import com.npucraft.farmguard.model.ServerPressure;
import com.npucraft.farmguard.model.ThrottleType;
import com.npucraft.farmguard.protection.ProtectionManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns already-computed FarmGuard state into debug records. Never walks World,
 * Entity, or Inventory objects.
 */
public final class DebugDiagnostics {

    private static final int LAST_STATE_CAP = 2048;

    private final DebugLogService log;
    private final DebugActionCounters actions;
    private final Map<ChunkKey, RiskLevel> lastRisk = new HashMap<>();
    private final Map<ChunkKey, LagCorrelation> lastCorrelation = new HashMap<>();
    private ServerPressure lastPressure;
    private long lastSnapshotMs;
    private long windowStartMs;
    private long startedAtMs;
    private String pluginVersion = "";

    public DebugDiagnostics(DebugLogService log, DebugActionCounters actions) {
        this.log = log;
        this.actions = actions;
    }

    public void setPluginVersion(String version) {
        this.pluginVersion = version == null ? "" : version;
    }

    public void onWriterStarted(boolean startup, String paperVersion, String javaVersion, OperatingMode mode, int configErrors) {
        startedAtMs = System.currentTimeMillis();
        lastSnapshotMs = 0L;
        windowStartMs = startedAtMs;
        lastPressure = null;
        lastRisk.clear();
        lastCorrelation.clear();
        Map<String, Object> session = base();
        session.put("pluginVersion", pluginVersion);
        log.offer(DebugLogRecord.high("session_start", session));
        if (startup) {
            Map<String, Object> start = base();
            start.put("pluginVersion", pluginVersion);
            start.put("paperVersion", paperVersion);
            start.put("javaVersion", javaVersion);
            start.put("mode", name(mode));
            start.put("logSchemaVersion", DebugLogRecord.SCHEMA);
            start.put("configErrors", configErrors);
            log.offer(DebugLogRecord.high("plugin_start", start));
        } else {
            Map<String, Object> enabled = base();
            enabled.put("mode", name(mode));
            enabled.put("pluginVersion", pluginVersion);
            log.offer(DebugLogRecord.high("debug_logging_enabled", enabled));
        }
    }

    public void onWriterStopping(boolean pluginStop, OperatingMode mode, int trackedChunks, int openIncidents, int activeProtections) {
        if (!log.writerAlive() && !log.enabled()) {
            return;
        }
        if (pluginStop) {
            Map<String, Object> stop = base();
            stop.put("mode", name(mode));
            stop.put("uptimeMs", Math.max(0L, System.currentTimeMillis() - startedAtMs));
            stop.put("trackedChunks", trackedChunks);
            stop.put("openIncidentCount", openIncidents);
            stop.put("activeProtectionCount", activeProtections);
            log.offer(DebugLogRecord.high("plugin_stop", stop));
        } else {
            Map<String, Object> disabled = base();
            disabled.put("mode", name(mode));
            log.offer(DebugLogRecord.high("debug_logging_disabled", disabled));
        }
    }

    public void onTick(
            FarmGuardSettings settings,
            ServerMetrics server,
            List<RiskAssessment> assessments,
            List<RiskAssessment> topHotspots,
            Map<ChunkKey, ProtectionLevel> protectionByChunk,
            List<ProtectionManager.Change> changes,
            List<AutomationCluster> clusters,
            int trackedChunks,
            int hotspotCount,
            int clusterCount,
            int activeProtectionCount,
            LagIncident currentIncident,
            boolean lagStarted,
            LagIncident startedIncident,
            boolean lagEnded,
            LagIncident endedIncident,
            long analysisDurationMs,
            OperatingMode mode
    ) {
        if (!log.enabled()) {
            lastPressure = null;
            lastRisk.clear();
            lastCorrelation.clear();
            lastSnapshotMs = 0L;
            return;
        }
        long now = server == null ? System.currentTimeMillis() : server.sampleTimeMs();
        emitPressure(server);
        emitRiskAndCorrelation(assessments, server, clusters);
        emitProtection(changes, assessments, server, clusters, currentIncident);
        if (lagStarted && startedIncident != null) {
            emitIncidentStart(startedIncident, server, topHotspots);
        }
        if (lagEnded && endedIncident != null) {
            emitIncidentEnd(endedIncident, server);
        }
        int intervalMs = Math.max(5, settings.debugSnapshotIntervalSeconds()) * 1000;
        if (lastSnapshotMs == 0L || now - lastSnapshotMs >= intervalMs) {
            int windowSeconds = lastSnapshotMs == 0L
                    ? settings.debugSnapshotIntervalSeconds()
                    : Math.max(1, (int) ((now - windowStartMs) / 1000L));
            emitSnapshots(
                    settings,
                    server,
                    topHotspots,
                    protectionByChunk,
                    clusters,
                    trackedChunks,
                    hotspotCount,
                    clusterCount,
                    activeProtectionCount,
                    currentIncident,
                    analysisDurationMs,
                    mode,
                    windowSeconds
            );
            lastSnapshotMs = now;
            windowStartMs = now;
        }
    }

    public void onModeChange(OperatingMode from, OperatingMode to, String source) {
        if (!log.enabled() || from == to) {
            return;
        }
        Map<String, Object> fields = base();
        fields.put("from", name(from));
        fields.put("to", name(to));
        fields.put("source", source == null ? "UNKNOWN" : source);
        log.offer(DebugLogRecord.high("mode_change", fields));
    }

    public void onConfigReload(boolean success, int errorCount, FarmGuardSettings previous, FarmGuardSettings next, OperatingMode runtimeMode) {
        if (!log.enabled()) {
            return;
        }
        Map<String, Object> fields = base();
        fields.put("success", success);
        fields.put("errorCount", errorCount);
        fields.put("debugLogEnabled", next != null && next.debugLogEnabled());
        fields.put("mode", name(runtimeMode));
        fields.put("changedSections", changedSections(previous, next));
        log.offer(DebugLogRecord.high("config_reload", fields));
    }

    public void onWhitelist(boolean added, ChunkKey key, String clusterId) {
        if (!log.enabled()) {
            return;
        }
        Map<String, Object> fields = chunkFields(key);
        if (clusterId != null && !clusterId.isBlank()) {
            fields.put("clusterId", clusterId);
        }
        log.offer(DebugLogRecord.high(added ? "whitelist_added" : "whitelist_removed", fields));
    }

    public void onInternalError(String component, Throwable error) {
        if (!log.enabled() || error == null) {
            return;
        }
        Map<String, Object> fields = base();
        fields.put("component", component == null ? "unknown" : component);
        fields.put("error", error.getClass().getSimpleName());
        String message = error.getMessage();
        if (message != null && !message.isBlank()) {
            fields.put("message", message.length() > 300 ? message.substring(0, 300) : message);
        }
        log.offer(DebugLogRecord.high("internal_error", fields));
    }

    private void emitPressure(ServerMetrics server) {
        if (server == null) {
            return;
        }
        ServerPressure current = server.pressure();
        ServerPressure previous = lastPressure;
        lastPressure = current;
        if (previous == null || previous == current) {
            return;
        }
        Map<String, Object> fields = base();
        fields.put("from", previous.name());
        fields.put("to", current.name());
        fields.put("tps", round1(server.tps()));
        fields.put("mspt", round1(server.mspt()));
        fields.put("avgMspt", round1(server.averageMspt()));
        log.offer(DebugLogRecord.high("server_pressure_transition", fields));
    }

    private void emitRiskAndCorrelation(
            List<RiskAssessment> assessments,
            ServerMetrics server,
            List<AutomationCluster> clusters
    ) {
        if (assessments == null) {
            return;
        }
        for (RiskAssessment assessment : assessments) {
            ChunkKey key = assessment.chunk();
            RiskLevel previousRisk = lastRisk.put(key, assessment.level());
            if (previousRisk == null) {
                previousRisk = RiskLevel.NONE;
            }
            if (previousRisk != assessment.level()) {
                Map<String, Object> fields = chunkFields(key);
                putCluster(fields, clusters, key);
                fields.put("from", previousRisk.name());
                fields.put("to", assessment.level().name());
                fields.put("oldRisk", previousRisk.name());
                fields.put("newRisk", assessment.level().name());
                fields.put("riskScore", round1(assessment.riskScore()));
                fields.put("activityScore", round1(assessment.activityScore()));
                fields.put("riskLevel", assessment.level().name());
                fields.put("riskReasons", reasonNames(assessment.reasons()));
                fields.put("correlation", name(assessment.correlation()));
                if (server != null) {
                    fields.put("mspt", round1(server.mspt()));
                    fields.put("pressure", name(server.pressure()));
                }
                log.offer(DebugLogRecord.high("risk_transition", fields));
            }
            LagCorrelation previousCorr = lastCorrelation.put(key, assessment.correlation());
            if (previousCorr == null) {
                previousCorr = LagCorrelation.NONE;
            }
            if (previousCorr != assessment.correlation()) {
                Map<String, Object> fields = chunkFields(key);
                putCluster(fields, clusters, key);
                fields.put("from", previousCorr.name());
                fields.put("to", assessment.correlation().name());
                fields.put("activityScore", round1(assessment.activityScore()));
                fields.put("riskScore", round1(assessment.riskScore()));
                fields.put("riskLevel", assessment.level().name());
                fields.put("correlation", assessment.correlation().name());
                if (server != null) {
                    fields.put("mspt", round1(server.mspt()));
                    fields.put("pressure", name(server.pressure()));
                }
                log.offer(DebugLogRecord.high("correlation_transition", fields));
            }
        }
        if (lastRisk.size() > LAST_STATE_CAP) {
            lastRisk.keySet().retainAll(currentKeys(assessments));
            lastCorrelation.keySet().retainAll(currentKeys(assessments));
        }
    }

    private void emitProtection(
            List<ProtectionManager.Change> changes,
            List<RiskAssessment> assessments,
            ServerMetrics server,
            List<AutomationCluster> clusters,
            LagIncident currentIncident
    ) {
        if (changes == null) {
            return;
        }
        Map<ChunkKey, RiskAssessment> byChunk = index(assessments);
        for (ProtectionManager.Change change : changes) {
            if (change == null || change.type() == ProtectionManager.ChangeType.NONE || change.state() == null) {
                continue;
            }
            ChunkKey key = change.state().chunk();
            RiskAssessment assessment = byChunk.get(key);
            ProtectionLevel from = change.previous();
            ProtectionLevel to = change.state().applied();
            Map<String, Object> fields = chunkFields(key);
            putCluster(fields, clusters, key);
            fields.put("from", name(from));
            fields.put("to", name(to));
            fields.put("protection", name(to));
            if (server != null) {
                fields.put("mspt", round1(server.mspt()));
                fields.put("pressure", name(server.pressure()));
            }
            if (assessment != null) {
                fields.put("activityScore", round1(assessment.activityScore()));
                fields.put("riskScore", round1(assessment.riskScore()));
                fields.put("riskLevel", assessment.level().name());
                fields.put("correlation", name(assessment.correlation()));
                fields.put("riskReasons", reasonNames(assessment.reasons()));
            } else {
                fields.put("riskReasons", reasonNames(change.state().reasons()));
            }
            if (currentIncident != null) {
                fields.put("incidentId", currentIncident.id());
            }
            log.offer(DebugLogRecord.high("protection_transition", fields));
            if (change.type() == ProtectionManager.ChangeType.RECOVERED && to != ProtectionLevel.NORMAL) {
                Map<String, Object> recovery = chunkFields(key);
                putCluster(recovery, clusters, key);
                recovery.put("from", name(from));
                recovery.put("to", name(to));
                if (currentIncident != null) {
                    recovery.put("incidentId", currentIncident.id());
                }
                log.offer(DebugLogRecord.high("recovery_started", recovery));
            }
            if (to == ProtectionLevel.NORMAL && from != null && from != ProtectionLevel.NORMAL) {
                Map<String, Object> done = chunkFields(key);
                putCluster(done, clusters, key);
                done.put("from", name(from));
                done.put("to", name(to));
                if (currentIncident != null) {
                    done.put("incidentId", currentIncident.id());
                }
                if (server != null) {
                    done.put("finalMspt", round1(server.mspt()));
                    done.put("finalPressure", name(server.pressure()));
                }
                log.offer(DebugLogRecord.high("recovery_completed", done));
            }
        }
    }

    private void emitIncidentStart(LagIncident incident, ServerMetrics server, List<RiskAssessment> assessments) {
        Map<String, Object> fields = base();
        fields.put("incidentId", incident.id());
        if (server != null) {
            fields.put("mspt", round1(server.mspt()));
            fields.put("tps", round1(server.tps()));
            fields.put("pressure", name(server.pressure()));
        }
        List<Map<String, Object>> suspects = new ArrayList<>();
        int limit = Math.min(5, assessments == null ? 0 : assessments.size());
        for (int i = 0; i < limit; i++) {
            RiskAssessment assessment = assessments.get(i);
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("world", assessment.chunk().worldName());
            row.put("chunkX", assessment.chunk().x());
            row.put("chunkZ", assessment.chunk().z());
            row.put("riskLevel", assessment.level().name());
            row.put("riskScore", round1(assessment.riskScore()));
            row.put("correlation", name(assessment.correlation()));
            suspects.add(row);
        }
        fields.put("suspects", suspects);
        log.offer(DebugLogRecord.high("incident_start", fields));
    }

    private void emitIncidentEnd(LagIncident incident, ServerMetrics server) {
        Map<String, Object> fields = base();
        fields.put("incidentId", incident.id());
        long end = incident.endMs() == null ? System.currentTimeMillis() : incident.endMs();
        long duration = Math.max(0L, end - incident.startMs());
        fields.put("durationMs", duration);
        fields.put("peakMspt", round1(incident.peakMspt()));
        fields.put("lowestTps", round1(incident.lowestTps()));
        fields.put("protectionsTriggered", incident.protectionActions().size());
        fields.put("recoveryTimeMs", duration);
        fields.put("recovered", incident.recovered());
        if (server != null) {
            fields.put("finalMspt", round1(server.mspt()));
            fields.put("finalPressure", name(server.pressure()));
            fields.put("tps", round1(server.tps()));
        }
        log.offer(DebugLogRecord.high("incident_end", fields));
        if (incident.recovered()) {
            Map<String, Object> recovery = base();
            recovery.put("incidentId", incident.id());
            recovery.put("durationMs", duration);
            if (server != null) {
                recovery.put("finalMspt", round1(server.mspt()));
                recovery.put("finalPressure", name(server.pressure()));
            }
            log.offer(DebugLogRecord.high("recovery_completed", recovery));
        }
    }

    private void emitSnapshots(
            FarmGuardSettings settings,
            ServerMetrics server,
            List<RiskAssessment> topHotspots,
            Map<ChunkKey, ProtectionLevel> protectionByChunk,
            List<AutomationCluster> clusters,
            int trackedChunks,
            int hotspotCount,
            int clusterCount,
            int activeProtectionCount,
            LagIncident currentIncident,
            long analysisDurationMs,
            OperatingMode mode,
            int windowSeconds
    ) {
        Map<String, Object> snapshot = base();
        snapshot.put("pluginVersion", pluginVersion);
        snapshot.put("mode", name(mode));
        if (server != null) {
            snapshot.put("tps", round1(server.tps()));
            snapshot.put("mspt", round1(server.mspt()));
            snapshot.put("avgMspt", round1(server.averageMspt()));
            snapshot.put("pressure", name(server.pressure()));
        }
        snapshot.put("trackedChunks", trackedChunks);
        snapshot.put("hotspotCount", hotspotCount);
        snapshot.put("clusterCount", clusterCount);
        snapshot.put("activeProtectionCount", activeProtectionCount);
        snapshot.put("activeIncident", currentIncident != null && currentIncident.open());
        if (currentIncident != null && currentIncident.open()) {
            snapshot.put("incidentId", currentIncident.id());
        }
        snapshot.put("droppedLogEntries", log.droppedLogEntries());
        if (analysisDurationMs >= 0L) {
            snapshot.put("analysisDurationMs", analysisDurationMs);
        }
        log.offer(DebugLogRecord.normal("server_snapshot", snapshot));

        int topN = settings.debugHotspotTopN();
        if (topN > 0 && topHotspots != null) {
            int limit = Math.min(topN, topHotspots.size());
            for (int i = 0; i < limit; i++) {
                log.offer(DebugLogRecord.normal(
                        "hotspot_snapshot",
                        hotspotFields(topHotspots.get(i), clusters, protectionByChunk)
                ));
            }
        }
        if (topN > 0 && clusters != null) {
            int emitted = 0;
            for (AutomationCluster cluster : clusters) {
                if (emitted >= topN) {
                    break;
                }
                if (cluster.riskLevel().atLeast(RiskLevel.HIGH)
                        || cluster.activityScore() >= settings.clusterMinActivityScore()) {
                    log.offer(DebugLogRecord.normal("cluster_snapshot", clusterFields(cluster)));
                    emitted++;
                }
            }
        }
        for (DebugActionCounters.Window window : actions.drain(Math.max(topN, 10))) {
            log.offer(DebugLogRecord.normal("protection_activity", activityFields(window, windowSeconds)));
        }
    }

    private Map<String, Object> hotspotFields(
            RiskAssessment assessment,
            List<AutomationCluster> clusters,
            Map<ChunkKey, ProtectionLevel> protectionByChunk
    ) {
        Map<String, Object> fields = chunkFields(assessment.chunk());
        putCluster(fields, clusters, assessment.chunk());
        fields.put("activityScore", round1(assessment.activityScore()));
        fields.put("riskScore", round1(assessment.riskScore()));
        fields.put("riskLevel", assessment.level().name());
        fields.put("riskReasons", reasonNames(assessment.reasons()));
        fields.put("correlation", name(assessment.correlation()));
        ProtectionLevel protection = protectionByChunk == null
                ? ProtectionLevel.NORMAL
                : protectionByChunk.getOrDefault(assessment.chunk(), ProtectionLevel.NORMAL);
        fields.put("protection", name(protection));
        if (assessment.snapshot() != null) {
            fields.put("estimatedEntityDensity", assessment.snapshot().totalEntities());
        }
        return fields;
    }

    private Map<String, Object> clusterFields(AutomationCluster cluster) {
        Map<String, Object> fields = base();
        fields.put("clusterId", cluster.id());
        fields.put("world", cluster.worldName());
        fields.put("memberChunkCount", cluster.chunks().size());
        fields.put("classification", cluster.type() == null ? "UNKNOWN_AUTOMATION" : cluster.type().name());
        fields.put("activityScore", round1(cluster.activityScore()));
        fields.put("riskScore", round1(cluster.riskScore()));
        fields.put("riskLevel", cluster.riskLevel().name());
        fields.put("riskReasons", reasonNames(cluster.reasons()));
        fields.put("correlation", name(cluster.correlation()));
        fields.put("protection", name(cluster.protectionLevel()));
        return fields;
    }

    private Map<String, Object> activityFields(DebugActionCounters.Window window, int windowSeconds) {
        Map<String, Object> fields = chunkFields(window.chunk());
        fields.put("windowSeconds", windowSeconds);
        fields.put("hopperAllowed", window.observed(ThrottleType.HOPPER));
        fields.put("hopperSuppressed", window.suppressed(ThrottleType.HOPPER));
        fields.put("redstoneObserved", window.observed(ThrottleType.REDSTONE));
        fields.put("redstoneSuppressed", window.suppressed(ThrottleType.REDSTONE));
        fields.put("pistonObserved", window.observed(ThrottleType.PISTON));
        fields.put("pistonSuppressed", window.suppressed(ThrottleType.PISTON));
        fields.put("spawnObserved", window.observed(ThrottleType.SPAWN));
        fields.put("spawnSuppressed", window.suppressed(ThrottleType.SPAWN));
        fields.put("breedingObserved", window.observed(ThrottleType.BREEDING));
        fields.put("breedingSuppressed", window.suppressed(ThrottleType.BREEDING));
        fields.put("itemObserved", window.observed(ThrottleType.ITEM));
        fields.put("itemSuppressed", window.suppressed(ThrottleType.ITEM));
        fields.put("minecartObserved", window.observed(ThrottleType.MINECART));
        fields.put("minecartSuppressed", window.suppressed(ThrottleType.MINECART));
        return fields;
    }

    private static Map<String, Object> base() {
        return new LinkedHashMap<>();
    }

    private static Map<String, Object> chunkFields(ChunkKey key) {
        Map<String, Object> fields = base();
        if (key != null) {
            fields.put("world", key.worldName());
            fields.put("chunkX", key.x());
            fields.put("chunkZ", key.z());
        }
        return fields;
    }

    private static void putCluster(Map<String, Object> fields, List<AutomationCluster> clusters, ChunkKey key) {
        if (clusters == null || key == null) {
            return;
        }
        for (AutomationCluster cluster : clusters) {
            if (cluster.chunks().contains(key)) {
                fields.put("clusterId", cluster.id());
                return;
            }
        }
    }

    private static List<String> reasonNames(Iterable<RiskReason> reasons) {
        List<String> names = new ArrayList<>();
        if (reasons != null) {
            for (RiskReason reason : reasons) {
                names.add(reason.name());
            }
        }
        return names;
    }

    private static Map<ChunkKey, RiskAssessment> index(List<RiskAssessment> assessments) {
        Map<ChunkKey, RiskAssessment> map = new HashMap<>();
        if (assessments == null) {
            return map;
        }
        for (RiskAssessment assessment : assessments) {
            map.put(assessment.chunk(), assessment);
        }
        return map;
    }

    private static List<ChunkKey> currentKeys(List<RiskAssessment> assessments) {
        List<ChunkKey> keys = new ArrayList<>(assessments.size());
        for (RiskAssessment assessment : assessments) {
            keys.add(assessment.chunk());
        }
        return keys;
    }

    static List<String> changedSections(FarmGuardSettings previous, FarmGuardSettings next) {
        List<String> changed = new ArrayList<>();
        if (previous == null || next == null) {
            return changed;
        }
        if (previous.mode() != next.mode()) {
            changed.add("mode");
        }
        if (previous.debugLogEnabled() != next.debugLogEnabled()
                || previous.debugSnapshotIntervalSeconds() != next.debugSnapshotIntervalSeconds()
                || previous.debugHotspotTopN() != next.debugHotspotTopN()
                || previous.debugMaxFileSizeMb() != next.debugMaxFileSizeMb()
                || previous.debugMaxFiles() != next.debugMaxFiles()) {
            changed.add("debug-log");
        }
        if (previous.aggregationIntervalTicks() != next.aggregationIntervalTicks()
                || previous.maxTrackedChunks() != next.maxTrackedChunks()
                || previous.analysisMaxChunks() != next.analysisMaxChunks()) {
            changed.add("monitoring");
        }
        if (previous.warningMspt() != next.warningMspt()
                || previous.highMspt() != next.highMspt()
                || previous.criticalMspt() != next.criticalMspt()) {
            changed.add("server-pressure");
        }
        if (previous.protectionEnterSeconds() != next.protectionEnterSeconds()
                || previous.throttleMinPressure() != next.throttleMinPressure()
                || previous.emergencyMinPressure() != next.emergencyMinPressure()) {
            changed.add("protection");
        }
        if (previous.historyEnabled() != next.historyEnabled()
                || previous.maxIncidents() != next.maxIncidents()) {
            changed.add("history");
        }
        return changed;
    }

    private static String name(Enum<?> value) {
        return value == null ? "UNKNOWN" : value.name();
    }

    static double round1(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
