package dev.farmguard.model;

public enum ProtectionLevel {
    NORMAL,
    WARNING,
    THROTTLE,
    EMERGENCY;

    public boolean atLeast(ProtectionLevel other) {
        return ordinal() >= other.ordinal();
    }

    public ProtectionLevel stepDown() {
        return switch (this) {
            case EMERGENCY -> THROTTLE;
            case THROTTLE -> WARNING;
            case WARNING, NORMAL -> NORMAL;
        };
    }

    public boolean restrictsGameplay() {
        return this == THROTTLE || this == EMERGENCY;
    }
}
