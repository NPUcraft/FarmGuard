package com.npucraft.farmguard;

import com.npucraft.farmguard.cluster.ClusterManager;
import com.npucraft.farmguard.collector.ActivityListener;
import com.npucraft.farmguard.collector.ActivitySink;
import com.npucraft.farmguard.collector.WorldUnloadListener;
import com.npucraft.farmguard.command.FarmGuardCommand;
import com.npucraft.farmguard.config.ConfigLoader;
import com.npucraft.farmguard.config.FarmGuardSettings;
import com.npucraft.farmguard.config.Messages;
import com.npucraft.farmguard.config.StateStore;
import com.npucraft.farmguard.config.Whitelist;
import com.npucraft.farmguard.hotspot.AnalysisBudget;
import com.npucraft.farmguard.hotspot.HotspotService;
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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Villager;
import org.bukkit.scheduler.BukkitTask;

public final class FarmGuardRuntime {

    private final FarmGuardPlugin plugin;
    private final AtomicReference<FarmGuardSettings> settings = new AtomicReference<>(FarmGuardSettings.defaults());
    private final AtomicReference<OperatingMode> mode = new AtomicReference<>(OperatingMode.MONITOR);
    private final Messages messages = new Messages();
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
        this.notifications = new NotificationService(plugin.getLogger(), messages);
        this.stateStore = new StateStore(new File(plugin.getDataFolder(), "state.yml"), plugin.getLogger());
        this.historyStore = new HistoryStore(new File(plugin.getDataFolder(), "incidents.yml"), plugin.getLogger());
    }

    public void enable() {
        running.set(true);
        reloadInternal();
        ActivitySink sink = new ActivitySink(metrics, this::settings);
        plugin.getServer().getPluginManager().registerEvents(new ActivityListener(sink), plugin);
        plugin.getServer().getPluginManager().registerEvents(new ProtectionListener(plugin, protection), plugin);
        plugin.getServer().getPluginManager().registerEvents(new WorldUnloadListener(metrics, protection), plugin);
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
        protection.setRestrictionsEnabled(false);
        protection.clearAll();
        correlationTracker.clear();
        saveHistory(false);
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

    public Messages messages() {
        return messages;
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

    public boolean setMode(OperatingMode next) {
        if (next == null) {
            return false;
        }
        mode.set(next);
        protection.setRestrictionsEnabled(next.allowsProtection());
        persistState();
        return true;
    }

    public int reload() {
        return reloadInternal();
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

    private int reloadInternal() {
        plugin.reloadConfig();
        plugin.saveDefaultConfig();
        File messagesFile = new File(plugin.getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        messages.load(messagesFile, plugin.getLogger(), bundledMessages());
        ConfigLoader.Result result = new ConfigLoader(plugin.getLogger()).load(plugin.getConfig());
        settings.set(result.settings());
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
        if (!result.errors().isEmpty()) {
            plugin.getLogger().warning("[FarmGuard] Reload used defaults for " + result.errors().size() + " invalid config value(s).");
        }
        return result.errors().size();
    }

    public void persistState() {
        stateStore.save(mode(), whitelist);
    }

    private YamlConfiguration bundledMessages() {
        YamlConfiguration bundled = new YamlConfiguration();
        try (InputStream in = plugin.getResource("messages.yml")) {
            if (in != null) {
                bundled = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (java.io.IOException exception) {
            plugin.getLogger().warning("[FarmGuard] Could not read bundled messages.yml: " + exception.getMessage());
        }
        return bundled;
    }

    private void tick() {
        try {
            tickUnsafe();
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("[FarmGuard] Aggregation tick failed: " + exception.getMessage());
            if (settings().debug()) {
                exception.printStackTrace();
            }
        }
    }

    private void tickUnsafe() {
        FarmGuardSettings cfg = settings();
        long now = System.currentTimeMillis();
        ServerMetrics server = performance.sample(plugin.getServer(), now, cfg);
        notifications.onServerMetrics(server, cfg, now);

        boolean lagNow = server.lagIncident();
        if (lagNow && !lagActive) {
            incidents.onLagStarted(now, server.mspt(), server.tps(), cfg);
            lagActive = true;
        } else if (!lagNow && lagActive && server.pressure() == com.npucraft.farmguard.model.ServerPressure.NORMAL) {
            incidents.onRecovered(now, cfg);
            lagActive = false;
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
