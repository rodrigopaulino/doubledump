package com.doubledump.system;

import java.nio.file.Files;
import java.nio.file.Path;

public final class CommandLocator {
    private CommandLocator() {
    }

    public static boolean isOnPath(String command) {
        return locateSingle(command) != null;
    }

    public static String firstAvailable(String... commands) {
        for (String cmd : commands) {
            String located = locateSingle(cmd);
            if (located != null) {
                return located;
            }
        }
        return null;
    }

    private static String locateSingle(String command) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isBlank()) {
            return null;
        }
        String separator = System.getProperty("path.separator");
        for (String dir : pathEnv.split(separator)) {
            if (dir.isBlank()) {
                continue;
            }
            Path candidate = Path.of(dir, command);
            if (Files.isExecutable(candidate)) {
                return command;
            }
        }
        return null;
    }
}
