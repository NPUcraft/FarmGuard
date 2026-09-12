package dev.farmguard.model;

public enum ServerPressure {
    NORMAL,
    WARNING,
    HIGH,
    CRITICAL;

    public boolean atLeast(ServerPressure other) {
        return ordinal() >= other.ordinal();
    }
}
