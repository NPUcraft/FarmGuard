package com.npucraft.farmguard;

import com.npucraft.farmguard.cluster.ClusterManager;
import com.npucraft.farmguard.collector.ActivityListener;
import com.npucraft.farmguard.collector.ActivitySink;
import com.npucraft.farmguard.collector.WorldUnloadListener;
import com.npucraft.farmguard.command.FarmGuardCommand;
import com.npucraft.farmguard.config.ConfigLoader;
import com.npucraft.farmguard.config.FarmGuardSettings;
import com.npucraft.farmguard.config.StateStore;
import com.npucraft.farmguard.config.Whitelist;
import com.npucraft.farmguard.debug.DebugActionCounters;
import com.npucraft.farmguard.debug.DebugChunkListener;
import com.npucraft.farmguard.debug.DebugDiagnostics;
import com.npucraft.farmguard.debug.DebugLogService;
import com.npucraft.farmguard.debug.DebugWorldCounters;
import com.npucraft.farmguard.hotspot.AnalysisBudget;
import com.npucraft.farmguard.hotspot.HotspotService;
import com.npucraft.farmguard.i18n.AdminUi;
import com.npucraft.farmguard.i18n.ConfigLanguagePatch;
import com.npucraft.farmguard.i18n.LanguageManager;
import com.npucraft.farmguard.i18n.LocaleIds;
import com.npucraft.farmguard.i18n.MessageService;
import com.npucraft.farmguard.incident.HistoryStore;
import com.npucraft.farmguard.incident.IncidentManager;
import com.npucraft.farmguard.metrics.ChunkMetricStore;
import com.npucraft.farmguard.model.ChunkActivitySnapshot;
import com.npucraft.farmguard.model.ChunkKey;
import com.npucraft.farmguard.model.LagCorrelation;
import com.npucraft.farmguard.model.LagIncident;
import com.npucraft.farmguard.model.OperatingMode;
import com.npucraft.farmguard.model.RiskAssessment;
import com.npucraft.farmguard.model.ServerMetrics;
import com.npucraft.farmguard.monitor.ServerPerformanceMonitor;
import com.npucraft.farmguard.notification.NotificationService;
import com.npucraft.farmguard.protection.ProtectionListener;
import com.npucraft.farmguard.protection.ProtectionManager;
import com.npucraft.farmguard.risk.LagCorrelationAnalyzer;
import com.npucraft.farmguard.risk.LagCorrelationTracker;
import com.npucraft.farmguard.risk.RiskEngine;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.scheduler.BukkitTask;

public final class FarmGuardRuntime {

    private final FarmGuardPlugin plugin;
    private final AtomicReference<FarmGuardSettings> settings = new AtomicReference<>(FarmGuardSettings.defaults());
    private final AtomicReference<OperatingMode> mode = new AtomicReference<>(OperatingMode.MONITOR);
    private final LanguageManager language;
    private final MessageService messages;
    private final AdminUi adminUi;
    private final ChunkMetricStore metrics = new ChunkMetricStore();
    private final ServerPerformanceMonitor performance = new ServerPerformanceMonitor();
    private final RiskEngine riskEngine = new RiskEngine();
    private final LagCorrelationAnalyzer correlationAnalyzer = new LagCorrelationAnalyzer();
    private final LagCorrelationTracker correlationTracker = new LagCorrelationTracker();
    private final HotspotService hotspots = new HotspotService();
    private final ClusterManager clusters = new ClusterManager();
    private final ProtectionManager protection = new ProtectionManager();
    private final IncidentManager incidents = new IncidentManager();
    private final Whitelist whitelist = new Whitelist();
    private final NotificationService notifications;
    private final StateStore stateStore;
    private final HistoryStore historyStore;
    private final DebugLogService debugLog;
    private final DebugActionCounters debugActions;
    private final DebugWorldCounters debugWorld;
    private final DebugDiagnostics diagnostics;
    private final AtomicBoolean historyIo = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private BukkitTask aggregationTask;
    private long lastCensusMs;
    private long lastClusterMs;
    private long lastHistoryFlushMs;
    private boolean lagActive;
    private int censusCursor;

    public FarmGuardRuntime(FarmGuardPlugin plugin) {
        this.plugin = plugin;
        this.language = new LanguageManager(plugin.getLogger(), plugin.getClass().getClassLoader());
        this.messages = new MessageService(language);
        this.adminUi = new AdminUi(language, messages);
        this.notifications = new NotificationService(plugin.getLogger(), messages);
        this.stateStore = new StateStore(new File(plugin.getDataFolder(), "state.yml"), plugin.getLogger());
        this.historyStore = new HistoryStore(new File(plugin.getDataFolder(), "incidents.yml"), plugin.getLogger());
        this.debugLog = new DebugLogService(plugin.getDataFolder(), plugin.getLogger());
        this.debugActions = new DebugActionCounters();
        this.debugWorld = new DebugWorldCounters();
        this.diagnostics = new DebugDiagnostics(debugLog, debugActions, debugWorld);
        this.diagnostics.setPluginVersion(plugin.getPluginMeta().getVersion());
    }

    public void enable() {
        running.set(true);
        reloadInternal(true);
        ActivitySink sink = new ActivitySink(metrics, this::settings);
        plugin.getServer().getPluginManager().registerEvents(new ActivityListener(sink), plugin);
        plugin.getServer().getPluginManager().registerEvents(new ProtectionListener(plugin, protection), plugin);
        plugin.getServer().getPluginManager().registerEvents(new WorldUnloadListener(metrics, protection), plugin);
        plugin.getServer().getPluginManager().registerEvents(new DebugChunkListener(debugWorld), plugin);
        FarmGuardCommand command = new FarmGuardCommand(this);
        if (plugin.getCommand("farmguard") != null) {
            plugin.getCommand("farmguard").setExecutor(command);
            plugin.getCommand("farmguard").setTabCompleter(command);
        }
        int interval = Math.max(20, settings().aggregationIntervalTicks());
        aggregationTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, interval, interval);
        loadHistoryAsync();
        plugin.getLogger().info("Mode: " + mode().name());
        plugin.getLogger().info("Monitoring enabled.");
        if (mode().allowsProtection()) {
            plugin.getLogger().info("Protection enabled.");
        } else {
            plugin.getLogger().info("Protection disabled because mode is MONITOR.");
        }
        plugin.getLogger().info("FarmGuard enabled successfully.");
    }

    public void disable() {
        running.set(false);
        if (aggregationTask != null) {
            aggregationTask.cancel();
            aggregationTask = null;
        }
        int trackedChunks = metrics.size();
        int openIncidents = incidents.current() == null ? 0 : 1;
        int activeProtections = protection.restrictingCount();
        OperatingMode shutdownMode = mode();
        protection.setRestrictionsEnabled(false);
        protection.clearAll();
        correlationTracker.clear();
        saveHistory(false);
        diagnostics.flushPendingSummaries();
        debugActions.setEnabled(false);
        debugWorld.setEnabled(false);
        if (debugLog.enabled() || debugLog.writerAlive()) {
            diagnostics.onWriterStopping(true, shutdownMode, trackedChunks, openIncidents, activeProtections);
            debugLog.stop();
        }
        metrics.clear();
        clusters.clear();
        notifications.clear();
        plugin.getLogger().info("FarmGuard disabled.");
    }

    public FarmGuardSettings settings() {
        return settings.get();
    }

    public OperatingMode mode() {
        return mode.get();
    }

    public MessageService messages() {
        return messages;
    }

    public LanguageManager language() {
        return language;
    }

    public AdminUi adminUi() {
        return adminUi;
    }

    public ServerPerformanceMonitor performance() {
        return performance;
    }

    public HotspotService hotspots() {
        return hotspots;
    }

    public ClusterManager clusters() {
        return clusters;
    }

    public ProtectionManager protection() {
        return protection;
    }

    public ChunkMetricStore metrics() {
        return metrics;
    }

    public Whitelist whitelist() {
        return whitelist;
    }

    public IncidentManager incidents() {
        return incidents;
    }

    public DebugLogService debugLog() {
        return debugLog;
    }

    public DebugActionCounters debugActions() {
        return debugActions;
    }

    public void recordWhitelist(boolean added, ChunkKey key) {
        String clusterId = null;
        if (key != null) {
            var cluster = clusters.find(key);
            if (cluster != null) {
                clusterId = cluster.id();
            }
        }
        diagnostics.onWhitelist(added, key, clusterId);
    }

    public boolean setMode(OperatingMode next) {
        if (next == null) {
            return false;
        }
        OperatingMode previous = mode.get();
        mode.set(next);
        protection.setRestrictionsEnabled(next.allowsProtection());
        persistState();
        diagnostics.onModeChange(previous, next, "COMMAND");
        return true;
    }

    public boolean setLanguage(String locale) {
        String normalized = LocaleIds.tryNormalize(locale);
        if (normalized == null || !language.supports(normalized)) {
            return false;
        }
        language.select(normalized);
        Path configFile = new File(plugin.getDataFolder(), "config.yml").toPath();
        ConfigLanguagePatch.write(configFile, normalized, plugin.getLogger());
        plugin.reloadConfig();
        return true;
    }

    public int reload() {
        return reloadInternal(false);
    }

    public RiskAssessment inspect(ChunkKey key) {
        RiskAssessment ranked = hotspots.find(key);
        if (ranked != null) {
            return ranked;
        }
        var record = metrics.get(key);
        if (record == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        ChunkActivitySnapshot snapshot = record.snapshot(now, settings());
        LagCorrelation correlation = correlationOf(key, snapshot.previousActivityScore(), performance.latest(), now);
        return riskEngine.assess(snapshot, performance.latest(), correlation, settings());
    }

    private int reloadInternal(boolean startup) {
        FarmGuardSettings previous = settings();
        plugin.reloadConfig();
        plugin.saveDefaultConfig();
        ConfigLoader.Result result = new ConfigLoader(plugin.getLogger()).load(plugin.getConfig());
        settings.set(result.settings());
        language.load(plugin.getDataFolder(), result.settings().language());
        StateStore.LoadedState state = stateStore.load(result.settings().mode());
        mode.set(state.mode());
        List<ChunkKey> chunks = new ArrayList<>(state.chunks());
        List<String> configChunks = new ConfigLoader(plugin.getLogger()).configChunks(plugin.getConfig());
        for (String raw : configChunks) {
            ChunkKey key = Whitelist.parseChunk(raw);
            if (key != null) {
                chunks.add(key);
            }
        }
        List<String> clustersListed = new ArrayList<>(state.clusters());
        clustersListed.addAll(new ConfigLoader(plugin.getLogger()).configClusters(plugin.getConfig()));
        whitelist.replace(chunks, clustersListed);
        protection.setRestrictionsEnabled(mode().allowsProtection());
        FarmGuardSettings next = result.settings();
        boolean wantDebug = next.debugLogEnabled();
        boolean debugRunning = debugLog.writerAlive();
        if (wantDebug && !debugRunning) {
            debugLog.start(next);
            if (debugLog.isActive()) {
                debugActions.setEnabled(true);
                debugWorld.setEnabled(true);
                diagnostics.onWriterStarted(
                        startup,
                        Bukkit.getVersion(),
                        System.getProperty("java.version", "unknown"),
                        mode(),
                        result.errors().size()
                );
            } else {
                debugActions.setEnabled(false);
                debugWorld.setEnabled(false);
                plugin.getLogger().warning("[FarmGuard] Debug log did not start; FarmGuard continues without diagnostic logging.");
            }
        } else if (wantDebug && debugRunning) {
            debugLog.applyLimits(next);
            debugActions.setEnabled(true);
            debugWorld.setEnabled(true);
        }
        if (!startup && (debugLog.enabled() || debugRunning)) {
            diagnostics.onConfigReload(result.errors().isEmpty(), result.errors().size(), previous, next, mode());
        }
        if (!wantDebug && debugRunning) {
            diagnostics.flushPendingSummaries();
            debugActions.setEnabled(false);
            debugWorld.setEnabled(false);
            diagnostics.onWriterStopping(
                    false,
                    mode(),
                    metrics.size(),
                    incidents.current() == null ? 0 : 1,
                    protection.restrictingCount()
            );
            debugLog.stop();
        } else if (wantDebug) {
            debugActions.setEnabled(debugLog.isActive());
            debugWorld.setEnabled(debugLog.isActive());
        } else {
            debugActions.setEnabled(false);
            debugWorld.setEnabled(false);
        }
        if (!result.errors().isEmpty()) {
            plugin.getLogger().warning("[FarmGuard] Reload used defaults for " + result.errors().size() + " invalid config value(s).");
        }
        return result.errors().size();
    }

    public void persistState() {
        stateStore.save(mode(), whitelist);
    }

    private void tick() {
        try {
            tickUnsafe();
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("[FarmGuard] Aggregation tick failed: " + exception.getMessage());
            diagnostics.onInternalError("AggregationTick", exception);
            if (settings().debug()) {
                exception.printStackTrace();
            }
        }
    }

    private void tickUnsafe() {
        FarmGuardSettings cfg = settings();
        long now = System.currentTimeMillis();
        debugActions.setEnabled(debugLog.isActive());
        debugWorld.setEnabled(debugLog.isActive());
        boolean debugOn = debugLog.isActive();
        long analysisStarted = debugOn ? System.nanoTime() : 0L;
        ServerMetrics server = performance.sample(plugin.getServer(), now, cfg);
        notifications.onServerMetrics(server, cfg, now);

        boolean lagNow = server.lagIncident();
        boolean lagStarted = false;
        boolean lagEnded = false;
        LagIncident startedIncident = null;
        LagIncident endedIncident = null;
        if (lagNow && !lagActive) {
            startedIncident = incidents.onLagStarted(now, server.mspt(), server.tps(), cfg);
            lagActive = true;
            lagStarted = true;
        } else if (!lagNow && lagActive && server.pressure() == com.npucraft.farmguard.model.ServerPressure.NORMAL) {
            endedIncident = incidents.onRecovered(now, cfg);
            lagActive = false;
            lagEnded = true;
        } else if (lagNow) {
            incidents.onLagSample(server.mspt(), server.tps(), suspectNames(), cfg);
        }

        metrics.cleanup(now, cfg);
        Set<ChunkKey> keep = protection.restrictingKeys();
        List<ChunkActivitySnapshot> snapshots = AnalysisBudget.select(
                metrics.snapshotRecent(now, cfg, keep),
                keep,
                cfg.analysisMaxChunks()
        );
        List<RiskAssessment> assessments = new ArrayList<>(snapshots.size());
        for (ChunkActivitySnapshot snapshot : snapshots) {
            LagCorrelation correlation = correlationOf(snapshot.key(), snapshot.previousActivityScore(), server, now);
            RiskAssessment assessment = riskEngine.assess(snapshot, server, correlation, cfg);
            metrics.rememberSample(snapshot.key(), assessment.activityScore(), server.mspt());
            assessments.add(assessment);
        }
        notifications.onRisks(assessments, cfg, now);
        hotspots.update(assessments, cfg);
        List<ProtectionManager.Change> changes = protection.tick(
                assessments,
                server,
                mode(),
                cfg,
                key -> isWhitelisted(key),
                now
        );
        for (ProtectionManager.Change change : changes) {
            notifications.onProtection(change, cfg);
            if (change.type() != ProtectionManager.ChangeType.NONE) {
                incidents.addAction(change.type().name() + " " + change.state().chunk().display() + " " + change.state().applied().name());
            }
        }
        if (now - lastClusterMs >= cfg.clusterRefreshIntervalSeconds() * 1000L) {
            clusters.refresh(assessments, protection.appliedByChunk(), cfg);
            lastClusterMs = now;
        }
        if (now - lastCensusMs >= cfg.hotspotCensusIntervalSeconds() * 1000L) {
            sampleHotspotCensus(cfg, now);
            lastCensusMs = now;
        }
        if (cfg.historyEnabled() && now - lastHistoryFlushMs >= cfg.historyFlushSeconds() * 1000L) {
            saveHistory(true);
            lastHistoryFlushMs = now;
        }
        if (debugOn) {
            long analysisMs = (System.nanoTime() - analysisStarted) / 1_000_000L;
            int onlinePlayers = 0;
            Map<String, Integer> playersByWorld = Map.of();
            var players = plugin.getServer().getOnlinePlayers();
            onlinePlayers = players.size();
            LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
            for (Player player : players) {
                World world = player.getWorld();
                if (world != null) {
                    counts.merge(world.getName(), 1, Integer::sum);
                }
            }
            playersByWorld = counts;
            diagnostics.onTick(
                    cfg,
                    server,
                    assessments,
                    hotspots.top(cfg.debugHotspotTopN()),
                    protection.appliedByChunk(),
                    changes,
                    clusters.current(),
                    metrics.size(),
                    hotspots.activeChunks(),
                    clusters.current().size(),
                    protection.restrictingCount(),
                    incidents.current(),
                    lagStarted,
                    startedIncident,
                    lagEnded,
                    endedIncident,
                    analysisMs,
                    mode(),
                    onlinePlayers,
                    playersByWorld
            );
        }
    }

    private boolean isWhitelisted(ChunkKey key) {
        return settings().ignoresWorld(key.worldName())
                || whitelist.exemptFromProtection(key, clusters.find(key));
    }

    private LagCorrelation correlationOf(ChunkKey key, double currentActivity, ServerMetrics server, long nowMs) {
        FarmGuardSettings cfg = settings();
        double[] activity = new double[cfg.correlationSamples()];
        double[] mspt = new double[cfg.correlationSamples()];
        int n = metrics.copyHistory(key, activity, mspt);
        LagCorrelation instant = LagCorrelation.NONE;
        if (n > 0) {
            instant = correlationAnalyzer.analyze(activity, mspt, n, cfg);
        }
        double liveActivity = currentActivity;
        if (liveActivity <= 0 && n > 0) {
            liveActivity = activity[n - 1];
        }
        ServerMetrics metricsNow = server == null ? ServerMetrics.idle(nowMs) : server;
        return correlationTracker.update(key, instant, liveActivity, metricsNow.pressure(), cfg, nowMs);
    }

    private List<String> suspectNames() {
        List<String> names = new ArrayList<>();
        for (RiskAssessment assessment : hotspots.top(5)) {
            if (assessment.correlation() != LagCorrelation.NONE || assessment.level().atLeast(com.npucraft.farmguard.model.RiskLevel.HIGH)) {
                names.add(assessment.chunk().display() + " [" + assessment.reasonsSummary() + "]");
            }
        }
        return names;
    }

    private void sampleHotspotCensus(FarmGuardSettings cfg, long now) {
        if (!cfg.censusEnabled()) {
            return;
        }
        var ranked = hotspots.top(cfg.hotspotCensusLimit());
        if (ranked.isEmpty()) {
            return;
        }
        int budget = Math.min(cfg.censusPerCycle(), ranked.size());
        int scanned = 0;
        int attempts = 0;
        int size = ranked.size();
        while (scanned < budget && attempts < size) {
            int index = Math.floorMod(censusCursor + attempts, size);
            attempts++;
            ChunkKey key = ranked.get(index).chunk();
            World world = Bukkit.getWorld(key.worldId());
            if (world == null) {
                world = Bukkit.getWorld(key.worldName());
            }
            if (world == null || !world.isChunkLoaded(key.x(), key.z())) {
                continue;
            }
            Chunk chunk = world.getChunkAt(key.x(), key.z());
            int villagers = 0;
            int minecarts = 0;
            int items = 0;
            int living = 0;
            int others = 0;
            for (Entity entity : chunk.getEntities()) {
                if (entity instanceof Item) {
                    items++;
                } else if (entity instanceof Villager) {
                    villagers++;
                } else if (entity instanceof Minecart) {
                    minecarts++;
                } else if (entity instanceof LivingEntity) {
                    living++;
                } else {
                    others++;
                }
            }
            metrics.replaceCensus(key, villagers, minecarts, items, living, others, now, cfg);
            scanned++;
        }
        censusCursor = Math.floorMod(censusCursor + Math.max(1, attempts), Math.max(1, size));
    }

    private void loadHistoryAsync() {
        if (!settings().historyEnabled()) {
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            if (!running.get()) {
                return;
            }
            List<LagIncident> loaded = historyStore.load();
            if (!running.get()) {
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!running.get()) {
                    return;
                }
                incidents.replaceHistory(loaded, settings());
            });
        });
    }

    private void saveHistory(boolean async) {
        if (!settings().historyEnabled()) {
            return;
        }
        List<LagIncident> snapshot = incidents.snapshot();
        if (async) {
            if (!running.get() || !historyIo.compareAndSet(false, true)) {
                return;
            }
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    if (running.get()) {
                        historyStore.save(snapshot);
                    }
                } finally {
                    historyIo.set(false);
                }
            });
        } else {
            historyStore.save(snapshot);
        }
    }
}
