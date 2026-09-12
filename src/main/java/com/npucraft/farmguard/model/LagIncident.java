package dev.farmguard.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class LagIncident {

    private final String id;
    private final long startMs;
    private Long endMs;
    private double peakMspt;
    private double lowestTps;
    private final List<String> topSuspects;
    private final List<String> protectionActions;
    private boolean recovered;

    public LagIncident(long startMs, double mspt, double tps) {
        this.id = UUID.randomUUID().toString().substring(0, 8);
        this.startMs = startMs;
        this.peakMspt = mspt;
        this.lowestTps = tps;
        this.topSuspects = new ArrayList<>();
        this.protectionActions = new ArrayList<>();
        this.recovered = false;
    }

    public LagIncident(
            String id,
            long startMs,
            Long endMs,
            double peakMspt,
            double lowestTps,
            List<String> topSuspects,
            List<String> protectionActions,
            boolean recovered
    ) {
        this.id = id;
        this.startMs = startMs;
        this.endMs = endMs;
        this.peakMspt = peakMspt;
        this.lowestTps = lowestTps;
        this.topSuspects = new ArrayList<>(topSuspects == null ? List.of() : topSuspects);
        this.protectionActions = new ArrayList<>(protectionActions == null ? List.of() : protectionActions);
        this.recovered = recovered;
    }

    public String id() {
        return id;
    }

    public long startMs() {
        return startMs;
    }

    public Long endMs() {
        return endMs;
    }

    public double peakMspt() {
        return peakMspt;
    }

    public double lowestTps() {
        return lowestTps;
    }

    public List<String> topSuspects() {
        return List.copyOf(topSuspects);
    }

    public List<String> protectionActions() {
        return List.copyOf(protectionActions);
    }

    public boolean recovered() {
        return recovered;
    }

    public boolean open() {
        return endMs == null;
    }

    public void recordSample(double mspt, double tps) {
        if (mspt > peakMspt) {
            peakMspt = mspt;
        }
        if (tps < lowestTps) {
            lowestTps = tps;
        }
    }

    public void setSuspects(List<String> suspects) {
        topSuspects.clear();
        if (suspects != null) {
            int limit = Math.min(5, suspects.size());
            for (int i = 0; i < limit; i++) {
                topSuspects.add(suspects.get(i));
            }
        }
    }

    public void addAction(String action) {
        if (action == null || action.isBlank()) {
            return;
        }
        if (protectionActions.size() >= 40) {
            protectionActions.remove(0);
        }
        if (!protectionActions.contains(action)) {
            protectionActions.add(action);
        }
    }

    public void close(long nowMs, boolean recovered) {
        this.endMs = nowMs;
        this.recovered = recovered;
    }
}
