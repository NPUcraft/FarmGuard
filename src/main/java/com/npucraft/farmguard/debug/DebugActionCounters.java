package com.npucraft.farmguard.debug;

import com.npucraft.farmguard.model.ChunkKey;
import com.npucraft.farmguard.model.ThrottleType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Listener-safe allowed/suppressed counters. Hot path is a volatile flag plus
 * LongAdder increment. JSON and file IO happen later on the aggregation thread.
 */
public final class DebugActionCounters {

    static final int MAX_TRACKED_CHUNKS = 512;

    private final ConcurrentHashMap<ChunkKey, Row> rows = new ConcurrentHashMap<>();
    private volatile boolean enabled;
    private final LongAdder skippedChunks = new LongAdder();

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            rows.clear();
            skippedChunks.reset();
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public void observe(ChunkKey key, ThrottleType type, boolean suppressed) {
        if (!enabled || key == null || type == null) {
            return;
        }
        Row row = rows.get(key);
        if (row == null) {
            if (rows.size() >= MAX_TRACKED_CHUNKS) {
                skippedChunks.increment();
                return;
            }
            Row created = new Row();
            Row existing = rows.putIfAbsent(key, created);
            row = existing == null ? created : existing;
        }
        row.add(type, suppressed);
    }

    public List<Window> drain(int limit) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Window> windows = new ArrayList<>();
        for (Map.Entry<ChunkKey, Row> entry : rows.entrySet()) {
            windows.add(entry.getValue().snapshot(entry.getKey()));
        }
        rows.clear();
        windows.sort(Comparator.comparingLong(Window::total).reversed());
        int cap = Math.max(0, limit);
        if (cap == 0 || windows.size() <= cap) {
            return windows;
        }
        return List.copyOf(windows.subList(0, cap));
    }

    public long skippedChunks() {
        return skippedChunks.sumThenReset();
    }

    public int trackedChunks() {
        return rows.size();
    }

    static final class Row {
        private final LongAdder[] allowed;
        private final LongAdder[] suppressed;

        Row() {
            int types = ThrottleType.values().length;
            this.allowed = new LongAdder[types];
            this.suppressed = new LongAdder[types];
            for (int i = 0; i < types; i++) {
                allowed[i] = new LongAdder();
                suppressed[i] = new LongAdder();
            }
        }

        void add(ThrottleType type, boolean cancelled) {
            int index = type.ordinal();
            if (cancelled) {
                suppressed[index].increment();
            } else {
                allowed[index].increment();
            }
        }

        Window snapshot(ChunkKey key) {
            long[] observed = new long[ThrottleType.values().length];
            long[] cancelled = new long[ThrottleType.values().length];
            long total = 0L;
            for (ThrottleType type : ThrottleType.values()) {
                int index = type.ordinal();
                observed[index] = allowed[index].sum();
                cancelled[index] = suppressed[index].sum();
                total += observed[index] + cancelled[index];
            }
            return new Window(key, observed, cancelled, total);
        }
    }

    public record Window(ChunkKey chunk, long[] observed, long[] suppressed, long total) {
        public long observed(ThrottleType type) {
            return observed[type.ordinal()];
        }

        public long suppressed(ThrottleType type) {
            return suppressed[type.ordinal()];
        }
    }
}
