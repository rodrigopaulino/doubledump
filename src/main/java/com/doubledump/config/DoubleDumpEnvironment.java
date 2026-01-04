package com.doubledump.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Holds shared configuration, file paths, and logging utilities for the app.
 */
public final class DoubleDumpEnvironment {
    private final Path cacheDir;
    private final Path defaultCacheDb;
    private final Path skippedLog;
    private final ObjectMapper mapper;
    private final DateTimeFormatter logFormatter;
    private final DateTimeFormatter rotateFormatter;
    private final Object skippedLock = new Object();
    private volatile boolean debugEnabled;

    public DoubleDumpEnvironment() {
        String home = System.getProperty("user.home");
        this.cacheDir = Path.of(home, ".cache", "doubledump");
        this.defaultCacheDb = Path.of(home, ".doubledump_cache.db");
        this.skippedLog = cacheDir.resolve("skipped_inputs.log");
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        this.logFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
        this.rotateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());
        this.debugEnabled = "1".equals(System.getenv().getOrDefault("DX2_DEBUG", "0"));
    }

    public void ensureCacheDir() throws IOException {
        Files.createDirectories(cacheDir);
    }

    public void setDebugEnabled(boolean enabled) {
        this.debugEnabled = enabled;
    }

    public boolean isDebugEnabled() {
        return debugEnabled;
    }

    public Path cacheDir() {
        return cacheDir;
    }

    public Path defaultCacheDb() {
        return defaultCacheDb;
    }

    public Path skippedLog() {
        return skippedLog;
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public DateTimeFormatter rotateFormatter() {
        return rotateFormatter;
    }

    public String formatRotationTimestamp(Instant instant) {
        return rotateFormatter.format(instant);
    }

    public void logSkippedInput(String reason, Path path, String detail) {
        try {
            ensureCacheDir();
            StringBuilder line = new StringBuilder()
                    .append(logFormatter.format(Instant.now()))
                    .append('\t').append(reason)
                    .append('\t').append(path);
            if (debugEnabled && detail != null && !detail.isBlank()) {
                line.append('\t').append(formatDebugPayload(detail));
            }
            synchronized (skippedLock) {
                Files.writeString(skippedLog, line.append(System.lineSeparator()).toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException ex) {
            if (debugEnabled) {
                System.err.println("Failed to write skipped log: " + ex.getMessage());
            }
        }
    }

    public String formatDebugPayload(String payload) {
        return payload == null ? "" : payload.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
    }

    public String humanBytes(long bytes) {
        if (bytes <= 0) {
            return "0B";
        }
        final String[] units = {"B", "KiB", "MiB", "GiB", "TiB"};
        double value = bytes;
        int unitIndex = 0;
        while (value >= 1024 && unitIndex < units.length - 1) {
            value /= 1024;
            unitIndex++;
        }
        return String.format(Locale.US, "%.1f%s", value, units[unitIndex]);
    }
}
