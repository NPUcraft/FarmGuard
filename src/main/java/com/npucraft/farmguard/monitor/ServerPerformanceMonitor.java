package com.npucraft.farmguard.monitor;

import com.npucraft.farmguard.config.FarmGuardSettings;
import com.npucraft.farmguard.model.ServerMetrics;
import com.npucraft.farmguard.model.ServerPressure;
import com.npucraft.farmguard.util.Numbers;
import org.bukkit.Server;

public final class ServerPerformanceMonitor {

    private final PressureTracker tracker = new PressureTracker();
    private final double[] msptWindow;
    private int msptCount;
    private int msptIndex;
    private ServerMetrics latest = ServerMetrics.idle(System.currentTimeMillis());

    public ServerPerformanceMonitor() {
        this.msptWindow = new double[64];
    }

    public ServerMetrics sample(Server server, long nowMs, FarmGuardSettings settings) {
        double tps = 20.0;
        double mspt = 0.0;
        try {
            double[] tpsSamples = server.getTPS();
            if (tpsSamples != null && tpsSamples.length > 0) {
                tps = Numbers.clamp(tpsSamples[0], 0.0, 20.0);
            }
            mspt = Math.max(0.0, server.getAverageTickTime());
            long[] tickTimes = server.getTickTimes();
            if (tickTimes != null && tickTimes.length > 0) {
                int n = Math.min(20, tickTimes.length);
                long sum = 0L;
                for (int i = tickTimes.length - n; i < tickTimes.length; i++) {
                    sum += tickTimes[i];
                }
                double recentMs = (sum / (double) n) / 1_000_000.0;
                mspt = Math.max(mspt, recentMs);
            }
        } catch (RuntimeException ignored) {
            // Paper performance APIs should exist; keep last known values if a call fails.
            tps = latest.tps();
            mspt = latest.mspt();
        }

        int window = Math.min(msptWindow.length, Math.max(3, settings.msptAverageSamples()));
        msptWindow[msptIndex] = mspt;
        msptIndex = (msptIndex + 1) % window;
        if (msptCount < window) {
            msptCount++;
        }
        double average = 0.0;
        for (int i = 0; i < msptCount; i++) {
            average += msptWindow[i];
        }
        average /= Math.max(1, msptCount);

        ServerPressure pressure = tracker.tick(nowMs, mspt, tps, settings);
        latest = new ServerMetrics(nowMs, tps, mspt, average, pressure, tracker.lagIncident());
        return latest;
    }

    public ServerMetrics latest() {
        return latest;
    }

    public PressureTracker tracker() {
        return tracker;
    }
}
