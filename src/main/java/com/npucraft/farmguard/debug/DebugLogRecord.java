package com.npucraft.farmguard.debug;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable diagnostic event. Built on the aggregation / command path, never on
 * a Minecraft event listener hot path.
 */
public final class DebugLogRecord {

    public static final int SCHEMA = 1;

    private final long tsMillis;
    private final DebugPriority priority;
    private final String type;
    private final Map<String, Object> fields;

    public DebugLogRecord(long tsMillis, DebugPriority priority, String type, Map<String, ?> fields) {
        this.tsMillis = tsMillis;
        this.priority = priority == null ? DebugPriority.NORMAL : priority;
        this.type = type == null ? "unknown" : type;
        this.fields = copy(fields);
    }

    public static DebugLogRecord high(String type, Map<String, ?> fields) {
        return new DebugLogRecord(System.currentTimeMillis(), DebugPriority.HIGH, type, fields);
    }

    public static DebugLogRecord normal(String type, Map<String, ?> fields) {
        return new DebugLogRecord(System.currentTimeMillis(), DebugPriority.NORMAL, type, fields);
    }

    public long tsMillis() {
        return tsMillis;
    }

    public DebugPriority priority() {
        return priority;
    }

    public String type() {
        return type;
    }

    public Map<String, Object> fields() {
        return fields;
    }

    private static Map<String, Object> copy(Map<String, ?> fields) {
        if (fields == null || fields.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>(fields.size() * 2);
        for (Map.Entry<String, ?> entry : fields.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            copy.put(entry.getKey(), entry.getValue());
        }
        return Map.copyOf(copy);
    }
}
