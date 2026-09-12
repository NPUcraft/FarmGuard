package dev.farmguard.model;

public enum RiskLevel {
    NONE,
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;

    public boolean atLeast(RiskLevel other) {
        return ordinal() >= other.ordinal();
    }
}
