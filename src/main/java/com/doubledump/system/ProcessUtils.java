package com.doubledump.system;

import com.doubledump.hash.HasherException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class ProcessUtils {
    private ProcessUtils() {
    }

    public static ProcessResult run(List<String> command) throws HasherException, InterruptedException {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            Process process = pb.start();
            byte[] stdout = process.getInputStream().readAllBytes();
            byte[] stderr = process.getErrorStream().readAllBytes();
            int exit = process.waitFor();
            return new ProcessResult(exit, new String(stdout, StandardCharsets.UTF_8), new String(stderr, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new HasherException("command-error", null, command.get(0) + ": " + ex.getMessage());
        }
    }
}
