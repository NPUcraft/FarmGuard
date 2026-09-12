package com.npucraft.farmguard.model;

import java.util.List;

public final class ServerMetrics {

    private final long sampleTimeMs;
    private final double tps;
    private final double mspt;
    private final double averageMspt;
    private final ServerPressure pressure;
    private final boolean lagIncident;

    public ServerMetrics(
            long sampleTimeMs,
            double tps,
            double mspt,
            double averageMspt,
            ServerPressure pressure,
            boolean lagIncident
    ) {
        this.sampleTimeMs = sampleTimeMs;
        this.tps = tps;
        this.mspt = mspt;
        this.averageMspt = averageMspt;
        this.pressure = pressure;
        this.lagIncident = lagIncident;
    }

    public long sampleTimeMs() {
        return sampleTimeMs;
    }

    public double tps() {
        return tps;
    }

    public double mspt() {
        return mspt;
    }

    public double averageMspt() {
        return averageMspt;
    }

    public ServerPressure pressure() {
        return pressure;
    }

    public boolean lagIncident() {
        return lagIncident;
    }

    public static ServerMetrics idle(long nowMs) {
        return new ServerMetrics(nowMs, 20.0, 0.0, 0.0, ServerPressure.NORMAL, false);
    }
}
