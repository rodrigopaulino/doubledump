package com.doubledump.system;

public record ProcessResult(int exitCode, String stdout, String stderr) {
}
