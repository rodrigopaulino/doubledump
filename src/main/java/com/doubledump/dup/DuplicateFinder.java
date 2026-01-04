package com.doubledump.dup;

import com.doubledump.config.DoubleDumpEnvironment;
import com.doubledump.hash.HasherException;
import com.doubledump.hash.MediaHasher;
import com.doubledump.model.Action;
import com.doubledump.model.DuplicateGroupRecord;
import com.doubledump.model.DuplicateStats;
import com.doubledump.model.FileHash;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class DuplicateFinder {
    private static final Set<String> MEDIA_EXT = Set.of("png", "gif", "jpg", "jpeg", "dng", "mp4", "mov");

    private final MediaHasher hasher;
    private final DoubleDumpEnvironment env;

    public DuplicateFinder(MediaHasher hasher, DoubleDumpEnvironment env) {
        this.hasher = hasher;
        this.env = env;
    }

    public DuplicateStats scan(Path root, int jobs, Action action, Path trashDir) throws Exception {
        Path absoluteRoot = root.toRealPath();
        if (!Files.isDirectory(absoluteRoot)) {
            throw new IllegalArgumentException("path missing");
        }
        List<Path> files = collectMediaFiles(absoluteRoot);
        int total = files.size();
        if (total == 0) {
            return new DuplicateStats(0, 0, 0L);
        }
        System.out.printf("Scanning for media under: %s%n", absoluteRoot);
        System.out.printf("Found %d media files. Starting hashing with %d jobs...%n", total, jobs);

        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, jobs));
        Map<String, List<Path>> groups = new ConcurrentHashMap<>();
        List<Future<FileHash>> futures = new ArrayList<>(total);
        for (Path file : files) {
            futures.add(pool.submit(() -> hasher.hashWithCache(file)));
        }
        pool.shutdown();
        for (Future<FileHash> future : futures) {
            try {
                FileHash fh = future.get();
                if (fh == null) {
                    continue;
                }
                groups.computeIfAbsent(fh.hash(), key -> new CopyOnWriteArrayList<>()).add(fh.path());
            } catch (ExecutionException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof HasherException) {
                    HasherException hex = (HasherException) cause;
                    env.logSkippedInput(hex.reason(), hex.problematic(), hex.detail());
                } else {
                    env.logSkippedInput("hashing-error", absoluteRoot, cause == null ? "unknown" : cause.getMessage());
                }
            }
        }
        try {
            pool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }

        Map<String, List<Path>> duplicates = groups.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .collect(Collectors.toMap(Map.Entry::getKey, e -> new ArrayList<>(e.getValue())));

        long reclaimable = 0L;
        List<DuplicateGroupRecord> records = new ArrayList<>();
        int processed = 0;
        for (Map.Entry<String, List<Path>> entry : duplicates.entrySet()) {
            List<Path> paths = entry.getValue();
            paths.sort(Comparator.naturalOrder());
            Path keep = paths.get(0);
            long groupReclaim = 0L;
            for (int i = 1; i < paths.size(); i++) {
                Path candidate = paths.get(i);
                try {
                    groupReclaim += Files.size(candidate);
                } catch (IOException ignored) {
                    // best-effort reclaim estimate
                }
            }
            reclaimable += groupReclaim;
            applyAction(action, entry.getKey(), keep, paths, trashDir);
            records.add(new DuplicateGroupRecord(entry.getKey(), paths.size(), keep.toString(),
                    paths.stream().map(Path::toString).collect(Collectors.toList())));
            processed++;
            if (processed % 10 == 0 || processed == duplicates.size()) {
                System.err.printf("Organizing duplicate groups (%d/%d)%n", processed, duplicates.size());
            }
        }

        writeResults(records);
        writeStats(total, records.size(), reclaimable);
        return new DuplicateStats(total, records.size(), reclaimable);
    }

    private static void applyAction(Action action, String hash, Path keep, List<Path> paths, Path trashDir) {
        switch (action) {
            case PRINT:
                System.out.println();
                System.out.println("=== Duplicate group (hash: " + hash + ") ===");
                for (Path p : paths) {
                    System.out.println("  " + p);
                }
                break;
            case HARDLINK:
                for (int i = 1; i < paths.size(); i++) {
                    Path dup = paths.get(i);
                    try {
                        Files.deleteIfExists(dup);
                        Files.createLink(dup, keep);
                        System.out.printf("Hardlinked %s -> %s%n", dup, keep);
                    } catch (IOException ex) {
                        System.err.printf("Failed to hardlink %s: %s%n", dup, ex.getMessage());
                    }
                }
                break;
            case SYMLINK:
                for (int i = 1; i < paths.size(); i++) {
                    Path dup = paths.get(i);
                    Path backup = dup.resolveSibling(dup.getFileName() + ".doubledump.bak");
                    try {
                        Files.move(dup, backup, StandardCopyOption.REPLACE_EXISTING);
                        Files.createSymbolicLink(dup, keep);
                        Files.deleteIfExists(backup);
                        System.out.printf("Symlinked %s -> %s%n", dup, keep);
                    } catch (IOException ex) {
                        System.err.printf("Failed to symlink %s: %s%n", dup, ex.getMessage());
                        try {
                            if (Files.exists(backup)) {
                                Files.move(backup, dup, StandardCopyOption.REPLACE_EXISTING);
                            }
                        } catch (IOException ignored) {
                            // already logged
                        }
                    }
                }
                break;
            case MOVE:
                try {
                    Files.createDirectories(trashDir);
                } catch (IOException ex) {
                    System.err.printf("Failed to create trash dir %s: %s%n", trashDir, ex.getMessage());
                    return;
                }
                for (int i = 1; i < paths.size(); i++) {
                    Path dup = paths.get(i);
                    Path dest = trashDir.resolve(dup.getFileName());
                    try {
                        Files.move(dup, dest, StandardCopyOption.REPLACE_EXISTING);
                        System.out.printf("Moved %s -> %s%n", dup, dest);
                    } catch (IOException ex) {
                        System.err.printf("Failed to move %s: %s%n", dup, ex.getMessage());
                    }
                }
                break;
            case NONE:
                break;
        }
    }

    private List<Path> collectMediaFiles(Path root) throws IOException {
        FileStore rootStore = Files.getFileStore(root);
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> sameStore(path, rootStore))
                    .filter(DuplicateFinder::isSupportedMedia)
                    .collect(Collectors.toList());
        }
    }

    private static boolean sameStore(Path path, FileStore rootStore) {
        try {
            return Files.getFileStore(path).equals(rootStore);
        } catch (IOException ex) {
            return false;
        }
    }

    private static boolean isSupportedMedia(Path path) {
        String ext = extension(path.getFileName().toString());
        return MEDIA_EXT.contains(ext);
    }

    private void writeResults(List<DuplicateGroupRecord> records) {
        Path results = env.cacheDir().resolve("last_scan.json");
        try {
            rotateIfExists(results);
            env.mapper().writeValue(results.toFile(), records);
        } catch (IOException ex) {
            System.err.printf("Failed to write %s: %s%n", results, ex.getMessage());
        }
    }

    private void writeStats(int total, int groups, long reclaimable) {
        Path stats = env.cacheDir().resolve("stats.json");
        try {
            env.mapper().writeValue(stats.toFile(), Map.of(
                    "total", total,
                    "duplicate_groups", groups,
                    "space_reclaimable_bytes", reclaimable
            ));
        } catch (IOException ex) {
            System.err.printf("Failed to write %s: %s%n", stats, ex.getMessage());
        }
    }

    private void rotateIfExists(Path file) throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        Instant ts = Files.getLastModifiedTime(file).toInstant();
        String baseName = file.getFileName().toString();
        String rotated = baseName.replaceFirst("\\.json$", "") + "_" + env.formatRotationTimestamp(ts) + ".json";
        Path backup = file.resolveSibling(rotated);
        Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String extension(String filename) {
        int idx = filename.lastIndexOf('.');
        if (idx == -1 || idx == filename.length() - 1) {
            return "";
        }
        return filename.substring(idx + 1).toLowerCase(Locale.ROOT);
    }
}
