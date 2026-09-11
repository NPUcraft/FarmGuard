package dev.farmguard.monitor;

import dev.farmguard.config.FarmGuardSettings;
import dev.farmguard.model.ServerPressure;

/**
 * Server pressure uses different enter/exit thresholds plus minimum duration
 * and cooldown so a brief TPS dip cannot flap NORMAL <-> CRITICAL.
 */
public final class PressureTracker {

    private ServerPressure current = ServerPressure.NORMAL;
    private ServerPressure pending = ServerPressure.NORMAL;
    private long pendingSinceMs;
    private long lastChangeMs;
    private boolean lagIncident;

    public ServerPressure current() {
        return current;
    }

    public boolean lagIncident() {
        return lagIncident;
    }

    public ServerPressure tick(long nowMs, double mspt, double tps, FarmGuardSettings settings) {
        ServerPressure target = targetWhileCurrent(mspt, tps, settings);
        long enterMs = settings.pressureEnterSeconds() * 1000L;
        long exitMs = settings.pressureExitSeconds() * 1000L;
        long cooldownMs = settings.pressureCooldownSeconds() * 1000L;

        if (target == current) {
            pending = current;
            pendingSinceMs = nowMs;
            lagIncident = current.atLeast(ServerPressure.HIGH);
            return current;
        }

        if (pending != target) {
            pending = target;
            pendingSinceMs = nowMs;
            lagIncident = current.atLeast(ServerPressure.HIGH);
            return current;
        }

        boolean upgrading = target.ordinal() > current.ordinal();
        long required = upgrading ? enterMs : exitMs;
        if (nowMs - pendingSinceMs < required) {
            lagIncident = current.atLeast(ServerPressure.HIGH);
            return current;
        }
        if (nowMs - lastChangeMs < cooldownMs) {
            lagIncident = current.atLeast(ServerPressure.HIGH);
            return current;
        }
        if (!upgrading && target.ordinal() < current.ordinal() - 1) {
            target = current.ordinal() == ServerPressure.CRITICAL.ordinal()
                    ? ServerPressure.HIGH
                    : ServerPressure.WARNING;
            if (current == ServerPressure.WARNING) {
                target = ServerPressure.NORMAL;
            }
        }
        current = target;
        lastChangeMs = nowMs;
        pending = current;
        pendingSinceMs = nowMs;
        lagIncident = current.atLeast(ServerPressure.HIGH);
        return current;
    }

    public void reset() {
        current = ServerPressure.NORMAL;
        pending = ServerPressure.NORMAL;
        pendingSinceMs = 0L;
        lastChangeMs = 0L;
        lagIncident = false;
    }

    private ServerPressure targetWhileCurrent(double mspt, double tps, FarmGuardSettings settings) {
        ServerPressure enter = enterLevel(mspt, tps, settings);
        if (enter.ordinal() > current.ordinal()) {
            return enter;
        }
        ServerPressure stay = stayLevel(mspt, tps, settings);
        if (stay.ordinal() < current.ordinal()) {
            return stay;
        }
        return current;
    }

    private static ServerPressure enterLevel(double mspt, double tps, FarmGuardSettings settings) {
        if (mspt >= settings.criticalMspt() || tps <= settings.criticalTps()) {
            return ServerPressure.CRITICAL;
        }
        if (mspt >= settings.highMspt() || tps <= settings.highTps()) {
            return ServerPressure.HIGH;
        }
        if (mspt >= settings.warningMspt() || tps <= settings.warningTps()) {
            return ServerPressure.WARNING;
        }
        return ServerPressure.NORMAL;
    }

    private static ServerPressure stayLevel(double mspt, double tps, FarmGuardSettings settings) {
        if (mspt > settings.criticalExitMspt() || tps < settings.criticalExitTps()) {
            return ServerPressure.CRITICAL;
        }
        if (mspt > settings.highExitMspt() || tps < settings.highExitTps()) {
            return ServerPressure.HIGH;
        }
        if (mspt > settings.warningExitMspt() || tps < settings.warningExitTps()) {
            return ServerPressure.WARNING;
        }
        return ServerPressure.NORMAL;
    }
}
