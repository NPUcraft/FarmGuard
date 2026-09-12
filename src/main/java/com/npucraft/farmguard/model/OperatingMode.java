package com.npucraft.farmguard.model;

public enum OperatingMode {
    MONITOR,
    PROTECT;

    public static OperatingMode parse(String raw, OperatingMode fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return OperatingMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    public boolean allowsProtection() {
        return this == PROTECT;
    }
}
