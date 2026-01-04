package com.doubledump.cli;

import com.doubledump.config.DoubleDumpEnvironment;
import com.doubledump.dup.DuplicateFinder;
import com.doubledump.hash.HasherException;
import com.doubledump.hash.MediaHasher;
import com.doubledump.model.Action;
import com.doubledump.model.DuplicateStats;
import com.doubledump.storage.CacheStore;
import com.doubledump.system.CommandLocator;
import com.doubledump.system.ProcessResult;
import com.doubledump.system.ProcessUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public final class DoubleDumpApp {
    private static final String BRAND_NAME = "DoubleDump";
    private static final String CLI_NAME = "dx2";
    private final DoubleDumpEnvironment env;

    private DoubleDumpApp(DoubleDumpEnvironment env) {
        this.env = env;
    }

    public static void main(String[] args) {
        DoubleDumpEnvironment env = new DoubleDumpEnvironment();
        try {
            env.ensureCacheDir();
            ensureRequiredCommands();
            DoubleDumpApp app = new DoubleDumpApp(env);
            int exit = app.dispatch(args);
            System.exit(exit);
        } catch (IllegalArgumentException ex) {
            System.err.println("ERROR: " + ex.getMessage());
            printUsage();
            System.exit(1);
        } catch (Exception ex) {
            ex.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private int dispatch(String[] rawArgs) throws Exception {
        if (rawArgs.length == 0) {
            printUsage();
            return 1;
        }
        List<String> args = new ArrayList<>(Arrays.asList(rawArgs));
        while (!args.isEmpty() && args.get(0).startsWith("--")) {
            String flag = args.get(0);
            if ("--debug".equals(flag)) {
                env.setDebugEnabled(true);
                args.remove(0);
            } else if ("--".equals(flag)) {
                args.remove(0);
                break;
            } else {
                break;
            }
        }
        if (args.isEmpty()) {
            printUsage();
            return 1;
        }
        String command = args.remove(0);
        switch (command) {
            case "find-duplicates":
                return runFindDuplicates(args);
            case "compare":
                return runCompare(args);
            case "compare-pixels":
                return runComparePixels(args);
            case "hash":
                return runHash(args);
            default:
                throw new IllegalArgumentException("Unknown command: " + command);
        }
    }

    private int runFindDuplicates(List<String> args) throws Exception {
        if (args.isEmpty()) {
            throw new IllegalArgumentException("path missing");
        }
        Path root = Paths.get(args.remove(0));
        Path cacheDb = env.defaultCacheDb();
        int jobs = Runtime.getRuntime().availableProcessors();
        Action action = Action.PRINT;
        Path trashDir = Path.of(System.getProperty("user.home"), ".Trash", BRAND_NAME);

        for (int i = 0; i < args.size(); ) {
            String flag = args.get(i);
            switch (flag) {
                case "--cache-db":
                    cacheDb = Paths.get(requireValue(args, i));
                    i += 2;
                    break;
                case "--jobs":
                    jobs = Integer.parseInt(requireValue(args, i));
                    i += 2;
                    break;
                case "--action":
                    action = Action.fromUserInput(requireValue(args, i));
                    i += 2;
                    break;
                case "--trash-dir":
                    trashDir = Paths.get(requireValue(args, i));
                    i += 2;
                    break;
                default:
                    throw new IllegalArgumentException("Unknown option: " + flag);
            }
        }

        try (CacheStore cache = new CacheStore(cacheDb, env)) {
            MediaHasher hasher = new MediaHasher(env, cache);
            DuplicateFinder finder = new DuplicateFinder(hasher, env);
            DuplicateStats stats = finder.scan(root, jobs, action, trashDir);
            if (stats.totalFiles() == 0) {
                System.out.println("No media files found.");
                return 0;
            }
            System.out.printf("Scan complete: found %d duplicate groups%n", stats.duplicateGroups());
            System.out.printf("Estimated recoverable: %s%n", env.humanBytes(stats.reclaimableBytes()));
            return 0;
        }
    }

    private int runCompare(List<String> args) throws Exception {
        if (args.size() != 2) {
            throw new IllegalArgumentException("compare needs two filenames");
        }
        Path first = Paths.get(args.get(0));
        Path second = Paths.get(args.get(1));
        MediaHasher hasher = new MediaHasher(env, null);
        try {
            String h1 = hasher.computeHash(first);
            String h2 = hasher.computeHash(second);
            if (h1.equals(h2)) {
                System.out.println("IDENTICAL (ignoring metadata) — " + h1);
                return 0;
            }
            System.out.println("DIFFER — " + h1 + " vs " + h2);
            return 1;
        } catch (HasherException ex) {
            Path offender = ex.problematic() != null ? ex.problematic() : first;
            env.logSkippedInput(ex.reason(), offender, ex.detail());
            System.err.println("Compare skipped for " + problematicPath(ex, first, second) + ". See " + env.skippedLog() + ".");
            return 2;
        }
    }

    private int runComparePixels(List<String> args) throws Exception {
        if (args.size() != 2) {
            throw new IllegalArgumentException("compare-pixels needs two images");
        }
        Path f1 = Paths.get(args.get(0));
        Path f2 = Paths.get(args.get(1));
        MediaHasher hasher = new MediaHasher(env, null);
        Path tmp1 = null;
        Path tmp2 = null;
        try {
            tmp1 = Files.createTempFile(env.cacheDir(), "p1", ".img");
            tmp2 = Files.createTempFile(env.cacheDir(), "p2", ".img");
            hasher.normalizeRasterForCompare(f1, tmp1);
            hasher.normalizeRasterForCompare(f2, tmp2);
            ProcessResult result = ProcessUtils.run(List.of("compare", "-metric", "RMSE", tmp1.toString(), tmp2.toString(), "null:"));
            System.out.println("RMSE = " + result.stderr().trim() + " (0 = identical)");
            return 0;
        } catch (IOException ex) {
            env.logSkippedInput("io-error", f1, ex.getMessage());
            System.err.println("Compare-pixels failed: " + ex.getMessage());
            return 2;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            System.err.println("compare-pixels interrupted");
            return 2;
        } catch (HasherException ex) {
            Path offender = ex.problematic() != null ? ex.problematic() : f1;
            env.logSkippedInput(ex.reason(), offender, ex.detail());
            System.err.println("Compare-pixels skipped: " + ex.getMessage());
            return 2;
        } finally {
            if (tmp1 != null) {
                Files.deleteIfExists(tmp1);
            }
            if (tmp2 != null) {
                Files.deleteIfExists(tmp2);
            }
        }
    }

    private int runHash(List<String> args) throws Exception {
        if (args.size() != 1) {
            throw new IllegalArgumentException("hash needs a file");
        }
        Path file = Paths.get(args.get(0));
        MediaHasher hasher = new MediaHasher(env, null);
        try {
            String hash = hasher.computeHash(file);
            System.out.println(hash);
            return 0;
        } catch (HasherException ex) {
            Path offender = ex.problematic() != null ? ex.problematic() : file;
            env.logSkippedInput(ex.reason(), offender, ex.detail());
            System.err.println("Hash skipped for " + file + ": See " + env.skippedLog() + ".");
            return 2;
        }
    }

    private static String requireValue(List<String> args, int index) {
        int valueIndex = index + 1;
        if (valueIndex >= args.size()) {
            throw new IllegalArgumentException("Missing value for " + args.get(index));
        }
        return args.get(valueIndex);
    }

    private static void printUsage() {
        System.out.println(BRAND_NAME + " (" + CLI_NAME + ") — media dedupe tool (complete)");
        System.out.println("Usage:");
        System.out.println("  " + CLI_NAME + " [--debug] find-duplicates <path> [--cache-db PATH] [--jobs N] [--action print|hardlink|symlink|move|none] [--trash-dir PATH]");
        System.out.println("  " + CLI_NAME + " [--debug] compare <file1> <file2>");
        System.out.println("  " + CLI_NAME + " [--debug] compare-pixels <file1> <file2>");
        System.out.println("  " + CLI_NAME + " [--debug] hash <file>");
    }

    private static void ensureRequiredCommands() {
        List<String> required = List.of("exiftool", "dcraw", "ffmpeg", "compare");
        List<String> missing = required.stream().filter(cmd -> !CommandLocator.isOnPath(cmd)).collect(Collectors.toList());
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Missing required commands: " + String.join(", ", missing));
        }
    }

    private static String problematicPath(HasherException ex, Path first, Path second) {
        if (ex.problematic() != null) {
            return ex.problematic().toString();
        }
        if (first != null && second != null) {
            return first + " / " + second;
        }
        return "one of the provided paths";
    }
}
