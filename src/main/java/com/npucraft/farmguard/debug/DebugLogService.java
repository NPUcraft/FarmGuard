package com.npucraft.farmguard.debug;

import com.npucraft.farmguard.config.FarmGuardSettings;
import com.npucraft.farmguard.util.JsonLines;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Async JSONL diagnostic writer. The main thread only offers immutable records
 * into a bounded queue. File IO, rotation, and JSON encoding run on
 * {@code FarmGuard-DebugLog}.
 */
public final class DebugLogService {

    public static final int DEFAULT_QUEUE_CAPACITY = 4096;
    public static final String RELATIVE_PATH = "plugins/FarmGuard/logs/debug.log";
    static final long FLUSH_INTERVAL_MS = 2_000L;
    static final int FLUSH_EVERY_ENTRIES = 32;
    static final long DROP_WARN_INTERVAL_MS = 60_000L;

    private final Path logsDir;
    private final Path currentFile;
    private final Logger logger;
    private final int queueCapacity;
    private final ArrayBlockingQueue<DebugLogRecord> queue;
    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private final AtomicBoolean writerRunning = new AtomicBoolean(false);
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong droppedSinceWarn = new AtomicLong();
    private volatile Thread writer;
    private volatile String sessionId = "";
    private volatile long maxBytes = 32L * 1024L * 1024L;
    private volatile int maxFiles = 5;
    private volatile BufferedWriter out;
    private volatile long currentSize;
    private volatile long lastDropWarnMs;
    private volatile long lastIoWarnMs;
    private volatile boolean failNextRotation;

    public DebugLogService(File dataFolder, Logger logger) {
        this(dataFolder, logger, DEFAULT_QUEUE_CAPACITY);
    }

    DebugLogService(File dataFolder, Logger logger, int queueCapacity) {
        File folder = dataFolder == null ? new File("FarmGuard") : dataFolder;
        this.logsDir = new File(folder, "logs").toPath();
        this.currentFile = logsDir.resolve("debug.log");
        this.logger = logger;
        this.queueCapacity = Math.max(8, queueCapacity);
        this.queue = new ArrayBlockingQueue<>(this.queueCapacity);
    }

    public boolean enabled() {
        return enabled.get();
    }

    public boolean isActive() {
        return enabled.get() && writerAlive();
    }

    public boolean writerAlive() {
        Thread thread = writer;
        return thread != null && thread.isAlive();
    }

    public String sessionId() {
        return sessionId;
    }

    public long droppedLogEntries() {
        return dropped.get();
    }

    public File logFile() {
        return currentFile.toFile();
    }

    public String relativePath() {
        return RELATIVE_PATH;
    }

    public Path logsDirectory() {
        return logsDir;
    }

    public synchronized void start(FarmGuardSettings settings) {
        applyLimits(settings);
        if (writerRunning.get()) {
            enabled.set(true);
            return;
        }
        sessionId = newSessionId();
        dropped.set(0L);
        droppedSinceWarn.set(0L);
        lastDropWarnMs = 0L;
        queue.clear();
        writerRunning.set(true);
        enabled.set(true);
        Thread thread = new Thread(this::runWriter, "FarmGuard-DebugLog");
        thread.setDaemon(true);
        writer = thread;
        thread.start();
    }

    public void applyLimits(FarmGuardSettings settings) {
        if (settings == null) {
            return;
        }
        this.maxBytes = Math.max(1024L, settings.debugMaxFileSizeMb() * 1024L * 1024L);
        this.maxFiles = Math.max(1, settings.debugMaxFiles());
    }

    void overrideLimitsForTest(long maxBytes, int maxFiles) {
        this.maxBytes = Math.max(32L, maxBytes);
        this.maxFiles = Math.max(1, maxFiles);
    }

    void failNextRotationForTest() {
        this.failNextRotation = true;
    }

    public boolean offer(DebugLogRecord record) {
        if (!enabled.get() || record == null || !writerRunning.get()) {
            return false;
        }
        int keepForHigh = Math.max(1, queueCapacity / 8);
        if (record.priority() == DebugPriority.NORMAL && queue.remainingCapacity() <= keepForHigh) {
            dropped.incrementAndGet();
            droppedSinceWarn.incrementAndGet();
            return false;
        }
        if (queue.offer(record)) {
            return true;
        }
        dropped.incrementAndGet();
        droppedSinceWarn.incrementAndGet();
        return false;
    }

    public synchronized void stop() {
        enabled.set(false);
        if (!writerRunning.get() && writer == null) {
            return;
        }
        writerRunning.set(false);
        Thread thread = writer;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(5_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        writer = null;
        queue.clear();
        sessionId = "";
    }

    private void runWriter() {
        int unflushed = 0;
        long lastFlush = System.currentTimeMillis();
        try {
            openWriter();
            while (true) {
                DebugLogRecord record = takeNext();
                if (record == null) {
                    if (!writerRunning.get() && queue.isEmpty()) {
                        break;
                    }
                } else {
                    write(record);
                    unflushed++;
                }
                long now = System.currentTimeMillis();
                if (unflushed > 0 && (unflushed >= FLUSH_EVERY_ENTRIES || now - lastFlush >= FLUSH_INTERVAL_MS)) {
                    flushQuietly();
                    unflushed = 0;
                    lastFlush = now;
                }
                maybeWarnDropped(now);
            }
        } catch (IOException exception) {
            // Writer-thread IO only. Never call DebugDiagnostics / offer() here:
            // a failed disk must not recurse into the same logger.
            warnThrottled("Debug log writer failed: " + exception.getMessage());
        } finally {
            try {
                DebugLogRecord leftover;
                while ((leftover = queue.poll()) != null) {
                    try {
                        write(leftover);
                    } catch (IOException ignored) {
                        break;
                    }
                }
                flushQuietly();
            } finally {
                closeWriter();
                writerRunning.set(false);
                enabled.set(false);
            }
        }
    }

    private DebugLogRecord takeNext() {
        try {
            DebugLogRecord record = queue.poll(writerRunning.get() ? 200L : 1L, TimeUnit.MILLISECONDS);
            if (record != null) {
                return record;
            }
            return writerRunning.get() ? null : queue.poll();
        } catch (InterruptedException ignored) {
            return queue.poll();
        }
    }

    private void write(DebugLogRecord record) throws IOException {
        maybeRotate();
        if (out == null) {
            openWriter();
        }
        String line = encode(record);
        out.write(line);
        out.newLine();
        currentSize += line.length() + 1L;
    }

    private String encode(DebugLogRecord record) {
        LinkedHashMap<String, Object> line = new LinkedHashMap<>();
        line.put("ts", iso(record.tsMillis()));
        line.put("schema", DebugLogRecord.SCHEMA);
        line.put("session", sessionId);
        line.put("type", record.type());
        for (Map.Entry<String, Object> entry : record.fields().entrySet()) {
            String key = entry.getKey();
            if ("ts".equals(key) || "schema".equals(key) || "session".equals(key) || "type".equals(key)) {
                continue;
            }
            line.put(key, entry.getValue());
        }
        return JsonLines.object(line);
    }

    static String iso(long epochMs) {
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault())
                .truncatedTo(ChronoUnit.MILLIS)
                .toString();
    }

    static String newSessionId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private void maybeRotate() throws IOException {
        if (out == null) {
            return;
        }
        if (currentSize < maxBytes) {
            return;
        }
        closeWriter();
        try {
            rotateFiles();
        } catch (IOException exception) {
            warnThrottled("Debug log rotation failed: " + exception.getMessage());
        }
        openWriter();
    }

    private void rotateFiles() throws IOException {
        if (failNextRotation) {
            failNextRotation = false;
            throw new IOException("injected rotation failure");
        }
        int generations = Math.max(1, maxFiles);
        Path oldest = logsDir.resolve("debug." + (generations - 1) + ".log");
        Files.deleteIfExists(oldest);
        for (int i = generations - 2; i >= 1; i--) {
            Path from = logsDir.resolve("debug." + i + ".log");
            Path to = logsDir.resolve("debug." + (i + 1) + ".log");
            if (Files.exists(from)) {
                Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        if (generations > 1 && Files.exists(currentFile)) {
            Files.move(currentFile, logsDir.resolve("debug.1.log"), StandardCopyOption.REPLACE_EXISTING);
        } else if (generations == 1) {
            Files.deleteIfExists(currentFile);
        }
    }

    private void openWriter() throws IOException {
        Files.createDirectories(logsDir);
        boolean append = Files.exists(currentFile);
        out = Files.newBufferedWriter(
                currentFile,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING
        );
        currentSize = Files.exists(currentFile) ? Files.size(currentFile) : 0L;
    }

    private void flushQuietly() {
        BufferedWriter writer = out;
        if (writer == null) {
            return;
        }
        try {
            writer.flush();
        } catch (IOException exception) {
            warnThrottled("Debug log flush failed: " + exception.getMessage());
        }
    }

    private void closeWriter() {
        BufferedWriter writer = out;
        out = null;
        if (writer == null) {
            return;
        }
        try {
            writer.flush();
            writer.close();
        } catch (IOException exception) {
            warnThrottled("Debug log close failed: " + exception.getMessage());
        }
        currentSize = 0L;
    }

    private void maybeWarnDropped(long nowMs) {
        long pending = droppedSinceWarn.get();
        if (pending <= 0L) {
            return;
        }
        if (nowMs - lastDropWarnMs < DROP_WARN_INTERVAL_MS) {
            return;
        }
        lastDropWarnMs = nowMs;
        droppedSinceWarn.addAndGet(-pending);
        if (logger != null) {
            logger.warning("[FarmGuard] Debug log queue overloaded; " + pending + " entries were dropped.");
        }
    }

    private void warnThrottled(String message) {
        long now = System.currentTimeMillis();
        if (now - lastIoWarnMs < DROP_WARN_INTERVAL_MS && lastIoWarnMs != 0L) {
            return;
        }
        lastIoWarnMs = now;
        if (logger != null) {
            logger.warning("[FarmGuard] " + message);
        }
    }
}
