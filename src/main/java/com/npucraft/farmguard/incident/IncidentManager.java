package com.npucraft.farmguard.incident;

import com.npucraft.farmguard.config.FarmGuardSettings;
import com.npucraft.farmguard.model.LagIncident;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public final class IncidentManager {

    private final ArrayDeque<LagIncident> incidents = new ArrayDeque<>();
    private LagIncident current;

    public LagIncident current() {
        return current;
    }

    public List<LagIncident> history() {
        return List.copyOf(incidents);
    }

    public LagIncident onLagStarted(long nowMs, double mspt, double tps, FarmGuardSettings settings) {
        if (current != null && current.open()) {
            current.recordSample(mspt, tps);
            return current;
        }
        current = new LagIncident(nowMs, mspt, tps);
        incidents.addFirst(current);
        trim(settings);
        return current;
    }

    public void onLagSample(double mspt, double tps, List<String> suspects, FarmGuardSettings settings) {
        if (current == null || !current.open()) {
            return;
        }
        current.recordSample(mspt, tps);
        if (suspects != null && !suspects.isEmpty()) {
            current.setSuspects(suspects);
        }
        trim(settings);
    }

    public void addAction(String action) {
        if (current != null && current.open()) {
            current.addAction(action);
        }
    }

    public LagIncident onRecovered(long nowMs, FarmGuardSettings settings) {
        if (current == null || !current.open()) {
            return null;
        }
        current.close(nowMs, true);
        LagIncident closed = current;
        current = null;
        trim(settings);
        return closed;
    }

    public void replaceHistory(List<LagIncident> loaded, FarmGuardSettings settings) {
        incidents.clear();
        if (loaded != null) {
            for (LagIncident incident : loaded) {
                incidents.addLast(incident);
            }
        }
        current = null;
        for (LagIncident incident : incidents) {
            if (incident.open()) {
                current = incident;
                break;
            }
        }
        trim(settings);
    }

    public List<LagIncident> snapshot() {
        return new ArrayList<>(incidents);
    }

    public void trim(FarmGuardSettings settings) {
        int max = Math.max(1, settings.maxIncidents());
        long maxAgeMs = Math.max(1, settings.maxIncidentAgeHours()) * 3_600_000L;
        long now = System.currentTimeMillis();
        while (incidents.size() > max) {
            LagIncident last = incidents.peekLast();
            if (last != null && last == current) {
                break;
            }
            incidents.pollLast();
        }
        incidents.removeIf(incident -> {
            if (incident == current) {
                return false;
            }
            long end = incident.endMs() == null ? incident.startMs() : incident.endMs();
            return now - end > maxAgeMs;
        });
    }
}
