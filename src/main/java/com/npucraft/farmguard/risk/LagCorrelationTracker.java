package com.npucraft.farmguard.risk;

import com.npucraft.farmguard.config.FarmGuardSettings;
import com.npucraft.farmguard.model.ChunkKey;
import com.npucraft.farmguard.model.LagCorrelation;
import com.npucraft.farmguard.model.ServerPressure;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds recently observed co-movement evidence so correlation does not
 * collapse the instant MSPT stops rising. Persistence is not a latch:
 * only chunks that already earned POSSIBLE/STRONG may hold, and hold
 * expires. Server CRITICAL alone never creates correlation.
 */
public final class LagCorrelationTracker {

    private final ConcurrentHashMap<ChunkKey, State> states = new ConcurrentHashMap<>();

    public LagCorrelation update(
            ChunkKey key,
            LagCorrelation instantaneous,
            double currentActivity,
            ServerPressure pressure,
            FarmGuardSettings settings,
            long nowMs
    ) {
        if (key == null || settings == null) {
            return LagCorrelation.NONE;
        }
        State state = states.computeIfAbsent(key, ignored -> new State());
        synchronized (state) {
            return updateLocked(state, instantaneous, currentActivity, pressure, settings, nowMs);
        }
    }

    public void clear() {
        states.clear();
    }

    public void forget(ChunkKey key) {
        if (key != null) {
            states.remove(key);
        }
    }

    private LagCorrelation updateLocked(
            State state,
            LagCorrelation instantaneous,
            double currentActivity,
            ServerPressure pressure,
            FarmGuardSettings settings,
            long nowMs
    ) {
        LagCorrelation instant = instantaneous == null ? LagCorrelation.NONE : instantaneous;
        if (instant != LagCorrelation.NONE) {
            state.lastEvidenceAtMs = nowMs;
            if (instant.ordinal() >= state.published.ordinal()) {
                state.published = instant;
            }
            return state.published;
        }
        if (state.published == LagCorrelation.NONE) {
            return LagCorrelation.NONE;
        }

        boolean collapsed = currentActivity < settings.hotspotMinActivityScore();
        boolean alive = currentActivity >= settings.hotspotMinActivityScore();
        boolean highPressure = pressure != null && pressure.atLeast(ServerPressure.HIGH);
        boolean warningPressure = pressure != null && pressure.atLeast(ServerPressure.WARNING);
        long strongHoldMs = Math.max(0, settings.strongHoldSeconds()) * 1000L;
        long possibleHoldMs = Math.max(0, settings.possibleHoldSeconds()) * 1000L;
        boolean withinStrongHold = nowMs - state.lastEvidenceAtMs < strongHoldMs;
        boolean withinPossibleFromDecay = state.lastDecayAtMs > 0 && nowMs - state.lastDecayAtMs < possibleHoldMs;

        if (state.published == LagCorrelation.STRONG) {
            // Hold requires the chunk still be a hotspot, not that it still
            // meets strong-activity-score. That score is for earning STRONG.
            if (!collapsed && highPressure && alive && withinStrongHold) {
                return LagCorrelation.STRONG;
            }
            state.published = LagCorrelation.POSSIBLE;
            state.lastDecayAtMs = nowMs;
            return LagCorrelation.POSSIBLE;
        }

        boolean holdPossible = !collapsed
                && alive
                && warningPressure
                && withinPossibleFromDecay;
        if (holdPossible) {
            return LagCorrelation.POSSIBLE;
        }
        state.published = LagCorrelation.NONE;
        return LagCorrelation.NONE;
    }

    private static final class State {
        private LagCorrelation published = LagCorrelation.NONE;
        private long lastEvidenceAtMs;
        private long lastDecayAtMs;
    }
}
