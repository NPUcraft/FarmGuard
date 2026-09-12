package com.npucraft.farmguard.model;

import java.util.EnumSet;
import java.util.Set;

public final class ProtectionState {

    private final ChunkKey chunk;
    private final ProtectionLevel applied;
    private final ProtectionLevel recommended;
    private final long sinceMs;
    private final Set<RiskReason> reasons;
    private final boolean whitelisted;
    private final boolean monitorOnly;

    public ProtectionState(
            ChunkKey chunk,
            ProtectionLevel applied,
            ProtectionLevel recommended,
            long sinceMs,
            Set<RiskReason> reasons,
            boolean whitelisted,
            boolean monitorOnly
    ) {
        this.chunk = chunk;
        this.applied = applied;
        this.recommended = recommended;
        this.sinceMs = sinceMs;
        this.reasons = reasons == null || reasons.isEmpty()
                ? EnumSet.noneOf(RiskReason.class)
                : EnumSet.copyOf(reasons);
        this.whitelisted = whitelisted;
        this.monitorOnly = monitorOnly;
    }

    public ChunkKey chunk() {
        return chunk;
    }

    public ProtectionLevel applied() {
        return applied;
    }

    public ProtectionLevel recommended() {
        return recommended;
    }

    public long sinceMs() {
        return sinceMs;
    }

    public Set<RiskReason> reasons() {
        return reasons;
    }

    public boolean whitelisted() {
        return whitelisted;
    }

    public boolean monitorOnly() {
        return monitorOnly;
    }

    public boolean restricting() {
        return applied.restrictsGameplay();
    }
}
